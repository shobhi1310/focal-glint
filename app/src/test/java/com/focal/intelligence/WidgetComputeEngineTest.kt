package com.focal.intelligence

import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity
import com.focal.data.repository.WidgetRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WidgetComputeEngineTest {

    private val repo = mockk<WidgetRepository>(relaxed = true)
    private val engine = WidgetComputeEngine(repo)

    private fun financeConfig(operation: String = "SUM") = WidgetConfigEntity(
        id = "w1", category = "finance", title = "Money today",
        operation = operation, extractionTool = "extract_finance",
        field = "amount", groupBy = "merchant", headlineTemplate = "₹{result}"
    )

    private fun financeData(amount: Double, merchant: String) = ExtractedDataEntity(
        notificationId = "n1", category = "finance",
        data = """{"amount":$amount,"merchant":"$merchant","category":"food","direction":"debit"}""",
        appPackage = "com.hdfc.bank"
    )

    @Test
    fun `computeAll with no configs does nothing`() = runTest {
        coEvery { repo.getAllConfigs() } returns emptyList()
        engine.computeAll()
        coVerify(exactly = 0) { repo.saveWidgetStates(any()) }
    }

    @Test
    fun `SUM computes total amount`() = runTest {
        val config = financeConfig("SUM")
        coEvery { repo.getAllConfigs() } returns listOf(config)
        coEvery { repo.getExtractedData("finance") } returns listOf(
            financeData(425.0, "Swiggy"),
            financeData(3200.0, "ICICI"),
            financeData(1080.0, "Uber")
        )

        val statesSlot = slot<List<WidgetStateEntity>>()
        coEvery { repo.saveWidgetStates(capture(statesSlot)) } returns Unit

        engine.computeAll()

        val states = statesSlot.captured
        assertEquals(1, states.size)
        assertTrue(states[0].headline.contains("4705"))
    }

    @Test
    fun `COUNT returns item count`() = runTest {
        val config = financeConfig("COUNT")
        coEvery { repo.getAllConfigs() } returns listOf(config)
        coEvery { repo.getExtractedData("finance") } returns listOf(
            financeData(100.0, "A"),
            financeData(200.0, "B")
        )

        val statesSlot = slot<List<WidgetStateEntity>>()
        coEvery { repo.saveWidgetStates(capture(statesSlot)) } returns Unit

        engine.computeAll()

        val states = statesSlot.captured
        assertEquals("2", states[0].headline)
    }

    @Test
    fun `empty data produces zero headline`() = runTest {
        coEvery { repo.getAllConfigs() } returns listOf(financeConfig())
        coEvery { repo.getExtractedData("finance") } returns emptyList()

        val statesSlot = slot<List<WidgetStateEntity>>()
        coEvery { repo.saveWidgetStates(capture(statesSlot)) } returns Unit

        engine.computeAll()

        val states = statesSlot.captured
        assertEquals("₹0", states[0].headline)
        assertEquals(0, states[0].itemCount)
    }
}
