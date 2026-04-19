package com.focal.ui.settings

import android.content.Context
import com.focal.data.db.entity.AppProfileEntity
import com.focal.data.db.entity.RuleEntity
import com.focal.data.repository.NotificationRepository
import com.focal.data.repository.RuleRepository
import com.focal.intelligence.InferenceProvider
import com.focal.intelligence.ModelManager
import androidx.work.WorkManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
    private lateinit var inferenceProvider: InferenceProvider
    private lateinit var modelManager: ModelManager
    private lateinit var context: Context

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        ruleRepository = mockk(relaxed = true)
        notificationRepository = mockk(relaxed = true)
        inferenceProvider = mockk(relaxed = true)
        modelManager = mockk(relaxed = true)
        context = mockk(relaxed = true)

        mockkStatic(WorkManager::class)
        val workManager = mockk<WorkManager>(relaxed = true)
        every { WorkManager.getInstance(any()) } returns workManager
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(WorkManager::class)
    }

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(ruleRepository, notificationRepository, inferenceProvider, modelManager, context)
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
