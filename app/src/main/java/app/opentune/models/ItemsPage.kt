package app.opentune.models

import app.opentune.db.entities.LocalItem


data class ItemsPage(
    val items: List<LocalItem>,
    val continuation: String?,
)
