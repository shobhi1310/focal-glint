package com.focal.intelligence

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

class BatchClassifyNotificationTool : ToolSet {
    private val results = mutableMapOf<Int, Pair<String, String>>()

    @Tool("Classify a notification from the batch by its index")
    fun classifyNotification(
        @ToolParam("1-based index of the notification being classified")
        index: Int,
        @ToolParam("Must be exactly the word 'matters' or the word 'noise' — no other value is valid")
        category: String,
        @ToolParam("Short snake_case reason explaining the classification decision")
        reason: String
    ): Map<String, Any> {
        Log.i(TAG, "called: index=$index category=$category reason=${reason.take(100)}")
        results[index] = category to reason
        return mapOf("status" to "ok")
    }

    fun getResult(index: Int): Pair<String, String>? = results[index]
    fun resultCount(): Int = results.size

    companion object {
        private const val TAG = "BatchClassifyTool"
    }
}
