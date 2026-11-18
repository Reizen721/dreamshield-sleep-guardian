package com.dreamshield.app

import android.app.PendingIntent
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class SleepTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val active = SleepRepository(this).snapshot.active != null
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (active) text(R.string.copy_tonight_in_progress) else text(R.string.copy_dreamshield_tonight)
            subtitle = if (active) text(R.string.copy_open_night_records_825) else text(R.string.copy_start_tonight)
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        val active = SleepRepository(this).snapshot.active != null
        val intent = SleepSystem.openIntent(this, if (active) SleepSystem.OPEN_SLEEP else SleepSystem.START_TONIGHT)
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 6120, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            } else {
                startLegacyActivity(intent)
            }
        }
    }
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startLegacyActivity(intent: Intent) {
        // Android 10–13 have no PendingIntent overload. The platform documents
        // the Intent overload as valid below API 34; never invoke it on 34+.
        // https://developer.android.com/reference/android/service/quicksettings/TileService
        if (Build.VERSION.SDK_INT < 34) startActivityAndCollapse(intent)
    }
}
