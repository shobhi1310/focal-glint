package com.focal.intelligence

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

class ClassifyNotificationTool : ToolSet {
    var lastCategory: String? = null
        private set
    var lastReason: String? = null
        private set

    @Tool("Classify whether a notification matters to the user personally")
    fun classifyNotification(
        @ToolParam("Classification result: 'matters' if personally relevant to the user, 'noise' if generic, promotional, or irrelevant")
        category: String,
        @ToolParam("Short reason explaining the classification decision")
        reason: String
    ): Map<String, Any> {
        Log.i(TAG, "called: category=$category reason=${reason.take(100)}")
        lastCategory = category
        lastReason = reason
        return mapOf("status" to "ok")
    }

    companion object {
        private const val TAG = "ClassifyTool"
    }
}
