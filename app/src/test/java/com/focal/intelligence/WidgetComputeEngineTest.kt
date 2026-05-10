package com.focal.intelligence

import com.focal.data.db.entity.TransactionEntity
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity
import com.focal.data.repository.TransactionRepository
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
    private val transactionRepo = mockk<TransactionRepository>(relaxed = true)
    private val engine = WidgetComputeEngine(repo, transactionRepo)

    private fun financeConfig(operation: String = "SUM") = WidgetConfigEntity(
        id = "w1", category = "finance", title = "Money today",
        operation = operation, extractionTool = "extract_finance",
        field = "amount", groupBy = "merchant", headlineTemplate = "₹{result}"
    )

    private fun transaction(
        amount: Double,
        merchant: String,
        direction: String = "debit"
    ) = TransactionEntity(
        notificationId = "n1-$amount-$merchant",
        amount = amount,
        direction = direction,
        account = "XX123",
        bank = "Test Bank",
        rawMerchant = merchant,
        postedAt = System.currentTimeMillis()
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
        coEvery { transactionRepo.getAll() } returns listOf(
            transaction(425.0, "Swiggy"),
            transaction(3200.0, "ICICI"),
            transaction(1080.0, "Uber")
        )

        val statesSlot = slot<List<WidgetStateEntity>>()
        coEvery { repo.saveWidgetStates(capture(statesSlot)) } returns Unit

        engine.computeAll()

        val states = statesSlot.captured
        assertEquals(1, states.size)
        assertTrue(states[0].headline.contains("4705"))
        assertEquals(3, states[0].itemCount)
    }

    @Test
    fun `finance computes received minus sent`() = runTest {
        val config = financeConfig("SUM")
        coEvery { repo.getAllConfigs() } returns listOf(config)
        coEvery { transactionRepo.getAll() } returns listOf(
            transaction(100.0, "A", direction = "debit"),
            transaction(250.0, "B", direction = "credit")
        )

        val statesSlot = slot<List<WidgetStateEntity>>()
        coEvery { repo.saveWidgetStates(capture(statesSlot)) } returns Unit

        engine.computeAll()

        val states = statesSlot.captured
        assertEquals("+₹150", states[0].headline)
        assertEquals(2, states[0].itemCount)
    }

    @Test
    fun `empty data produces zero headline`() = runTest {
        coEvery { repo.getAllConfigs() } returns listOf(financeConfig())
        coEvery { transactionRepo.getAll() } returns emptyList()

        val statesSlot = slot<List<WidgetStateEntity>>()
        coEvery { repo.saveWidgetStates(capture(statesSlot)) } returns Unit

        engine.computeAll()

        val states = statesSlot.captured
        assertEquals("₹0", states[0].headline)
        assertEquals(0, states[0].itemCount)
    }
}
