# Notification Classification Without Thinking Prefix

## Goal

Remove the Gemma thinking trigger from new and unclassified notification classification while preserving the existing classification prompt text, tool-calling flow, and tool-provided reason.

## Scope

This change applies only to the notification classification path in `Classifier`.

It does not change:
- the prompt content used to describe the classification task
- the `classifyNotification` tool schema
- the tool `reason` field
- other inference paths that may still use thinking mode

## Current Behavior

`Classifier` builds its system instruction by wrapping the classification prompt with `ThinkingMode.withThinkPrefix(...)`. That causes Gemma to emit a thinking token sequence before the tool call path runs.

## Desired Behavior

`Classifier` should send the same classification instruction text to `generateWithTools(...)` without the thinking wrapper.

The model should still:
- call `classifyNotification`
- return category through the tool
- return reason through the tool

## Implementation

Update `Classifier` so the classification system instruction is the raw prompt string instead of the `ThinkingMode.withThinkPrefix(...)` result.

Add or update a unit test to verify:
- `generateWithTools(...)` receives a system instruction without the thinking prefix
- tool-based classification still returns the expected result

## Risks

The main risk is changing classification latency/behavior slightly because the model no longer receives an explicit thinking trigger. Tool execution should remain stable because the prompt text and tool contract stay unchanged.

## Verification

Run focused classifier tests first, then run the relevant debug unit test task if needed.
