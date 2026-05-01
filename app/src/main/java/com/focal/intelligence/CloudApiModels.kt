package com.focal.intelligence

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = "auto",
    @SerialName("max_tokens") val maxTokens: Int = 512,
    val temperature: Double = 0.1
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallResponse>? = null
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: FunctionParameters
)

@Serializable
data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, PropertyDefinition>,
    val required: List<String>
)

@Serializable
data class PropertyDefinition(
    val type: String,
    val description: String? = null,
    val enum: List<String>? = null
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice>
)

@Serializable
data class Choice(
    val message: ChatMessage
)

@Serializable
data class ToolCallResponse(
    val id: String? = null,
    val type: String? = "function",
    val function: FunctionCallResponse
)

@Serializable
data class FunctionCallResponse(
    val name: String,
    val arguments: String
)
