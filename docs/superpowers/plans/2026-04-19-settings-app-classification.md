# Settings Page — App Classification Overrides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Settings page where users can override per-app classification (matters/noise/auto) with debounced save and automatic reclassification.

**Architecture:** SettingsViewModel reads app profiles + rules, exposes a list of AppOverride objects with 3-state toggles. Changes are debounced (3s), then persisted as `user_explicit` rules and a ClassificationWorker is enqueued. The existing RulesEngine already prioritizes `user_explicit` over `system_default`.

**Tech Stack:** Jetpack Compose (Material 3 SegmentedButton), Room, Hilt, WorkManager, Kotlin coroutines

---

## Task 1: DAO + Repository Layer

**Files to modify:**
- `app/src/main/java/com/focal/data/db/dao/RuleDao.kt`
- `app/src/main/java/com/focal/data/repository/RuleRepository.kt`

- [ ] **Step 1: Add user rule management queries to RuleDao**

Add these queries to `RuleDao.kt`:
```kotlin
    @Query("SELECT * FROM rules WHERE source = 'user_explicit' AND type = 'app_match' AND app = :packageName LIMIT 1")
    suspend fun getUserRuleForApp(packageName: String): RuleEntity?

    @Query("DELETE FROM rules WHERE source = 'user_explicit' AND type = 'app_match' AND app = :packageName")
    suspend fun deleteUserRuleForApp(packageName: String)
```

- [ ] **Step 2: Add user override methods to RuleRepository**

Add these methods to `RuleRepository.kt`:
```kotlin
    suspend fun getUserOverrides(): List<RuleEntity> {
        return ruleDao.getUserRules()
    }

    suspend fun getSystemDefaults(): List<RuleEntity> {
        return ruleDao.getSystemRules()
    }

    suspend fun setUserOverride(packageName: String, category: String) {
        ruleDao.deleteUserRuleForApp(packageName)
        ruleDao.insert(
            RuleEntity(
                type = "app_match",
                app = packageName,
                category = category,
                confidence = 1.0f,
                source = "user_explicit"
            )
        )
    }

    suspend fun clearUserOverride(packageName: String) {
        ruleDao.deleteUserRuleForApp(packageName)
    }
```

- [ ] **Step 3: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add user rule override queries to DAO and repository"
```

---

## Task 2: SettingsViewModel

**Files to create:**
- `app/src/main/java/com/focal/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Create SettingsViewModel with data model and state**

Create `app/src/main/java/com/focal/ui/settings/SettingsViewModel.kt`:

```kotlin
package com.focal.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.focal.data.db.entity.AppProfileEntity
import com.focal.data.db.entity.RuleEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.RuleRepository
import com.focal.worker.ClassificationWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppOverride(
    val packageName: String,
    val appName: String,
    val notificationCount: Int,
    val systemDefault: String?,
    val userOverride: String?
)

data class SettingsUiState(
    val apps: List<AppOverride> = emptyList(),
    val snackbarMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val ruleRepository: RuleRepository,
    private val notificationRepository: NotificationRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    private var debounceJob: Job? = null
    private val pendingChanges = mutableMapOf<String, String?>()

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            val profiles = notificationRepository.getAppProfiles()
            val userRules = ruleRepository.getUserOverrides()
            val systemRules = ruleRepository.getSystemDefaults()

            val userRuleMap = userRules
                .filter { it.type == "app_match" && it.app != null }
                .associateBy { it.app!! }
            val systemRuleMap = systemRules
                .filter { it.type == "app_match" && it.app != null }
                .associateBy { it.app!! }

            val apps = profiles
                .sortedByDescending { it.notificationCount }
                .map { profile ->
                    AppOverride(
                        packageName = profile.packageName,
                        appName = profile.appName,
                        notificationCount = profile.notificationCount,
                        systemDefault = systemRuleMap[profile.packageName]?.category,
                        userOverride = userRuleMap[profile.packageName]?.category
                    )
                }

            _uiState.value = _uiState.value.copy(apps = apps)
        }
    }

    fun onToggle(packageName: String, newState: String?) {
        // Update UI immediately (optimistic)
        _uiState.value = _uiState.value.copy(
            apps = _uiState.value.apps.map { app ->
                if (app.packageName == packageName) app.copy(userOverride = newState)
                else app
            }
        )

        pendingChanges[packageName] = newState

        // Reset debounce timer
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(3000)
            persistChanges()
        }
    }

    fun dismissSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }

    private suspend fun persistChanges() {
        val changes = pendingChanges.toMap()
        pendingChanges.clear()

        for ((packageName, category) in changes) {
            if (category != null) {
                ruleRepository.setUserOverride(packageName, category)
            } else {
                ruleRepository.clearUserOverride(packageName)
            }
        }

        // Enqueue reclassification
        val workRequest = OneTimeWorkRequestBuilder<ClassificationWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ClassificationWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        _uiState.value = _uiState.value.copy(snackbarMessage = "Changes saved · refreshing digest...")

        // Auto-dismiss snackbar
        delay(3000)
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }
}
```

Note: `notificationRepository.getAppProfiles()` returns `Flow<List<AppProfileEntity>>`. We need a snapshot version. The existing `getAppProfiles()` returns a Flow. We need to collect it once. Change `loadApps()` to use the Flow's first emission:

Replace the `profiles` line with:
```kotlin
            val profiles = kotlinx.coroutines.flow.first(notificationRepository.getAppProfiles())
```

Actually, import `first` at the top and use:
```kotlin
import kotlinx.coroutines.flow.first
```
And in loadApps:
```kotlin
            val profiles = notificationRepository.getAppProfiles().first()
```

- [ ] **Step 2: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add SettingsViewModel with debounced app override logic"
```

---

## Task 3: SettingsScreen UI

**Files to create:**
- `app/src/main/java/com/focal/ui/settings/SettingsScreen.kt`

**Files to modify:**
- `app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt`

- [ ] **Step 1: Create SettingsScreen composable**

Create `app/src/main/java/com/focal/ui/settings/SettingsScreen.kt`:

```kotlin
package com.focal.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            item {
                Text(
                    text = "Choose how each app is classified",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }

            if (state.apps.isEmpty()) {
                item {
                    Text(
                        text = "No apps seen yet. Notifications will appear here as they arrive.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 32.dp)
                    )
                }
            } else {
                items(state.apps, key = { it.packageName }) { app ->
                    AppOverrideRow(
                        app = app,
                        onToggle = { newState -> viewModel.onToggle(app.packageName, newState) }
                    )
                }
            }
        }

        // Snackbar at bottom
        state.snackbarMessage?.let { message ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(text = message)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppOverrideRow(
    app: AppOverride,
    onToggle: (String?) -> Unit
) {
    val options = listOf("Matters", "Auto", "Noise")
    val selectedIndex = when (app.userOverride) {
        "matters" -> 0
        null -> 1
        "noise" -> 2
        else -> 1
    }

    val systemHint = when (app.systemDefault) {
        "matters" -> "system: matters"
        "noise" -> "system: noise"
        else -> "system: llm"
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "${app.notificationCount} notifications · $systemHint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = index == selectedIndex,
                        onClick = {
                            val newState = when (index) {
                                0 -> "matters"
                                2 -> "noise"
                                else -> null
                            }
                            onToggle(newState)
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size
                        )
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Wire SettingsScreen into navigation**

In `app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt`, replace:
```kotlin
            composable(Screen.Settings.route) {
                Text("Settings \u2014 coming soon")
            }
```

With:
```kotlin
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
```

Add import at top:
```kotlin
import com.focal.ui.settings.SettingsScreen
```

Remove the unused `Text` import if it was only used for the placeholder (check — it's likely still used by nav bar labels, so probably keep it).

- [ ] **Step 3: Verify build**

```bash
./gradlew assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add Settings screen with per-app classification overrides"
```

---

## Task 4: Tests

**Files to create:**
- `app/src/test/java/com/focal/ui/settings/SettingsViewModelTest.kt`

- [ ] **Step 1: Write SettingsViewModel tests**

Create `app/src/test/java/com/focal/ui/settings/SettingsViewModelTest.kt`:

```kotlin
package com.focal.ui.settings

import android.content.Context
import com.focal.data.db.entity.AppProfileEntity
import com.focal.data.db.entity.RuleEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.RuleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var ruleRepository: RuleRepository
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var context: Context

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        ruleRepository = mockk(relaxed = true)
        notificationRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(ruleRepository, notificationRepository, context)
    }

    private fun setupProfiles(vararg apps: Pair<String, Int>) {
        val profiles = apps.map { (name, count) ->
            AppProfileEntity(
                packageName = "com.$name",
                appName = name.replaceFirstChar { it.uppercase() },
                notificationCount = count
            )
        }
        every { notificationRepository.getAppProfiles() } returns flowOf(profiles)
    }

    @Test
    fun `loads apps sorted by notification count`() = runTest {
        setupProfiles("whatsapp" to 20, "youtube" to 5, "gmail" to 12)
        coEvery { ruleRepository.getUserOverrides() } returns emptyList()
        coEvery { ruleRepository.getSystemDefaults() } returns emptyList()

        val vm = createViewModel()
        advanceUntilIdle()

        val apps = vm.uiState.value.apps
        assertEquals(3, apps.size)
        assertEquals("com.whatsapp", apps[0].packageName)
        assertEquals("com.gmail", apps[1].packageName)
        assertEquals("com.youtube", apps[2].packageName)
    }

    @Test
    fun `shows user override when set`() = runTest {
        setupProfiles("whatsapp" to 10)
        coEvery { ruleRepository.getUserOverrides() } returns listOf(
            RuleEntity(type = "app_match", app = "com.whatsapp", category = "noise", source = "user_explicit")
        )
        coEvery { ruleRepository.getSystemDefaults() } returns emptyList()

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("noise", vm.uiState.value.apps[0].userOverride)
    }

    @Test
    fun `shows system default hint`() = runTest {
        setupProfiles("whatsapp" to 10)
        coEvery { ruleRepository.getUserOverrides() } returns emptyList()
        coEvery { ruleRepository.getSystemDefaults() } returns listOf(
            RuleEntity(type = "app_match", app = "com.whatsapp", category = "matters", source = "system_default")
        )

        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals("matters", vm.uiState.value.apps[0].systemDefault)
        assertNull(vm.uiState.value.apps[0].userOverride)
    }

    @Test
    fun `onToggle updates UI optimistically`() = runTest {
        setupProfiles("whatsapp" to 10)
        coEvery { ruleRepository.getUserOverrides() } returns emptyList()
        coEvery { ruleRepository.getSystemDefaults() } returns emptyList()

        val vm = createViewModel()
        advanceUntilIdle()

        vm.onToggle("com.whatsapp", "noise")

        assertEquals("noise", vm.uiState.value.apps[0].userOverride)
    }

    @Test
    fun `debounce persists after delay`() = runTest {
        setupProfiles("whatsapp" to 10)
        coEvery { ruleRepository.getUserOverrides() } returns emptyList()
        coEvery { ruleRepository.getSystemDefaults() } returns emptyList()

        val vm = createViewModel()
        advanceUntilIdle()

        vm.onToggle("com.whatsapp", "noise")
        advanceUntilIdle()

        coVerify { ruleRepository.setUserOverride("com.whatsapp", "noise") }
    }

    @Test
    fun `toggle to auto clears user override`() = runTest {
        setupProfiles("whatsapp" to 10)
        coEvery { ruleRepository.getUserOverrides() } returns listOf(
            RuleEntity(type = "app_match", app = "com.whatsapp", category = "noise", source = "user_explicit")
        )
        coEvery { ruleRepository.getSystemDefaults() } returns emptyList()

        val vm = createViewModel()
        advanceUntilIdle()

        vm.onToggle("com.whatsapp", null)
        advanceUntilIdle()

        coVerify { ruleRepository.clearUserOverride("com.whatsapp") }
    }
}
```

- [ ] **Step 2: Run tests**

```bash
./gradlew testDebugUnitTest
```

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: add SettingsViewModel unit tests"
```

---

## Task 5: On-Device Verification

- [ ] **Step 1: Install and launch**

```bash
./gradlew installDebug
```

- [ ] **Step 2: Navigate to Settings tab — verify app list shows sorted by notification count**
- [ ] **Step 3: Toggle an app (e.g., Google News) to Noise — verify segment updates immediately**
- [ ] **Step 4: Wait 3 seconds — verify snackbar "Changes saved · refreshing digest..."**
- [ ] **Step 5: Navigate to Digest — verify the app's notifications moved to noise**
- [ ] **Step 6: Go back to Settings, toggle same app to Auto — verify it resets**
- [ ] **Step 7: Commit and push**

```bash
git add -A
git commit -m "feat: Settings page with per-app classification overrides"
git push
```

---

## Summary

3 implementation tasks + 1 test task + 1 on-device verification:
1. **DAO + Repository** — add user rule queries and management methods
2. **SettingsViewModel** — debounced toggle logic, combines profiles + rules
3. **SettingsScreen UI** — LazyColumn with segmented buttons, snackbar feedback
4. **Tests** — ViewModel unit tests covering load, toggle, debounce, auto-clear
5. **On-device test** — verify full flow on Redmi Note 10 Pro
