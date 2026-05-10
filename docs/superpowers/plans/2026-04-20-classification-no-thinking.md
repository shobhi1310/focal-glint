# Classification No-Thinking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the Gemma thinking prefix from notification classification while preserving the existing classification prompt text, tool calls, and tool-provided reason.

**Architecture:** Keep the change local to `Classifier` by passing the existing classification instruction text directly to `generateWithTools(...)` instead of wrapping it with `ThinkingMode.withThinkPrefix(...)`. Protect the behavior with a focused `ClassifierTest` assertion that the system instruction no longer contains the thinking token while tool-based classification still succeeds.

**Tech Stack:** Kotlin, JUnit4, MockK, Gradle Android unit tests

---

### Task 1: Remove thinking prefix from notification classification

**Files:**
- Modify: `app/src/main/java/com/focal/intelligence/Classifier.kt`
- Modify: `app/src/test/java/com/focal/intelligence/ClassifierTest.kt`
- Test: `app/src/test/java/com/focal/intelligence/ClassifierTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `classify uses raw system prompt without thinking prefix`() = runTest {
    coEvery { inferenceProvider.isReady() } returns true
    var capturedSystemInstruction: String? = null
    coEvery { inferenceProvider.generateWithTools(any(), any(), any()) } answers {
        capturedSystemInstruction = firstArg()
        val tools = thirdArg<List<ToolSet>>()
        tools.filterIsInstance<ClassifyNotificationTool>().first()
            .classifyNotification("matters", "OTP from bank")
        emptyFlow()
    }

    val result = classifier.classify(notification(content = "Your OTP is 123456"))

    assertEquals(ClassificationResult.MATTERS, result.category)
    assertEquals("llm", result.classifiedBy)
    assertEquals("OTP from bank", result.reason)
    assertNotNull(capturedSystemInstruction)
    assertFalse(capturedSystemInstruction!!.contains("<|think|>"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.focal.intelligence.ClassifierTest`
Expected: FAIL because the captured system instruction still contains the thinking prefix.

- [ ] **Step 3: Write minimal implementation**

```kotlin
private const val CLASSIFICATION_SYSTEM =
    "You are a notification classifier. Classify each notification as 'matters' (personally relevant to the user) or 'noise' (generic, promotional, or irrelevant). Call classifyNotification exactly once. No prose."
```

Keep the rest of the classifier flow unchanged:

```kotlin
inferenceProvider.generateWithTools(CLASSIFICATION_SYSTEM, prompt, listOf(tool))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.focal.intelligence.ClassifierTest`
Expected: PASS with all `ClassifierTest` tests green.

- [ ] **Step 5: Run broader verification**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS for debug unit tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/Classifier.kt app/src/test/java/com/focal/intelligence/ClassifierTest.kt docs/superpowers/specs/2026-04-19-classification-no-thinking-design.md docs/superpowers/plans/2026-04-20-classification-no-thinking.md
git commit -m "fix: remove thinking prefix from notification classification"
```
