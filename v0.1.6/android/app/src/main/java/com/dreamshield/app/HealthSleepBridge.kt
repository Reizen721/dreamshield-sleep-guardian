package com.dreamshield.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

data class HealthSleepStage(val startMillis: Long, val endMillis: Long, val kind: Int) {
    val label: String get() = when(kind) { 1 -> text(R.string.copy_awake); 2 -> text(R.string.copy_asleep); 3 -> text(R.string.copy_out_of_bed); 4 -> text(R.string.copy_light); 5 -> text(R.string.copy_deep); 6 -> "REM"; else -> text(R.string.copy_unclassified) }
}
data class HealthSleepSession(
    val id: String, val startMillis: Long, val endMillis: Long,
    val sourcePackage: String, val sourceName: String, val stages: List<HealthSleepStage>
)
data class HealthSleepResult(val status: String, val message: String, val sessions: List<HealthSleepSession>,
    val readAt: Long = 0, val days: Int = 7)

/** Reads only explicitly authorized existing health records, never fabricates or writes sleep. */
class HealthSleepBridge(context: Context) {
    companion object { private val cacheGate = HealthCacheGate() }
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("dreamshield_health_sleep_v1", Context.MODE_PRIVATE)
    val permissions: Set<String> = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))
    fun availability(): Int = runCatching { HealthConnectClient.getSdkStatus(context) }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()
    suspend fun granted(): Boolean = withContext(Dispatchers.IO) {
        availability() == HealthConnectClient.SDK_AVAILABLE &&
            runCatching { HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(permissions) }.getOrDefault(false)
    }
    fun cached(): HealthSleepResult = cacheGate.read {
        runCatching {
            val root = JSONObject(prefs.getString("records", "{}") ?: "{}")
            val rows = root.optJSONArray("sessions") ?: JSONArray()
            val result = (0 until rows.length().coerceAtMost(1000)).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val start = row.optLong("start")
                val end = row.optLong("end")
                if (start <= 0 || end <= start || end > System.currentTimeMillis() + 60_000) return@mapNotNull null
                val stageArray = row.optJSONArray("stages") ?: JSONArray()
                val stages = (0 until stageArray.length().coerceAtMost(2000)).mapNotNull { s ->
                    val stage = stageArray.optJSONObject(s) ?: return@mapNotNull null
                    val from = stage.optLong("start"); val to = stage.optLong("end")
                    if (from < start || to > end || from >= to) null else HealthSleepStage(from, to, stage.optInt("kind"))
                }
                HealthSleepSession(row.optString("id"), start, end, row.optString("package"),
                    row.optString("source"), stages)
            }
            HealthSleepResult("cached", if (result.isEmpty()) text(R.string.copy_allow_access_to_import_sleep_records) else text(R.string.copy_last_import_on_this_phone),
                result, root.optLong("readAt"), root.optInt("days", 7))
        }.getOrDefault(HealthSleepResult("empty", text(R.string.copy_allow_access_to_import_sleep_records), emptyList()))
    }
    suspend fun refresh(days: Int = 7): HealthSleepResult = withContext(Dispatchers.IO) {
        val generation = cacheGate.snapshot()
        val rangeDays = if (days == 30) 30 else 7
        val available = availability()
        if (available != HealthConnectClient.SDK_AVAILABLE) return@withContext HealthSleepResult(
            if (available == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) "install" else "unavailable",
            if (available == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) text(R.string.copy_install_or_update_health_connect) else text(R.string.copy_health_connect_is_unavailable),
            emptyList(), days = rangeDays)
        try {
            val client = HealthConnectClient.getOrCreate(context)
            if (!client.permissionController.getGrantedPermissions().containsAll(permissions)) {
                clearLocal()
                return@withContext HealthSleepResult("permission", text(R.string.copy_allow_read_access_to_import_sleep_records), emptyList(), days = rangeDays)
            }
            val now = Instant.now()
            val list = mutableListOf<HealthSleepSession>()
            var page: String? = null
            var pages = 0
            do {
                val response = client.readRecords(ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(rangeDays.toLong(), ChronoUnit.DAYS), now),
                    ascendingOrder = false, pageSize = 200, pageToken = page))
                response.records.forEach { record ->
                    val source = record.metadata.dataOrigin.packageName
                    val sourceName = runCatching {
                        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(source, 0)).toString()
                    }.getOrDefault(source)
                    list += HealthSleepSession(record.metadata.id, record.startTime.toEpochMilli(), record.endTime.toEpochMilli(),
                        source, sourceName, record.stages.map {
                            HealthSleepStage(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.stage)
                        })
                }
                page = response.pageToken
                pages++
            } while (page != null && pages < 5)
            val unique = list.distinctBy { it.sourcePackage + ":" + it.id }.sortedByDescending { it.startMillis }.take(1000)
            val result = HealthSleepResult("ready", if (unique.isEmpty()) text(R.string.copy_no_sleep_records_for_this_period) else text(R.string.copy_imported_records_sources_shown_separately), unique, now.toEpochMilli(), rangeDays)
            // OS permission can change while records are being read. Local revoke/clear
            // also invalidates this generation before its platform request begins.
            if (!client.permissionController.getGrantedPermissions().containsAll(permissions)) {
                clearLocal()
                return@withContext HealthSleepResult("permission", text(R.string.copy_sleep_access_revoked_grant_it_again_to_import), emptyList(), days = rangeDays)
            }
            cacheGate.ifCurrent(generation) { save(result); result }
                ?: HealthSleepResult("permission", text(R.string.copy_import_canceled_and_local_copies_cleared), emptyList(), days = rangeDays)
        } catch (_: SecurityException) {
            clearLocal()
            HealthSleepResult("permission", text(R.string.copy_sleep_access_revoked_grant_it_again_to_import), emptyList(), days = rangeDays)
        } catch (_: Exception) {
            cacheGate.ifCurrent(generation) {
                val previous = cached()
                HealthSleepResult("error", text(R.string.copy_health_connect_is_unavailable_try_again_later), previous.sessions, previous.readAt, rangeDays)
            } ?: HealthSleepResult("permission", text(R.string.copy_import_canceled_and_local_copies_cleared), emptyList(), days = rangeDays)
        }
    }
    suspend fun revoke(): HealthSleepResult = withContext(Dispatchers.IO) {
        // Invalidate in-flight reads before awaiting the system's revocation.
        clearLocal()
        val success = if (availability() == HealthConnectClient.SDK_AVAILABLE)
            runCatching { HealthConnectClient.getOrCreate(context).permissionController.revokeAllPermissions(); true }.getOrDefault(false)
            else true
        clearLocal()
        HealthSleepResult("permission", if (success) text(R.string.copy_access_revoked_and_local_copies_cleared) else text(R.string.copy_local_copies_cleared_check_health_connect_permissi), emptyList())
    }
    fun clearLocal() { cacheGate.invalidate { prefs.edit().remove("records").apply() } }
    private fun save(result: HealthSleepResult) {
        val array = JSONArray()
        result.sessions.forEach { row ->
            val stages = JSONArray()
            row.stages.forEach { stages.put(JSONObject().put("start", it.startMillis).put("end", it.endMillis).put("kind", it.kind)) }
            array.put(JSONObject().put("id", row.id).put("start", row.startMillis).put("end", row.endMillis)
                .put("package", row.sourcePackage).put("source", row.sourceName).put("stages", stages))
        }
        prefs.edit().putString("records", JSONObject().put("sessions", array).put("readAt", result.readAt).put("days", result.days).toString()).apply()
    }
    fun openSettings() {
        val intent = Intent(if (android.os.Build.VERSION.SDK_INT >= 34) "android.health.connect.action.HEALTH_HOME_SETTINGS" else "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { openInstall() }
    }
    fun openInstall() {
        val pkg = "com.google.android.apps.healthdata"
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
