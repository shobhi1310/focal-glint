package com.focal.intelligence

object TopicClusteringPolicy {
    const val CONFIG_VERSION = 1
    const val ASSIGN_THRESHOLD = 0.93f

    fun needsFullRebuild(storedVersion: Int): Boolean {
        return storedVersion < CONFIG_VERSION
    }
}
