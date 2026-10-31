package com.hesier.lumy.data

import com.hesier.lumy.core.AudioStream
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.SubtitleRule
import com.hesier.lumy.core.SubtitleStream
import com.hesier.lumy.core.TrackLanguage
import com.hesier.lumy.core.TrackPicker

/** Resolve and save each track type independently, including explicit subtitle-off choices. */
internal class TrackPreferences(
    private val settings: Settings,
    private val memory: TrackMemory,
    private val serverId: String,
) {
    fun audio(version: MediaVersion, showId: String?): AudioStream? {
        if (settings.rememberAudioSelection) {
            val id = memory.fileAudio(serverId, version.part.id)
            version.audio.firstOrNull { it.id == id }?.let { return it }
        }
        val showLanguage = showId?.takeIf { settings.rememberAudioSelection }?.let { memory.showAudio(serverId, it) }
        return listOfNotNull(showLanguage, settings.audioLanguage)
            .firstNotNullOfOrNull { TrackPicker.audio(version.audio, it) }
            ?: version.audio.firstOrNull { it.selected } ?: version.audio.firstOrNull()
    }

    fun subtitle(version: MediaVersion, showId: String?, audio: AudioStream?): SubtitleStream? {
        if (settings.rememberSubtitleSelection) {
            val id = memory.fileSubtitle(serverId, version.part.id)
            if (id == "") return null
            version.subtitles.firstOrNull { it.id == id }?.let { return it }
        }
        val showRule = showId?.takeIf { settings.rememberSubtitleSelection }?.let { memory.showSubtitle(serverId, it) }
        val heard = audio?.let { TrackLanguage.of(it.language, it.title) }
        val choice = listOfNotNull(showRule, settings.subtitleRule)
            .firstNotNullOfOrNull { TrackPicker.subtitle(version.subtitles, it, heard) }
        return if (choice != null) choice.stream else version.subtitles.firstOrNull { it.selected }
    }

    fun rememberAudio(version: MediaVersion, showId: String?, stream: AudioStream?) {
        if (!settings.rememberAudioSelection || stream == null) return
        memory.rememberFileAudio(serverId, version.part.id, stream.id)
        val language = TrackLanguage.of(stream.language, stream.title) ?: return
        showId?.let { memory.rememberShowAudio(serverId, it, language) }
    }

    fun rememberSubtitle(version: MediaVersion, showId: String?, stream: SubtitleStream?) {
        if (!settings.rememberSubtitleSelection) return
        memory.rememberFileSubtitle(serverId, version.part.id, stream?.id)
        val rule = if (stream == null) SubtitleRule.Off else {
            val language = TrackLanguage.of(stream.language, stream.title) ?: return
            if (stream.forced) SubtitleRule.ForcedOnly(language) else SubtitleRule.Language(language)
        }
        showId?.let { memory.rememberShowSubtitle(serverId, it, rule) }
    }
}
