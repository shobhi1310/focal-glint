# Gecko Embedding Task Type Design

**Goal:** Change the default Gecko embedding task type from `CLUSTERING` to `SEMANTIC_SIMILARITY`.

## Scope

This is a narrow behavior change in the on-device embedding provider. No UI, schema, or pipeline structure changes are required.

## Design

- `GeckoEmbeddingProvider` should default to `EmbedData.TaskType.SEMANTIC_SIMILARITY`.
- Constructor override support should remain intact so tests or future experiments can still pass a different task type explicitly.
- Add a unit test that locks the default constructor behavior to `SEMANTIC_SIMILARITY`.

## Testing

- Add a JVM unit test that instantiates `GeckoEmbeddingProvider()` and verifies its default `taskType`.
- Run the targeted provider test after the change.
