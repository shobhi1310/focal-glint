package com.focal.intelligence

import com.google.ai.edge.litertlm.Channel

internal object ThinkingMode {
    private const val THINK_PREFIX = "<|think|>\n"
    private const val THOUGHT_START = "<|channel>thought"
    private const val THOUGHT_END = "<channel|>"

    val thoughtChannel = Channel(
        channelName = "thought",
        start = THOUGHT_START,
        end = THOUGHT_END
    )

    fun withThinkPrefix(instruction: String): String =
        if (instruction.startsWith(THINK_PREFIX)) instruction else THINK_PREFIX + instruction

    fun stripThoughtBlocks(text: String): String {
        if (!text.contains(THOUGHT_START)) return text

        val output = StringBuilder()
        var index = 0
        while (index < text.length) {
            val start = text.indexOf(THOUGHT_START, index)
            if (start < 0) {
                output.append(text.substring(index))
                break
            }
            output.append(text.substring(index, start))
            val contentStart = start + THOUGHT_START.length
            val end = text.indexOf(THOUGHT_END, contentStart)
            if (end < 0) {
                break
            }
            index = end + THOUGHT_END.length
        }
        return output.toString()
    }
}
