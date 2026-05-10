package com.focal.worker

import android.util.Log
import java.util.concurrent.PriorityBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

enum class WorkPriority(val level: Int) {
    HIGH(0),   // classification — user-facing, time-sensitive
    LOW(1)     // narrative generation — background polish
}

data class InferenceWorkItem(
    val id: String,
    val priority: WorkPriority,
    val type: WorkType,
    val createdAt: Long = System.currentTimeMillis()
) : Comparable<InferenceWorkItem> {
    override fun compareTo(other: InferenceWorkItem): Int {
        val p = priority.level.compareTo(other.priority.level)
        if (p != 0) return p
        return createdAt.compareTo(other.createdAt) // FIFO within same priority
    }
}

enum class WorkType {
    CLASSIFY_PENDING,      // classify all pending notifications
    GENERATE_NARRATIVES    // generate narratives for dirty topics
}

@Singleton
class InferenceWorkQueue @Inject constructor() {
    private val queue = PriorityBlockingQueue<InferenceWorkItem>()

    // Track what's already enqueued to avoid duplicates
    private val enqueuedTypes = mutableSetOf<WorkType>()

    @Synchronized
    fun enqueue(type: WorkType, priority: WorkPriority): Boolean {
        if (type in enqueuedTypes) {
            Log.d(TAG, "Already enqueued: $type, skipping")
            return false
        }
        val item = InferenceWorkItem(
            id = "${type.name}-${System.currentTimeMillis()}",
            priority = priority,
            type = type
        )
        enqueuedTypes.add(type)
        queue.add(item)
        Log.d(TAG, "Enqueued: ${item.type} priority=${item.priority} queueSize=${queue.size}")
        return true
    }

    @Synchronized
    fun poll(): InferenceWorkItem? {
        val item = queue.poll()
        if (item != null) {
            enqueuedTypes.remove(item.type)
        }
        return item
    }

    @Synchronized
    fun peek(): InferenceWorkItem? = queue.peek()

    @Synchronized
    fun hasHighPriority(): Boolean = queue.any { it.priority == WorkPriority.HIGH }

    @Synchronized
    fun isEmpty(): Boolean = queue.isEmpty()

    @Synchronized
    fun clear() {
        queue.clear()
        enqueuedTypes.clear()
    }

    @Synchronized
    fun size(): Int = queue.size

    companion object {
        private const val TAG = "InferenceWorkQueue"
    }
}
