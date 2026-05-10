package com.focal.ui.pulse

import com.focal.data.db.entity.WidgetConfigEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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
    WidgetTemplate("finance", "Money today", "Track spending across banking apps", "SUM", "extract_finance", "amount", "merchant", "₹{result}", listOf("com.hdfc.bank", "com.icici.bank", "net.one97.paytm", "com.phonepe.app", "com.google.android.apps.nbu.paisa.user")),
    WidgetTemplate("work", "Work pulse", "PRs, mentions, action items", "COUNT", "extract_work", null, "sender", "{result} items need you", listOf("com.github.android", "com.Slack", "com.linear", "com.google.android.gm")),
    WidgetTemplate("personal", "People who reached out", "Calls, messages, missed contacts", "LIST", "extract_personal", null, "sender", "{top_sender} · {count}", listOf("com.android.dialer", "com.whatsapp", "com.google.android.apps.messaging", "org.telegram.messenger")),
    WidgetTemplate("logistics", "Deliveries", "Order tracking and ETAs", "STATUS", "extract_logistics", null, null, "{in_transit} in transit", listOf("in.amazon.mShop.android.shopping", "in.swiggy.android", "com.application.zomato", "com.flipkart.android"))
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
        category = category, title = title, operation = operation,
        extractionTool = extractionTool, field = field, groupBy = groupBy,
        headlineTemplate = headlineTemplate,
        filterApps = filterApps?.let { Json.encodeToString(ListSerializer(String.serializer()), it) },
        source = "TEMPLATE"
    )
}
