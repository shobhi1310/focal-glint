package com.focal.intelligence

import org.json.JSONArray
import org.json.JSONObject

data class SuggestedAction(
    val label: String,
    val type: String,
    val app: String,
    val packageName: String = ""
) {
    companion object {
        val VALID_TYPES = setOf("call", "reply", "open_app", "view", "pay", "track")

        fun fromJson(json: JSONObject): SuggestedAction {
            return SuggestedAction(
                label = json.optString("label", ""),
                type = json.optString("type", "open_app").let {
                    if (it in VALID_TYPES) it else "open_app"
                },
                app = json.optString("app", ""),
                packageName = json.optString("packageName", "")
            )
        }

        fun listFromJson(jsonStr: String?): List<SuggestedAction> {
            if (jsonStr.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(jsonStr)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) { emptyList() }
        }

        fun listToJson(actions: List<SuggestedAction>): String {
            val arr = JSONArray()
            actions.forEach { action ->
                arr.put(JSONObject().apply {
                    put("label", action.label)
                    put("type", action.type)
                    put("app", action.app)
                    put("packageName", action.packageName)
                })
            }
            return arr.toString()
        }
    }
}
