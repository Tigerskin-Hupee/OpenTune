package app.opentune.models

import app.opentune.db.entities.LocalItem


data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<LocalItem>,
)
