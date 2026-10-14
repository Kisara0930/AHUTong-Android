package com.ahu.ahutong.ui.shell.home

/** Defaults only; a saved per-account slot layout always takes precedence. */
internal object HomeWidgetDefaults {
    const val NOTICE_WIDGET_ID = "campus_notices"

    val classic: List<String?> = listOf(
        "bathroom", "electricity", NOTICE_WIDGET_ID
    ) + List(5) { null }

    val radiant: List<String?> = listOf(
        "electricity", "bathroom", "grade", "exam",
        "weather", "network_recharge", NOTICE_WIDGET_ID
    )
}
