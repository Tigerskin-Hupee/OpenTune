/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package app.opentune.models

import app.opentune.db.entities.FormatEntity
import app.opentune.db.entities.Song

/**
 * For passing along song metadata
 */
data class SongTempData(val song: Song, val format: FormatEntity?)