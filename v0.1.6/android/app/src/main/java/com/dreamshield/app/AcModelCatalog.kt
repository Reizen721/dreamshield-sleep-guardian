package com.dreamshield.app

import android.content.Context
import org.json.JSONObject
import java.util.Locale

internal data class AcModelChoice(
    val id: String,
    val brand: String,
    val brandLabel: String,
    val model: String,
    val protocol: String,
    val projectProfile: Boolean,
    val search: String
) {
    val localizedBrand: String get() = if (AppLanguage.locale.language == "en") brand else brandLabel
    val displayName: String get() = "$localizedBrand · $model"
    val supportLabel: String get() = if (projectProfile) text(R.string.copy_dreamshield_128_bit) else text(R.string.copy_ir_library)
}

internal class AcModelCatalog(context: Context) {
    private val source = context.resources.openRawResource(R.raw.ac_model_catalog)
        .bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    val libraryVersion: String = source.getString("library_version")
    val models: List<AcModelChoice> = source.getJSONArray("models").let { array ->
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            AcModelChoice(item.getString("id"), item.getString("brand"), item.getString("brand_label"),
                item.getString("model"), item.getString("protocol"), item.getString("source") == "project",
                normalize(item.getString("search")))
        }
    }
    private val byId = models.associateBy { it.id }
    val brands: List<Pair<String, String>> get() = models.map { it.brand to it.localizedBrand }.distinct()
        .sortedWith(compareBy<Pair<String, String>> { if (it.first == "Gree") 0 else 1 }.thenBy { it.second })

    fun find(id: String): AcModelChoice? = byId[id]
    fun search(query: String, brand: String = ""): List<AcModelChoice> {
        val terms = query.trim().split(Regex("\\s+")).map(::normalize).filter { it.isNotEmpty() }
        return models.filter { model -> (brand.isEmpty() || model.brand == brand) && terms.all { model.search.contains(it) } }
    }

    companion object {
        private fun normalize(value: String): String = value.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    }
}
