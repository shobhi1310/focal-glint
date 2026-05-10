package com.focal.intelligence

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Tool for structured topic card generation. The LLM outputs human-readable
 * action labels and integer app indices — the code resolves index → package name.
 * This avoids asking the LLM to copy package strings verbatim, which is error-prone.
 *
 * availableApps: ordered list of (appName, packageName) — same order as shown in the prompt.
 */
class GenerateTopicCardTool(
    private val availableApps: List<Pair<String, String>>
) : ToolSet {

    var title: String? = null
        private set
    var summary: String? = null
        private set
    val actions: MutableList<SuggestedAction> = mutableListOf()

    @Tool("Generate the topic card for this notification cluster")
    fun generateTopicCard(
        @ToolParam("Short headline summarising what happened — 5 to 8 words, assertive and specific")
        title: String,
        @ToolParam("1 to 2 sentence factual summary — specific names and amounts where relevant, no fluff")
        summary: String,
        @ToolParam("Label for the most important action — direct, e.g. 'Reply to Mom about dinner'")
        action1Label: String,
        @ToolParam("Index of the app for action 1, from the numbered AVAILABLE APPS list. Use -1 if no action is needed")
        action1AppIndex: Int,
        @ToolParam("Label for a second action, or empty string if only one action is needed")
        action2Label: String,
        @ToolParam("Index of the app for action 2. Use -1 if not needed")
        action2AppIndex: Int,
        @ToolParam("Label for a third action, or empty string if fewer than three actions are needed")
        action3Label: String,
        @ToolParam("Index of the app for action 3. Use -1 if not needed")
        action3AppIndex: Int
    ): Map<String, Any> {
        this.title = title.trim().takeCodepointSafe(60)
        this.summary = summary.trim().takeCodepointSafe(300)

        listOf(
            action1Label to action1AppIndex,
            action2Label to action2AppIndex,
            action3Label to action3AppIndex
        ).forEach { (label, index) ->
            if (label.isNotBlank() && index >= 0) {
                val pkg = availableApps.getOrNull(index)?.second
                if (!pkg.isNullOrBlank()) {
                    actions.add(
                        SuggestedAction(
                            label = label.trim(),
                            type = "open_app",
                            app = availableApps[index].first,
                            packageName = pkg
                        )
                    )
                } else {
                    Log.w(TAG, "action index $index out of range (availableApps size=${availableApps.size}), skipping")
                }
            }
        }

        Log.i(TAG, "called: title=${this.title?.take(40)} actions=${actions.size}")
        return mapOf("status" to "ok")
    }

    fun isComplete(): Boolean = title != null && summary != null

    companion object {
        private const val TAG = "TopicCardTool"
    }
}
