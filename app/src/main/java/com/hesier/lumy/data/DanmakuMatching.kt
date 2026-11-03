package com.hesier.lumy.data

import com.hesier.lumy.net.danmaku.DanmakuAnime
import com.hesier.lumy.net.danmaku.DanmakuEpisode

internal enum class DanmakuMatchReason { AMBIGUOUS, METADATA, INCOMPLETE, NO_MATCH }

internal data class DanmakuSuggestion(val anime: DanmakuAnime, val episode: DanmakuEpisode? = null,
    val sourceOffsetMs: Long = 0, val reason: DanmakuMatchReason = DanmakuMatchReason.METADATA)

internal data class DanmakuAssessment(val suggestion: DanmakuSuggestion, val titleScore: Double,
    val score: Double, val eligible: Boolean, val definitiveMissingEpisode: Boolean = false)

internal data class DanmakuDecision(val selected: DanmakuSuggestion? = null,
    val suggestions: List<DanmakuSuggestion> = emptyList(), val reason: DanmakuMatchReason = DanmakuMatchReason.NO_MATCH)
