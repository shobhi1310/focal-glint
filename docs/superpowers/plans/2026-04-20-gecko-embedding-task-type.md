# Gecko Embedding Task Type Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Switch the default Gecko embedding task type to `SEMANTIC_SIMILARITY` and protect it with a regression test.

**Architecture:** Keep the existing embedding provider API unchanged and update only the default constructor argument. Verify the new default with a focused unit test that inspects provider state without initializing the native model.

**Tech Stack:** Kotlin, JUnit 4, MockK-free JVM unit testing

---

### Task 1: Lock the default in a unit test

**Files:**
- Create: `app/src/test/java/com/focal/intelligence/GeckoEmbeddingProviderTest.kt`
- Modify: `app/src/main/java/com/focal/intelligence/GeckoEmbeddingProvider.kt`
- Test: `app/src/test/java/com/focal/intelligence/GeckoEmbeddingProviderTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `default task type is semantic similarity`() {
    val provider = GeckoEmbeddingProvider()
    val field = provider.javaClass.getDeclaredField("taskType").apply { isAccessible = true }

    assertEquals(EmbedData.TaskType.SEMANTIC_SIMILARITY, field.get(provider))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.focal.intelligence.GeckoEmbeddingProviderTest"`
Expected: FAIL because the provider still defaults to `EmbedData.TaskType.CLUSTERING`

- [ ] **Step 3: Write minimal implementation**

```kotlin
class GeckoEmbeddingProvider(
    private val taskType: EmbedData.TaskType = EmbedData.TaskType.SEMANTIC_SIMILARITY
) : EmbeddingProvider
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.focal.intelligence.GeckoEmbeddingProviderTest"`
Expected: PASS
