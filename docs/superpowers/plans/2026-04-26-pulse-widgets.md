# Pulse Widgets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Pulse" tab with live, user-configurable widgets that extract structured data from notifications via LLM tool calling and compute metrics (Sum/Count/Latest/List/Max/Status) in pure code.

**Architecture:** Extraction tools piggyback on the existing classification conversation (zero extra LLM calls). Four starter templates (Finance, Work, Personal, Logistics). Widget data persists independently of the 2AM daily reset. UI uses compact grid cards with tap-to-expand detail screens.

**Tech Stack:** Room (migration v7→v8), LiteRT-LM tool calling (@Tool/@ToolParam), Hilt DI, Jetpack Compose (Material 3, LazyVerticalGrid, ModalBottomSheet), WorkManager, kotlinx-serialization-json.

---

### Task 1: Room Entities — WidgetConfigEntity, ExtractedDataEntity, WidgetStateEntity

**Files:**
- Create: `app/src/main/java/com/focal/data/db/entity/WidgetConfigEntity.kt`
- Create: `app/src/main/java/com/focal/data/db/entity/ExtractedDataEntity.kt`
- Create: `app/src/main/java/com/focal/data/db/entity/WidgetStateEntity.kt`

- [ ] **Step 1: Create WidgetConfigEntity**

```kotlin
package com.focal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "widget_configs")
data class WidgetConfigEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    val category: String,

    val title: String,

    val operation: String,

    @ColumnInfo(name = "extraction_tool")
    val extractionTool: String,

    val field: String? = null,

    @ColumnInfo(name = "group_by")
    val groupBy: String? = null,

    @ColumnInfo(name = "filter_apps")
    val filterApps: String? = null,

    @ColumnInfo(name = "headline_template")
    val headlineTemplate: String,

    val source: String = "TEMPLATE",

    val position: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 2: Create ExtractedDataEntity**

```kotlin
package com.focal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "extracted_data",
    indices = [
        Index(value = ["notification_id"]),
        Index(value = ["category"])
    ]
)
data class ExtractedDataEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "notification_id")
    val notificationId: String,

    val category: String,

    val data: String,

    @ColumnInfo(name = "app_package")
    val appPackage: String,

    @ColumnInfo(name = "extracted_at")
    val extractedAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 3: Create WidgetStateEntity**

```kotlin
package com.focal.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "widget_state")
data class WidgetStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "widget_id")
    val widgetId: String,

    val headline: String = "",

    val subtitle: String? = null,

    val badge: String? = null,

    @ColumnInfo(name = "detail_json")
    val detailJson: String? = null,

    @ColumnInfo(name = "source_app_icons")
    val sourceAppIcons: String? = null,

    @ColumnInfo(name = "item_count")
    val itemCount: Int = 0,

    @ColumnInfo(name = "last_updated_at")
    val lastUpdatedAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/focal/data/db/entity/WidgetConfigEntity.kt app/src/main/java/com/focal/data/db/entity/ExtractedDataEntity.kt app/src/main/java/com/focal/data/db/entity/WidgetStateEntity.kt
git commit -m "feat(pulse): add Room entities for widget configs, extracted data, and widget state"
```

---

### Task 2: Room DAOs — WidgetConfigDao, ExtractedDataDao, WidgetStateDao

**Files:**
- Create: `app/src/main/java/com/focal/data/db/dao/WidgetConfigDao.kt`
- Create: `app/src/main/java/com/focal/data/db/dao/ExtractedDataDao.kt`
- Create: `app/src/main/java/com/focal/data/db/dao/WidgetStateDao.kt`

- [ ] **Step 1: Create WidgetConfigDao**

```kotlin
package com.focal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.focal.data.db.entity.WidgetConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetConfigDao {
    @Query("SELECT * FROM widget_configs ORDER BY position ASC")
    fun observeAll(): Flow<List<WidgetConfigEntity>>

    @Query("SELECT * FROM widget_configs ORDER BY position ASC")
    suspend fun getAll(): List<WidgetConfigEntity>

    @Query("SELECT DISTINCT category FROM widget_configs")
    suspend fun getActiveCategories(): List<String>

    @Query("SELECT * FROM widget_configs WHERE id = :id")
    suspend fun getById(id: String): WidgetConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: WidgetConfigEntity)

    @Update
    suspend fun update(config: WidgetConfigEntity)

    @Query("DELETE FROM widget_configs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM widget_configs")
    suspend fun count(): Int
}
```

- [ ] **Step 2: Create ExtractedDataDao**

```kotlin
package com.focal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.focal.data.db.entity.ExtractedDataEntity

@Dao
interface ExtractedDataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(data: ExtractedDataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(data: List<ExtractedDataEntity>)

    @Query("SELECT * FROM extracted_data WHERE category = :category")
    suspend fun getByCategory(category: String): List<ExtractedDataEntity>

    @Query("SELECT * FROM extracted_data WHERE category = :category AND app_package IN (:packages)")
    suspend fun getByCategoryAndApps(category: String, packages: List<String>): List<ExtractedDataEntity>

    @Query("SELECT * FROM extracted_data WHERE notification_id = :notificationId")
    suspend fun getByNotificationId(notificationId: String): List<ExtractedDataEntity>

    @Query("DELETE FROM extracted_data WHERE category = :category")
    suspend fun deleteByCategory(category: String)

    @Query("DELETE FROM extracted_data WHERE category = :category AND notification_id IN (SELECT id FROM notifications WHERE package_name IN (:packages))")
    suspend fun deleteByCategoryAndApps(category: String, packages: List<String>)

    @Query("DELETE FROM extracted_data")
    suspend fun deleteAll()
}
```

- [ ] **Step 3: Create WidgetStateDao**

```kotlin
package com.focal.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.focal.data.db.entity.WidgetStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetStateDao {
    @Query("SELECT * FROM widget_state")
    fun observeAll(): Flow<List<WidgetStateEntity>>

    @Query("SELECT * FROM widget_state WHERE widget_id = :widgetId")
    fun observeById(widgetId: String): Flow<WidgetStateEntity?>

    @Query("SELECT * FROM widget_state WHERE widget_id = :widgetId")
    suspend fun getById(widgetId: String): WidgetStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: WidgetStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<WidgetStateEntity>)

    @Query("DELETE FROM widget_state WHERE widget_id = :widgetId")
    suspend fun deleteById(widgetId: String)

    @Query("DELETE FROM widget_state")
    suspend fun deleteAll()
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/focal/data/db/dao/WidgetConfigDao.kt app/src/main/java/com/focal/data/db/dao/ExtractedDataDao.kt app/src/main/java/com/focal/data/db/dao/WidgetStateDao.kt
git commit -m "feat(pulse): add Room DAOs for widget configs, extracted data, and widget state"
```

---

### Task 3: Room Migration v7→v8 and Database Registration

**Files:**
- Modify: `app/src/main/java/com/focal/data/db/FocalDatabase.kt`
- Modify: `app/src/main/java/com/focal/data/db/entity/NotificationEntity.kt`
- Modify: `app/src/main/java/com/focal/di/DatabaseModule.kt`

- [ ] **Step 1: Add `extractedCategories` column to NotificationEntity**

In `NotificationEntity.kt`, add after the `contentHash` field (line 79):

```kotlin
    @ColumnInfo(name = "extracted_categories")
    val extractedCategories: String? = null
```

- [ ] **Step 2: Add MIGRATION_7_8 to FocalDatabase**

In `FocalDatabase.kt`, update the `@Database` annotation to include the 3 new entity classes and bump version to 8:

```kotlin
@Database(
    entities = [
        NotificationEntity::class,
        RuleEntity::class,
        CorrectionEntity::class,
        AppProfileEntity::class,
        TopicEntity::class,
        WidgetConfigEntity::class,
        ExtractedDataEntity::class,
        WidgetStateEntity::class
    ],
    version = 8,
    exportSchema = false
)
```

Add abstract DAO accessors after `topicDao()`:

```kotlin
    abstract fun widgetConfigDao(): WidgetConfigDao
    abstract fun extractedDataDao(): ExtractedDataDao
    abstract fun widgetStateDao(): WidgetStateDao
```

Add migration in companion object after `MIGRATION_6_7`:

```kotlin
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN extracted_categories TEXT")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS widget_configs (
                        id TEXT PRIMARY KEY NOT NULL,
                        category TEXT NOT NULL,
                        title TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        extraction_tool TEXT NOT NULL,
                        field TEXT,
                        group_by TEXT,
                        filter_apps TEXT,
                        headline_template TEXT NOT NULL,
                        source TEXT NOT NULL DEFAULT 'TEMPLATE',
                        position INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )"""
                )

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS extracted_data (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        notification_id TEXT NOT NULL,
                        category TEXT NOT NULL,
                        data TEXT NOT NULL,
                        app_package TEXT NOT NULL,
                        extracted_at INTEGER NOT NULL
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_extracted_data_notification_id ON extracted_data(notification_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_extracted_data_category ON extracted_data(category)")

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS widget_state (
                        widget_id TEXT PRIMARY KEY NOT NULL,
                        headline TEXT NOT NULL DEFAULT '',
                        subtitle TEXT,
                        badge TEXT,
                        detail_json TEXT,
                        source_app_icons TEXT,
                        item_count INTEGER NOT NULL DEFAULT 0,
                        last_updated_at INTEGER NOT NULL
                    )"""
                )
            }
        }
```

Add new entity imports at the top of the file.

- [ ] **Step 3: Register migration and DAOs in DatabaseModule**

In `DatabaseModule.kt`, add `FocalDatabase.MIGRATION_7_8` to the `addMigrations()` call and add the 3 new DAO providers:

```kotlin
    @Provides
    fun provideWidgetConfigDao(db: FocalDatabase): WidgetConfigDao = db.widgetConfigDao()

    @Provides
    fun provideExtractedDataDao(db: FocalDatabase): ExtractedDataDao = db.extractedDataDao()

    @Provides
    fun provideWidgetStateDao(db: FocalDatabase): WidgetStateDao = db.widgetStateDao()
```

- [ ] **Step 4: Verify build compiles**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/focal/data/db/FocalDatabase.kt app/src/main/java/com/focal/data/db/entity/NotificationEntity.kt app/src/main/java/com/focal/di/DatabaseModule.kt
git commit -m "feat(pulse): add Room migration v7→v8 with widget tables and extractedCategories column"
```

---

### Task 4: WidgetRepository

**Files:**
- Create: `app/src/main/java/com/focal/data/repository/WidgetRepository.kt`
- Modify: `app/src/main/java/com/focal/di/IntelligenceModule.kt`

- [ ] **Step 1: Create WidgetRepository**

```kotlin
package com.focal.data.repository

import com.focal.data.db.dao.ExtractedDataDao
import com.focal.data.db.dao.WidgetConfigDao
import com.focal.data.db.dao.WidgetStateDao
import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class WidgetRepository(
    private val configDao: WidgetConfigDao,
    private val extractedDataDao: ExtractedDataDao,
    private val stateDao: WidgetStateDao
) {
    fun observeConfigs(): Flow<List<WidgetConfigEntity>> = configDao.observeAll()

    fun observeStates(): Flow<List<WidgetStateEntity>> = stateDao.observeAll()

    fun observeState(widgetId: String): Flow<WidgetStateEntity?> = stateDao.observeById(widgetId)

    suspend fun getActiveCategories(): List<String> = configDao.getActiveCategories()

    suspend fun getAllConfigs(): List<WidgetConfigEntity> = configDao.getAll()

    suspend fun getConfig(id: String): WidgetConfigEntity? = configDao.getById(id)

    suspend fun createWidget(config: WidgetConfigEntity) {
        val position = configDao.count()
        configDao.insert(config.copy(position = position))
    }

    suspend fun updateWidget(config: WidgetConfigEntity) {
        configDao.update(config.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteWidget(widgetId: String) {
        stateDao.deleteById(widgetId)
        configDao.deleteById(widgetId)
    }

    suspend fun wipeWidgetData(widgetId: String) {
        val config = configDao.getById(widgetId) ?: return
        val filterApps = config.filterApps?.let {
            Json.decodeFromString<List<String>>(it)
        }
        if (filterApps != null) {
            extractedDataDao.deleteByCategoryAndApps(config.category, filterApps)
        } else {
            extractedDataDao.deleteByCategory(config.category)
        }
        stateDao.deleteById(widgetId)
    }

    suspend fun saveExtractedData(data: List<ExtractedDataEntity>) {
        extractedDataDao.insertAll(data)
    }

    suspend fun getExtractedData(category: String): List<ExtractedDataEntity> {
        return extractedDataDao.getByCategory(category)
    }

    suspend fun getExtractedData(category: String, packages: List<String>): List<ExtractedDataEntity> {
        return extractedDataDao.getByCategoryAndApps(category, packages)
    }

    suspend fun saveWidgetStates(states: List<WidgetStateEntity>) {
        stateDao.upsertAll(states)
    }
}
```

- [ ] **Step 2: Register WidgetRepository in IntelligenceModule**

In `IntelligenceModule.kt`, add:

```kotlin
    @Provides
    @Singleton
    fun provideWidgetRepository(
        configDao: WidgetConfigDao,
        extractedDataDao: ExtractedDataDao,
        stateDao: WidgetStateDao
    ): WidgetRepository {
        return WidgetRepository(configDao, extractedDataDao, stateDao)
    }
```

- [ ] **Step 3: Verify build compiles**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/focal/data/repository/WidgetRepository.kt app/src/main/java/com/focal/di/IntelligenceModule.kt
git commit -m "feat(pulse): add WidgetRepository with CRUD, wipe, and extraction data access"
```

---

### Task 5: Extraction Tool Classes

**Files:**
- Create: `app/src/main/java/com/focal/intelligence/ExtractionToolSet.kt`

- [ ] **Step 1: Create extraction data models and tool classes**

```kotlin
package com.focal.intelligence

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "ExtractionTool"

@Serializable
data class FinanceData(
    val amount: Double,
    val merchant: String,
    val category: String,
    val direction: String
)

@Serializable
data class WorkData(
    val entity: String,
    val sender: String,
    val action: String,
    val repo: String? = null
)

@Serializable
data class PersonalData(
    val sender: String,
    val channel: String,
    val count: Int,
    val snippet: String? = null
)

@Serializable
data class LogisticsData(
    val item: String,
    val merchant: String,
    val status: String,
    val etaMinutes: Int? = null
)

data class ExtractionResult(
    val notificationIndex: Int,
    val category: String,
    val dataJson: String
)

class ExtractFinanceTool : ToolSet {
    private val _results = mutableListOf<ExtractionResult>()
    val results: List<ExtractionResult> get() = _results

    @Tool("Extract financial transaction data from a notification about money, payments, or banking")
    fun extractFinance(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("Transaction amount as a number") amount: Double,
        @ToolParam("Merchant or payee name") merchant: String,
        @ToolParam("Spending category: food, transport, shopping, bills, transfer, or other") category: String,
        @ToolParam("Transaction direction: debit or credit") direction: String
    ): Map<String, Any> {
        Log.i(TAG, "finance: index=$index amount=$amount merchant=$merchant")
        _results.add(ExtractionResult(
            notificationIndex = index,
            category = "finance",
            dataJson = Json.encodeToString(FinanceData(amount, merchant, category, direction))
        ))
        return mapOf("status" to "ok")
    }
}

class ExtractWorkTool : ToolSet {
    private val _results = mutableListOf<ExtractionResult>()
    val results: List<ExtractionResult> get() = _results

    @Tool("Extract work item data from a notification about PRs, issues, code reviews, or work tasks")
    fun extractWork(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("Work entity identifier like PR #482, Issue FOCAL-31, or thread name") entity: String,
        @ToolParam("Person who sent or triggered this") sender: String,
        @ToolParam("Action type: review_requested, merged, commented, assigned, mentioned, or other") action: String,
        @ToolParam("Repository or project name if mentioned") repo: String
    ): Map<String, Any> {
        Log.i(TAG, "work: index=$index entity=$entity sender=$sender action=$action")
        _results.add(ExtractionResult(
            notificationIndex = index,
            category = "work",
            dataJson = Json.encodeToString(WorkData(entity, sender, action, repo.takeIf { it.isNotBlank() }))
        ))
        return mapOf("status" to "ok")
    }
}

class ExtractPersonalTool : ToolSet {
    private val _results = mutableListOf<ExtractionResult>()
    val results: List<ExtractionResult> get() = _results

    @Tool("Extract personal contact data from a notification about calls, messages, or personal communication")
    fun extractPersonal(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("Name of the person who reached out") sender: String,
        @ToolParam("Communication channel: call, message, email, or other") channel: String,
        @ToolParam("Number of attempts or messages, default 1") count: Int,
        @ToolParam("Brief content snippet if available") snippet: String
    ): Map<String, Any> {
        Log.i(TAG, "personal: index=$index sender=$sender channel=$channel count=$count")
        _results.add(ExtractionResult(
            notificationIndex = index,
            category = "personal",
            dataJson = Json.encodeToString(PersonalData(sender, channel, count, snippet.takeIf { it.isNotBlank() }))
        ))
        return mapOf("status" to "ok")
    }
}

class ExtractLogisticsTool : ToolSet {
    private val _results = mutableListOf<ExtractionResult>()
    val results: List<ExtractionResult> get() = _results

    @Tool("Extract delivery or logistics data from a notification about orders, shipments, or tracking")
    fun extractLogistics(
        @ToolParam("1-based index of the notification") index: Int,
        @ToolParam("Item or order description") item: String,
        @ToolParam("Merchant or delivery service name") merchant: String,
        @ToolParam("Delivery status: ordered, shipped, out_for_delivery, delivered, or cancelled") status: String,
        @ToolParam("Estimated arrival time in minutes, or -1 if unknown") etaMinutes: Int
    ): Map<String, Any> {
        Log.i(TAG, "logistics: index=$index item=$item merchant=$merchant status=$status")
        _results.add(ExtractionResult(
            notificationIndex = index,
            category = "logistics",
            dataJson = Json.encodeToString(LogisticsData(item, merchant, status, etaMinutes.takeIf { it >= 0 }))
        ))
        return mapOf("status" to "ok")
    }
}

object ExtractionToolFactory {
    fun createTools(activeCategories: List<String>): Map<String, ToolSet> {
        val tools = mutableMapOf<String, ToolSet>()
        if ("finance" in activeCategories) tools["finance"] = ExtractFinanceTool()
        if ("work" in activeCategories) tools["work"] = ExtractWorkTool()
        if ("personal" in activeCategories) tools["personal"] = ExtractPersonalTool()
        if ("logistics" in activeCategories) tools["logistics"] = ExtractLogisticsTool()
        return tools
    }

    fun collectResults(tools: Map<String, ToolSet>): List<ExtractionResult> {
        return tools.values.flatMap { tool ->
            when (tool) {
                is ExtractFinanceTool -> tool.results
                is ExtractWorkTool -> tool.results
                is ExtractPersonalTool -> tool.results
                is ExtractLogisticsTool -> tool.results
                else -> emptyList()
            }
        }
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/ExtractionToolSet.kt
git commit -m "feat(pulse): add extraction tool classes for finance, work, personal, and logistics"
```

---

### Task 6: Widget Compute Engine

**Files:**
- Create: `app/src/main/java/com/focal/intelligence/WidgetComputeEngine.kt`

- [ ] **Step 1: Create WidgetComputeEngine**

```kotlin
package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity
import com.focal.data.repository.WidgetRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString

private const val TAG = "WidgetCompute"

class WidgetComputeEngine(private val widgetRepository: WidgetRepository) {

    suspend fun computeAll() {
        val configs = widgetRepository.getAllConfigs()
        if (configs.isEmpty()) return

        val states = configs.map { config -> computeWidget(config) }
        widgetRepository.saveWidgetStates(states)
        Log.i(TAG, "Computed ${states.size} widget states")
    }

    private suspend fun computeWidget(config: WidgetConfigEntity): WidgetStateEntity {
        val filterApps = config.filterApps?.let {
            try { Json.decodeFromString<List<String>>(it) } catch (_: Exception) { null }
        }
        val data = if (filterApps != null) {
            widgetRepository.getExtractedData(config.category, filterApps)
        } else {
            widgetRepository.getExtractedData(config.category)
        }

        if (data.isEmpty()) {
            return WidgetStateEntity(
                widgetId = config.id,
                headline = emptyHeadline(config),
                itemCount = 0
            )
        }

        val sourceApps = data.map { it.appPackage }.distinct()

        return when (config.operation) {
            "SUM" -> computeSum(config, data, sourceApps)
            "COUNT" -> computeCount(config, data, sourceApps)
            "LATEST" -> computeLatest(config, data, sourceApps)
            "LIST" -> computeList(config, data, sourceApps)
            "MAX" -> computeMax(config, data, sourceApps)
            "STATUS" -> computeStatus(config, data, sourceApps)
            else -> WidgetStateEntity(widgetId = config.id, headline = "Unknown operation")
        }
    }

    private fun emptyHeadline(config: WidgetConfigEntity): String {
        return when (config.operation) {
            "SUM" -> "₹0"
            "COUNT" -> "0"
            "STATUS" -> "No updates"
            else -> "No data yet"
        }
    }

    private fun computeSum(config: WidgetConfigEntity, data: List<ExtractedDataEntity>, sourceApps: List<String>): WidgetStateEntity {
        val field = config.field ?: "amount"
        val items = data.mapNotNull { parseField(it.data, field)?.toDoubleOrNull() }
        val total = items.sum()
        val formatted = if (total == total.toLong().toDouble()) "₹${total.toLong()}" else "₹${"%.0f".format(total)}"

        val latest = data.maxByOrNull { it.extractedAt }
        val latestParsed = latest?.let { parseJson(it.data) }
        val badge = latestParsed?.let {
            val amt = parseField(it, field)
            val merchant = parseField(it, "merchant")
            if (amt != null && merchant != null) "+₹${amt} $merchant" else null
        }

        val grouped = data.groupBy { parseField(it.data, config.groupBy ?: "merchant") ?: "Other" }
        val detailLines = grouped.map { (key, items) ->
            val sum = items.mapNotNull { parseField(it.data, field)?.toDoubleOrNull() }.sum()
            mapOf("label" to key, "value" to "₹${"%.0f".format(sum)}")
        }

        return WidgetStateEntity(
            widgetId = config.id,
            headline = formatted,
            subtitle = badge?.let { "+ $it just now" },
            badge = badge,
            detailJson = Json.encodeToString(detailLines),
            sourceAppIcons = Json.encodeToString(sourceApps),
            itemCount = data.size
        )
    }

    private fun computeCount(config: WidgetConfigEntity, data: List<ExtractedDataEntity>, sourceApps: List<String>): WidgetStateEntity {
        return WidgetStateEntity(
            widgetId = config.id,
            headline = "${data.size}",
            sourceAppIcons = Json.encodeToString(sourceApps),
            itemCount = data.size
        )
    }

    private fun computeLatest(config: WidgetConfigEntity, data: List<ExtractedDataEntity>, sourceApps: List<String>): WidgetStateEntity {
        val latest = data.maxByOrNull { it.extractedAt } ?: return WidgetStateEntity(widgetId = config.id)
        val parsed = parseJson(latest.data)
        val headline = parseField(parsed, "sender") ?: parseField(parsed, "item") ?: parseField(parsed, "entity") ?: "Latest"
        val snippet = parseField(parsed, "snippet") ?: parseField(parsed, "status") ?: ""

        return WidgetStateEntity(
            widgetId = config.id,
            headline = headline,
            subtitle = snippet,
            sourceAppIcons = Json.encodeToString(sourceApps),
            itemCount = data.size
        )
    }

    private fun computeList(config: WidgetConfigEntity, data: List<ExtractedDataEntity>, sourceApps: List<String>): WidgetStateEntity {
        val groupField = config.groupBy ?: "sender"
        val grouped = data.groupBy { parseField(it.data, groupField) ?: "Unknown" }
        val topSender = grouped.maxByOrNull { it.value.size }
        val detailLines = grouped.map { (key, items) ->
            mapOf("label" to key, "value" to "${items.size}")
        }

        return WidgetStateEntity(
            widgetId = config.id,
            headline = "${topSender?.key ?: ""} · ${data.size}",
            detailJson = Json.encodeToString(detailLines),
            sourceAppIcons = Json.encodeToString(sourceApps),
            itemCount = data.size
        )
    }

    private fun computeMax(config: WidgetConfigEntity, data: List<ExtractedDataEntity>, sourceApps: List<String>): WidgetStateEntity {
        val field = config.field ?: "amount"
        val max = data.maxByOrNull { parseField(it.data, field)?.toDoubleOrNull() ?: 0.0 }
        val parsed = max?.let { parseJson(it.data) }
        val value = parsed?.let { parseField(it, field) } ?: "0"
        val merchant = parsed?.let { parseField(it, "merchant") } ?: ""

        return WidgetStateEntity(
            widgetId = config.id,
            headline = "₹$value",
            badge = "$merchant",
            sourceAppIcons = Json.encodeToString(sourceApps),
            itemCount = data.size
        )
    }

    private fun computeStatus(config: WidgetConfigEntity, data: List<ExtractedDataEntity>, sourceApps: List<String>): WidgetStateEntity {
        val byStatus = data.groupBy { parseField(it.data, "status") ?: "unknown" }
        val headline = byStatus.entries
            .sortedByDescending { it.value.size }
            .joinToString(", ") { "${it.value.size} ${it.key.replace('_', ' ')}" }

        val detailLines = data.map { row ->
            val parsed = parseJson(row.data)
            mapOf(
                "label" to (parseField(parsed, "item") ?: parseField(parsed, "merchant") ?: "Item"),
                "value" to (parseField(parsed, "status")?.replace('_', ' ') ?: "unknown")
            )
        }

        val latestRow = data.maxByOrNull { it.extractedAt }
        val latestMerchant = latestRow?.let { parseField(it.data, "merchant") }
        val latestStatus = latestRow?.let { parseField(it.data, "status")?.replace('_', ' ') }
        val badge = if (latestMerchant != null && latestStatus != null) "$latestMerchant · $latestStatus" else null

        return WidgetStateEntity(
            widgetId = config.id,
            headline = headline,
            badge = badge,
            detailJson = Json.encodeToString(detailLines),
            sourceAppIcons = Json.encodeToString(sourceApps),
            itemCount = data.size
        )
    }

    private fun parseJson(json: String): JsonObject? {
        return try { Json.parseToJsonElement(json).jsonObject } catch (_: Exception) { null }
    }

    private fun parseField(json: String, field: String): String? = parseField(parseJson(json), field)

    private fun parseField(obj: JsonObject?, field: String): String? {
        if (obj == null) return null
        val element = obj[field] ?: return null
        return try { element.jsonPrimitive.content } catch (_: Exception) { null }
    }
}
```

- [ ] **Step 2: Register in IntelligenceModule**

In `IntelligenceModule.kt`, add:

```kotlin
    @Provides
    @Singleton
    fun provideWidgetComputeEngine(widgetRepository: WidgetRepository): WidgetComputeEngine {
        return WidgetComputeEngine(widgetRepository)
    }
```

- [ ] **Step 3: Verify build compiles**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/WidgetComputeEngine.kt app/src/main/java/com/focal/di/IntelligenceModule.kt
git commit -m "feat(pulse): add WidgetComputeEngine with Sum, Count, Latest, List, Max, Status operations"
```

---

### Task 7: Modify Classifier to Include Extraction Tools

**Files:**
- Modify: `app/src/main/java/com/focal/intelligence/Classifier.kt`

- [ ] **Step 1: Add extraction support to classifyBatch**

In `Classifier.kt`, add a new method and modify `classifyBatch` to accept optional extraction tools. Add after the class declaration imports:

```kotlin
import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.repository.WidgetRepository
```

Change the `Classifier` constructor to also accept a `WidgetRepository`:

```kotlin
class Classifier(
    private val inferenceProvider: InferenceProvider,
    private val widgetRepository: WidgetRepository? = null
)
```

Add a new method `classifyAndExtractBatch` after `classifyBatch`:

```kotlin
    suspend fun classifyAndExtractBatch(
        notifications: List<NotificationEntity>,
        extractionTools: Map<String, ToolSet>
    ): List<Pair<NotificationEntity, ClassificationResult>> {
        if (notifications.isEmpty()) return emptyList()

        if (!inferenceProvider.isReady()) {
            return notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending") }
        }

        val prompt = PromptBuilder.buildBatchClassificationPrompt(notifications)
        val allToolCategories = extractionTools.keys.joinToString(", ")
        Log.i(TAG, "classifyAndExtract: count=${notifications.size} extractions=[$allToolCategories] promptLen=${prompt.length}")

        val classifyTool = BatchClassifyNotificationTool()
        val allTools = mutableListOf<ToolSet>(classifyTool)
        allTools.addAll(extractionTools.values)

        val systemPrompt = BATCH_CLASSIFICATION_SYSTEM +
            "\n\nAfter classifying each notification, if it is 'matters', also call the appropriate extraction tool(s) for it. " +
            "A notification can match multiple extraction tools (e.g., a food delivery payment is both finance and logistics). " +
            "Available extraction categories: $allToolCategories."

        return try {
            var messageCount = 0
            inferenceProvider.generateWithTools(systemPrompt, prompt, allTools)
                .catch { e -> Log.e(TAG, "extract batch error: ${e.message}", e); throw e }
                .collect { message ->
                    message.toolCalls?.forEachIndexed { i, call ->
                        Log.i(TAG, "extract toolCall[$i]: name=${call.name}")
                    }
                    messageCount++
                }

            val extractionResults = ExtractionToolFactory.collectResults(extractionTools)
            Log.i(TAG, "extract done: messages=$messageCount classified=${classifyTool.resultCount()}/${notifications.size} extracted=${extractionResults.size}")

            if (extractionResults.isNotEmpty() && widgetRepository != null) {
                val entities = extractionResults.mapNotNull { result ->
                    val notif = notifications.getOrNull(result.notificationIndex - 1) ?: return@mapNotNull null
                    ExtractedDataEntity(
                        notificationId = notif.id,
                        category = result.category,
                        data = result.dataJson,
                        appPackage = notif.packageName
                    )
                }
                widgetRepository.saveExtractedData(entities)
                Log.i(TAG, "Saved ${entities.size} extracted data rows")
            }

            notifications.mapIndexed { i, notification ->
                val (category, reason) = classifyTool.getResult(i + 1)
                    ?: return@mapIndexed notification to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending")
                val resolved = when (category.lowercase().trim()) {
                    "matters" -> ClassificationResult.MATTERS
                    "noise" -> ClassificationResult.NOISE
                    else -> return@mapIndexed notification to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending")
                }
                notification to ClassificationResult(category = resolved, classifiedBy = "llm", reason = reason)
            }
        } catch (e: Exception) {
            Log.e(TAG, "classifyAndExtract failed", e)
            notifications.map { it to ClassificationResult(ClassificationResult.UNCATEGORIZED, "pending") }
        }
    }
```

- [ ] **Step 2: Update Classifier provider in IntelligenceModule**

In `IntelligenceModule.kt`, update the `provideClassifier` method:

```kotlin
    @Provides
    @Singleton
    fun provideClassifier(inferenceProvider: InferenceProvider, widgetRepository: WidgetRepository): Classifier {
        return Classifier(inferenceProvider, widgetRepository)
    }
```

- [ ] **Step 3: Verify build compiles**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/focal/intelligence/Classifier.kt app/src/main/java/com/focal/di/IntelligenceModule.kt
git commit -m "feat(pulse): add classifyAndExtractBatch to Classifier for merged classify+extract conversation"
```

---

### Task 8: Modify ClassificationWorker and DailyResetWorker

**Files:**
- Modify: `app/src/main/java/com/focal/worker/ClassificationWorker.kt`
- Modify: `app/src/main/java/com/focal/worker/DailyResetWorker.kt`

- [ ] **Step 1: Add extraction + widget compute to ClassificationWorker**

In `ClassificationWorker.kt`, add new dependencies to the constructor:

```kotlin
    private val widgetRepository: WidgetRepository,
    private val widgetComputeEngine: WidgetComputeEngine,
```

Add imports:

```kotlin
import com.focal.data.repository.WidgetRepository
import com.focal.intelligence.WidgetComputeEngine
import com.focal.intelligence.ExtractionToolFactory
```

Replace the batch classification loop (the `for (batch in pending.chunked(10))` block, approximately lines 92-110) with:

```kotlin
                val activeCategories = widgetRepository.getActiveCategories()
                val extractionTools = if (activeCategories.isNotEmpty()) {
                    ExtractionToolFactory.createTools(activeCategories)
                } else {
                    emptyMap()
                }

                Log.d("ClassificationWorker", "Classifying ${pending.size} pending notifications in batches of 10, extraction categories: $activeCategories")
                var classified = 0
                for (batch in pending.chunked(10)) {
                    try {
                        val results = if (extractionTools.isNotEmpty()) {
                            classifier.classifyAndExtractBatch(batch, extractionTools)
                        } else {
                            classifier.classifyBatch(batch)
                        }
                        for ((notification, result) in results) {
                            if (result.classifiedBy != "pending") {
                                notificationRepository.markClassified(
                                    notification = notification,
                                    category = result.category,
                                    classifiedBy = result.classifiedBy
                                )
                                classified++
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("ClassificationWorker", "Batch classification failed", e)
                    }
                }
                Log.d("ClassificationWorker", "Classified $classified/${pending.size}")
```

After the topic generation block (after `Log.d("ClassificationWorker", "Topic generation complete")`), add widget compute:

```kotlin
        try {
            widgetComputeEngine.computeAll()
            Log.d("ClassificationWorker", "Widget compute complete")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ClassificationWorker", "Widget compute failed", e)
        }
```

- [ ] **Step 2: Verify DailyResetWorker does NOT touch widget tables**

Read `DailyResetWorker.kt` — it calls `notificationRepository.purgeOlderThan()`, `topicRepository.clearAndSaveTopics()`, and `notificationRepository.resetAllProcessedFlags()`. None of these touch `widget_configs`, `extracted_data`, or `widget_state` tables. No change needed — the worker already correctly skips widget data. Add a log line for clarity:

In `DailyResetWorker.kt`, after the `resetAllProcessedFlags()` line add:

```kotlin
        Log.d(TAG, "Widget data preserved (not part of daily reset)")
```

- [ ] **Step 3: Verify build compiles**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && ./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/focal/worker/ClassificationWorker.kt app/src/main/java/com/focal/worker/DailyResetWorker.kt
git commit -m "feat(pulse): integrate extraction tools into ClassificationWorker and add widget compute step"
```

---

### Task 9: Navigation — Replace All Tab with Pulse

**Files:**
- Modify: `app/src/main/java/com/focal/ui/navigation/Screen.kt`
- Modify: `app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt`

- [ ] **Step 1: Update Screen.kt**

Replace `Screen.All` with `Screen.Pulse` and add `Screen.PulseDetail`:

```kotlin
package com.focal.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    data object Digest : Screen("digest", "Today", Icons.Default.Star)
    data object Pulse : Screen("pulse", "Pulse", Icons.Default.FavoriteBorder)
    data object Settings : Screen("settings", "Tune", Icons.Default.Settings)
    data object Setup : Screen("setup", "Setup", Icons.Default.Settings)
    data object TopicDetail : Screen("topic/{topicId}", "Topic Detail", Icons.Default.Star) {
        fun createRoute(topicId: String) = "topic/$topicId"
    }
    data object PulseDetail : Screen("pulse/{widgetId}", "Pulse Detail", Icons.Default.FavoriteBorder) {
        fun createRoute(widgetId: String) = "pulse/$widgetId"
    }
}
```

- [ ] **Step 2: Update FocalNavigation.kt**

Replace `Screen.All` in `bottomNavItems` and update NavHost:

```kotlin
val bottomNavItems = listOf(Screen.Digest, Screen.Pulse, Screen.Settings)
```

Replace the `composable(Screen.All.route)` block with:

```kotlin
            composable(Screen.Pulse.route) {
                PulseScreen(
                    onWidgetClick = { widgetId ->
                        navController.navigate(Screen.PulseDetail.createRoute(widgetId))
                    }
                )
            }
```

Add after the `TopicDetail` composable:

```kotlin
            composable(
                route = Screen.PulseDetail.route,
                arguments = listOf(navArgument("widgetId") { type = NavType.StringType })
            ) {
                PulseDetailScreen(
                    onBack = { navController.popBackStack() }
                )
            }
```

Update imports: remove `AllNotificationsScreen`, add `PulseScreen` and `PulseDetailScreen` (these will be created in the next tasks — for now the build will fail, which is expected).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/ui/navigation/Screen.kt app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt
git commit -m "feat(pulse): replace All tab with Pulse in navigation, add PulseDetail route"
```

---

### Task 10: PulseViewModel

**Files:**
- Create: `app/src/main/java/com/focal/ui/pulse/PulseViewModel.kt`

- [ ] **Step 1: Create PulseViewModel**

```kotlin
package com.focal.ui.pulse

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity
import com.focal.data.repository.WidgetRepository
import com.focal.worker.ClassificationWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PulseUiState(
    val configs: List<WidgetConfigEntity> = emptyList(),
    val states: Map<String, WidgetStateEntity> = emptyMap(),
    val isRefreshing: Boolean = false,
    val showWizard: Boolean = false
)

@HiltViewModel
class PulseViewModel @Inject constructor(
    private val widgetRepository: WidgetRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)
    private val showWizard = MutableStateFlow(false)

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<PulseUiState> = combine(
        widgetRepository.observeConfigs(),
        widgetRepository.observeStates(),
        isRefreshing,
        showWizard
    ) { values ->
        val configs = values[0] as List<WidgetConfigEntity>
        val statesList = values[1] as List<WidgetStateEntity>
        val refreshing = values[2] as Boolean
        val wizard = values[3] as Boolean
        PulseUiState(
            configs = configs,
            states = statesList.associateBy { it.widgetId },
            isRefreshing = refreshing,
            showWizard = wizard
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PulseUiState()
    )

    fun onRefresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            WorkManager.getInstance(context).enqueueUniqueWork(
                ClassificationWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ClassificationWorker>().build()
            )
            kotlinx.coroutines.delay(3000)
            isRefreshing.value = false
        }
    }

    fun onShowWizard() { showWizard.value = true }
    fun onDismissWizard() { showWizard.value = false }

    fun onCreateWidget(config: WidgetConfigEntity) {
        viewModelScope.launch {
            widgetRepository.createWidget(config)
            showWizard.value = false
            onRefresh()
        }
    }

    fun onDeleteWidget(widgetId: String) {
        viewModelScope.launch {
            widgetRepository.deleteWidget(widgetId)
        }
    }

    fun onWipeWidget(widgetId: String) {
        viewModelScope.launch {
            widgetRepository.wipeWidgetData(widgetId)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/focal/ui/pulse/PulseViewModel.kt
git commit -m "feat(pulse): add PulseViewModel with widget CRUD, refresh, and wizard state"
```

---

### Task 11: PulseCard Composable

**Files:**
- Create: `app/src/main/java/com/focal/ui/pulse/PulseCard.kt`

- [ ] **Step 1: Create PulseCard**

```kotlin
package com.focal.ui.pulse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity
import com.focal.ui.components.AppIcon
import com.focal.ui.components.RelativeTime
import kotlinx.serialization.json.Json

@Composable
fun PulseCard(
    config: WidgetConfigEntity,
    state: WidgetStateEntity?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = config.category.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    letterSpacing = 2.sp
                )
                if (state != null && System.currentTimeMillis() - state.lastUpdatedAt < 5 * 60 * 1000) {
                    Text(
                        text = "●",
                        color = com.focal.ui.theme.FocalAccent,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Text(
                text = config.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp)
            )

            Text(
                text = state?.headline ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )

            state?.badge?.let { badge ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val appIcons = state?.sourceAppIcons?.let {
                    try { Json.decodeFromString<List<String>>(it) } catch (_: Exception) { null }
                } ?: emptyList()
                Row {
                    appIcons.take(3).forEach { pkg ->
                        AppIcon(packageName = pkg, size = 18.dp)
                    }
                }
                if (state != null) {
                    Text(
                        text = RelativeTime.formatRelativeTime(state.lastUpdatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}
```

Note: The `letterSpacing = 2.sp` needs `import androidx.compose.ui.unit.sp`. The `AppIcon` and `RelativeTime` already exist in `com.focal.ui.components`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/focal/ui/pulse/PulseCard.kt
git commit -m "feat(pulse): add PulseCard compact composable for widget grid"
```

---

### Task 12: PulseScreen

**Files:**
- Create: `app/src/main/java/com/focal/ui/pulse/PulseScreen.kt`

- [ ] **Step 1: Create PulseScreen**

```kotlin
package com.focal.ui.pulse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focal.ui.theme.FocalAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseScreen(
    onWidgetClick: (String) -> Unit,
    viewModel: PulseViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val recentlyUpdated = state.states.values.count {
        System.currentTimeMillis() - it.lastUpdatedAt < 60_000
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "Pulse",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Ask your day a question.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                if (state.states.values.any {
                        System.currentTimeMillis() - it.lastUpdatedAt < 5 * 60 * 1000
                    }) {
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            if (state.configs.isNotEmpty()) {
                Text(
                    text = "● ${state.configs.size} widgets · $recentlyUpdated updated in the last minute",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocalAccent,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (state.configs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No widgets yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = viewModel::onShowWizard) {
                            Text("+ New widget")
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.configs, key = { it.id }) { config ->
                        PulseCard(
                            config = config,
                            state = state.states[config.id],
                            onClick = { onWidgetClick(config.id) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = viewModel::onShowWizard,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("+ New widget")
                }
            }
        }
    }

    if (state.showWizard) {
        PulseWizard(
            onDismiss = viewModel::onDismissWizard,
            onCreate = viewModel::onCreateWidget
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/focal/ui/pulse/PulseScreen.kt
git commit -m "feat(pulse): add PulseScreen with grid layout, LIVE indicator, and pull-to-refresh"
```

---

### Task 13: PulseWizard (3-step Bottom Sheet)

**Files:**
- Create: `app/src/main/java/com/focal/ui/pulse/PulseWizard.kt`
- Create: `app/src/main/java/com/focal/ui/pulse/WidgetTemplates.kt`

- [ ] **Step 1: Create WidgetTemplates**

```kotlin
package com.focal.ui.pulse

import com.focal.data.db.entity.WidgetConfigEntity

data class WidgetTemplate(
    val category: String,
    val title: String,
    val description: String,
    val defaultOperation: String,
    val extractionTool: String,
    val field: String?,
    val groupBy: String?,
    val headlineTemplate: String,
    val defaultApps: List<String>
)

val STARTER_TEMPLATES = listOf(
    WidgetTemplate(
        category = "finance",
        title = "Money today",
        description = "Track spending across banking apps",
        defaultOperation = "SUM",
        extractionTool = "extract_finance",
        field = "amount",
        groupBy = "merchant",
        headlineTemplate = "₹{result}",
        defaultApps = listOf("com.hdfc.bank", "com.icici.bank", "net.one97.paytm", "com.phonepe.app", "com.google.android.apps.nbu.paisa.user")
    ),
    WidgetTemplate(
        category = "work",
        title = "Work pulse",
        description = "PRs, mentions, action items",
        defaultOperation = "COUNT",
        extractionTool = "extract_work",
        field = null,
        groupBy = "sender",
        headlineTemplate = "{result} items need you",
        defaultApps = listOf("com.github.android", "com.Slack", "com.linear", "com.google.android.gm")
    ),
    WidgetTemplate(
        category = "personal",
        title = "People who reached out",
        description = "Calls, messages, missed contacts",
        defaultOperation = "LIST",
        extractionTool = "extract_personal",
        field = null,
        groupBy = "sender",
        headlineTemplate = "{top_sender} · {count}",
        defaultApps = listOf("com.android.dialer", "com.whatsapp", "com.google.android.apps.messaging", "org.telegram.messenger")
    ),
    WidgetTemplate(
        category = "logistics",
        title = "Deliveries",
        description = "Order tracking and ETAs",
        defaultOperation = "STATUS",
        extractionTool = "extract_logistics",
        field = null,
        groupBy = null,
        headlineTemplate = "{in_transit} in transit",
        defaultApps = listOf("in.amazon.mShop.android.shopping", "in.swiggy.android", "com.application.zomato", "com.flipkart.android")
    )
)

val ALL_OPERATIONS = listOf(
    "SUM" to "Add up numbers",
    "COUNT" to "Count matching items",
    "LATEST" to "Show the newest one",
    "LIST" to "Group by sender/type",
    "MAX" to "Pick the highest value",
    "STATUS" to "Roll up into one state"
)

fun WidgetTemplate.toConfig(operation: String = defaultOperation, filterApps: List<String>? = null): WidgetConfigEntity {
    return WidgetConfigEntity(
        category = category,
        title = title,
        operation = operation,
        extractionTool = extractionTool,
        field = field,
        groupBy = groupBy,
        headlineTemplate = headlineTemplate,
        filterApps = filterApps?.let { kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer<String>()), it) },
        source = "TEMPLATE"
    )
}
```

- [ ] **Step 2: Create PulseWizard**

```kotlin
package com.focal.ui.pulse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.ui.theme.FocalAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseWizard(
    onDismiss: () -> Unit,
    onCreate: (WidgetConfigEntity) -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var selectedTemplate by remember { mutableStateOf<WidgetTemplate?>(null) }
    var selectedOperation by remember { mutableStateOf("") }
    var useAutoApps by remember { mutableStateOf(true) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "STEP $step OF 3 · ${when (step) { 1 -> "TEMPLATE"; 2 -> "RECIPE"; else -> "SOURCES" }}",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )

            when (step) {
                1 -> {
                    Text(
                        text = "What do you want to track?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )
                    STARTER_TEMPLATES.forEach { template ->
                        val isSelected = selectedTemplate == template
                        Surface(
                            color = if (isSelected) FocalAccent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            border = if (isSelected) BorderStroke(1.dp, FocalAccent) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    selectedTemplate = template
                                    selectedOperation = template.defaultOperation
                                }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(text = template.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = template.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "Ask a custom question... (coming soon)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                2 -> {
                    Text(
                        text = "How should Focal compute it?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )

                    Surface(
                        color = FocalAccent.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, FocalAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "RECIPE · AUTO",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                            Text(
                                text = "$selectedOperation of ${selectedTemplate?.title ?: "items"}",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "OPERATION",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val chunked = ALL_OPERATIONS.chunked(3)
                    chunked.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { (op, desc) ->
                                val isSelected = selectedOperation == op
                                Surface(
                                    color = if (isSelected) FocalAccent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(10.dp),
                                    border = if (isSelected) BorderStroke(1.dp, FocalAccent) else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { selectedOperation = op }
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(text = op.lowercase().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                        Text(text = desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                3 -> {
                    Text(
                        text = "From which apps?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )

                    Surface(
                        color = if (useAutoApps) FocalAccent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        border = if (useAutoApps) BorderStroke(1.dp, FocalAccent) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { useAutoApps = true }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(text = "Auto — let Focal decide", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Picks relevant apps from your notifications",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (step > 1) {
                    TextButton(onClick = { step-- }) { Text("Back") }
                } else {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }

                if (step < 3) {
                    Button(
                        onClick = { step++ },
                        enabled = selectedTemplate != null
                    ) { Text("Next →") }
                } else {
                    Button(
                        onClick = {
                            selectedTemplate?.let { template ->
                                val config = template.toConfig(
                                    operation = selectedOperation,
                                    filterApps = if (useAutoApps) null else null
                                )
                                onCreate(config)
                            }
                        },
                        enabled = selectedTemplate != null
                    ) { Text("Create widget") }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/ui/pulse/PulseWizard.kt app/src/main/java/com/focal/ui/pulse/WidgetTemplates.kt
git commit -m "feat(pulse): add PulseWizard 3-step bottom sheet and starter widget templates"
```

---

### Task 14: PulseDetailScreen

**Files:**
- Create: `app/src/main/java/com/focal/ui/pulse/PulseDetailScreen.kt`
- Create: `app/src/main/java/com/focal/ui/pulse/PulseDetailViewModel.kt`

- [ ] **Step 1: Create PulseDetailViewModel**

```kotlin
package com.focal.ui.pulse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity
import com.focal.data.repository.WidgetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PulseDetailUiState(
    val config: WidgetConfigEntity? = null,
    val state: WidgetStateEntity? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class PulseDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val widgetRepository: WidgetRepository
) : ViewModel() {

    private val widgetId: String = savedStateHandle.get<String>("widgetId") ?: ""

    private val _uiState = MutableStateFlow(PulseDetailUiState())
    val uiState: StateFlow<PulseDetailUiState> = _uiState.asStateFlow()

    init {
        loadWidget()
        observeState()
    }

    private fun loadWidget() {
        viewModelScope.launch {
            val config = widgetRepository.getConfig(widgetId)
            _uiState.value = _uiState.value.copy(config = config, isLoading = false)
        }
    }

    private fun observeState() {
        viewModelScope.launch {
            widgetRepository.observeState(widgetId).collect { state ->
                _uiState.value = _uiState.value.copy(state = state)
            }
        }
    }

    fun onDelete() {
        viewModelScope.launch {
            widgetRepository.deleteWidget(widgetId)
        }
    }

    fun onWipe() {
        viewModelScope.launch {
            widgetRepository.wipeWidgetData(widgetId)
        }
    }
}
```

- [ ] **Step 2: Create PulseDetailScreen**

```kotlin
package com.focal.ui.pulse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.focal.ui.components.RelativeTime
import com.focal.ui.components.SectionHeader
import com.focal.ui.theme.FocalAccent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseDetailScreen(
    onBack: () -> Unit,
    viewModel: PulseDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showMenu by remember { mutableStateOf(false) }

    val config = state.config
    val widgetState = state.state

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(config?.title ?: "Widget") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Wipe data") },
                                onClick = { showMenu = false; viewModel.onWipe() }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete widget") },
                                onClick = { showMenu = false; viewModel.onDelete(); onBack() }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (config == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Widget not found")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = config.category.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                item {
                    Text(
                        text = widgetState?.headline ?: "No data yet",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                widgetState?.badge?.let { badge ->
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                widgetState?.subtitle?.let { subtitle ->
                    item {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                widgetState?.detailJson?.let { detailJson ->
                    val details = try {
                        Json.parseToJsonElement(detailJson).jsonArray.map { el ->
                            val obj = el.jsonObject
                            (obj["label"]?.jsonPrimitive?.content ?: "") to (obj["value"]?.jsonPrimitive?.content ?: "")
                        }
                    } catch (_: Exception) { emptyList() }

                    if (details.isNotEmpty()) {
                        item { SectionHeader(title = "BREAKDOWN") }
                        items(details) { (label, value) ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                if (widgetState != null) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${widgetState.itemCount} notifications · updated ${RelativeTime.formatRelativeTime(widgetState.lastUpdatedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/focal/ui/pulse/PulseDetailScreen.kt app/src/main/java/com/focal/ui/pulse/PulseDetailViewModel.kt
git commit -m "feat(pulse): add PulseDetailScreen and PulseDetailViewModel for expanded widget view"
```

---

### Task 15: Wire Everything Together and Build Verification

**Files:**
- Modify: `app/src/main/java/com/focal/ui/navigation/FocalNavigation.kt` (add imports)

- [ ] **Step 1: Verify FocalNavigation imports are correct**

In `FocalNavigation.kt`, ensure imports include:

```kotlin
import com.focal.ui.pulse.PulseScreen
import com.focal.ui.pulse.PulseDetailScreen
```

Remove:
```kotlin
import com.focal.ui.all.AllNotificationsScreen
```

- [ ] **Step 2: Full build check**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && ./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

Fix any compilation errors. Common issues:
- Missing imports (sp, RoundedCornerShape, etc.)
- Type mismatches in serialization
- Missing `@Serializable` on data classes used with `Json.encodeToString`

- [ ] **Step 3: Run existing unit tests to check for regressions**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && ./gradlew :app:testDebugUnitTest 2>&1 | tail -20`
Expected: All existing tests pass. New code has no unit tests yet (they'll be added after on-device verification).

- [ ] **Step 4: Commit any fixes**

```bash
git add -A
git commit -m "fix(pulse): resolve build errors and wire navigation imports"
```

---

### Task 16: Unit Tests for WidgetComputeEngine

**Files:**
- Create: `app/src/test/java/com/focal/intelligence/WidgetComputeEngineTest.kt`

- [ ] **Step 1: Write tests for each operation**

```kotlin
package com.focal.intelligence

import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity
import com.focal.data.repository.WidgetRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class WidgetComputeEngineTest {

    private val repo = mock<WidgetRepository>()
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
        whenever(repo.getAllConfigs()).thenReturn(emptyList())
        engine.computeAll()
    }

    @Test
    fun `SUM computes total amount`() = runTest {
        val config = financeConfig("SUM")
        whenever(repo.getAllConfigs()).thenReturn(listOf(config))
        whenever(repo.getExtractedData("finance")).thenReturn(listOf(
            financeData(425.0, "Swiggy"),
            financeData(3200.0, "ICICI"),
            financeData(1080.0, "Uber")
        ))
        whenever(repo.saveWidgetStates(org.mockito.kotlin.any())).thenAnswer { invocation ->
            val states = invocation.getArgument<List<WidgetStateEntity>>(0)
            assertEquals(1, states.size)
            assertTrue(states[0].headline.contains("4705"))
            null
        }
        engine.computeAll()
    }

    @Test
    fun `COUNT returns item count`() = runTest {
        val config = financeConfig("COUNT")
        whenever(repo.getAllConfigs()).thenReturn(listOf(config))
        whenever(repo.getExtractedData("finance")).thenReturn(listOf(
            financeData(100.0, "A"),
            financeData(200.0, "B")
        ))
        whenever(repo.saveWidgetStates(org.mockito.kotlin.any())).thenAnswer { invocation ->
            val states = invocation.getArgument<List<WidgetStateEntity>>(0)
            assertEquals("2", states[0].headline)
            null
        }
        engine.computeAll()
    }

    @Test
    fun `empty data produces zero headline`() = runTest {
        whenever(repo.getAllConfigs()).thenReturn(listOf(financeConfig()))
        whenever(repo.getExtractedData("finance")).thenReturn(emptyList())
        whenever(repo.saveWidgetStates(org.mockito.kotlin.any())).thenAnswer { invocation ->
            val states = invocation.getArgument<List<WidgetStateEntity>>(0)
            assertEquals("₹0", states[0].headline)
            assertEquals(0, states[0].itemCount)
            null
        }
        engine.computeAll()
    }
}
```

- [ ] **Step 2: Run tests**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && ./gradlew :app:testDebugUnitTest --tests "com.focal.intelligence.WidgetComputeEngineTest" 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/focal/intelligence/WidgetComputeEngineTest.kt
git commit -m "test(pulse): add unit tests for WidgetComputeEngine operations"
```

---

### Task 17: Unit Tests for ExtractionToolSet

**Files:**
- Create: `app/src/test/java/com/focal/intelligence/ExtractionToolSetTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package com.focal.intelligence

import org.junit.Assert.*
import org.junit.Test

class ExtractionToolSetTest {

    @Test
    fun `ExtractFinanceTool stores results`() {
        val tool = ExtractFinanceTool()
        tool.extractFinance(1, 425.0, "Swiggy", "food", "debit")
        tool.extractFinance(2, 3200.0, "ICICI", "bills", "debit")

        assertEquals(2, tool.results.size)
        assertEquals("finance", tool.results[0].category)
        assertEquals(1, tool.results[0].notificationIndex)
        assertTrue(tool.results[0].dataJson.contains("425"))
        assertTrue(tool.results[0].dataJson.contains("Swiggy"))
    }

    @Test
    fun `ExtractionToolFactory creates only requested categories`() {
        val tools = ExtractionToolFactory.createTools(listOf("finance", "logistics"))
        assertEquals(2, tools.size)
        assertTrue(tools.containsKey("finance"))
        assertTrue(tools.containsKey("logistics"))
        assertFalse(tools.containsKey("work"))
    }

    @Test
    fun `ExtractionToolFactory collectResults aggregates all tools`() {
        val tools = ExtractionToolFactory.createTools(listOf("finance", "work"))
        (tools["finance"] as ExtractFinanceTool).extractFinance(1, 100.0, "Test", "food", "debit")
        (tools["work"] as ExtractWorkTool).extractWork(2, "PR #1", "Alice", "review_requested", "repo")

        val results = ExtractionToolFactory.collectResults(tools)
        assertEquals(2, results.size)
        assertEquals("finance", results[0].category)
        assertEquals("work", results[1].category)
    }

    @Test
    fun `empty categories produces empty tools map`() {
        val tools = ExtractionToolFactory.createTools(emptyList())
        assertTrue(tools.isEmpty())
    }
}
```

- [ ] **Step 2: Run tests**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && ./gradlew :app:testDebugUnitTest --tests "com.focal.intelligence.ExtractionToolSetTest" 2>&1 | tail -10`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/focal/intelligence/ExtractionToolSetTest.kt
git commit -m "test(pulse): add unit tests for extraction tools and factory"
```

---

### Task 18: Final Build, Test, and Push

- [ ] **Step 1: Full build**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && ./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run all unit tests**

Run: `cd /Users/shubhankar.bhadra/pet-project/focal-glint && ./gradlew :app:testDebugUnitTest 2>&1 | tail -20`
Expected: All tests pass, no regressions

- [ ] **Step 3: Push to remote**

```bash
git push origin dev-darahas
```
