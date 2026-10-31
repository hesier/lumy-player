package com.hesier.lumy.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.hesier.lumy.core.AudioStream
import com.hesier.lumy.core.MediaPart
import com.hesier.lumy.core.MediaVersion
import com.hesier.lumy.core.SubtitleRule
import com.hesier.lumy.core.SubtitleStream

class TrackMemoryTest(context: Context) {
    private val context = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("track-memory-test-$name", mode)
    }
    private val prefs get() = context.getSharedPreferences("track_memory", Context.MODE_PRIVATE)
    private lateinit var settings: Settings
    private lateinit var memory: TrackMemory
    private lateinit var choices: TrackPreferences
    private val english = AudioStream(id = "en", codec = "aac", channels = 2, language = "eng", title = "English", selected = false)
    private val japanese = english.copy(id = "ja", language = "jpn", title = "Japanese", selected = true)
    private val englishSub = SubtitleStream(id = "en-sub", codec = "srt", language = "eng", title = "English",
        forced = false, selected = false, key = null)
    private val japaneseSub = englishSub.copy(id = "ja-sub", language = "jpn", title = "Japanese", selected = true)
    private val version = MediaVersion(id = "version", index = 0, container = "mkv", bitrateKbps = null, video = null,
        audio = listOf(english, japanese), subtitles = listOf(englishSub, japaneseSub),
        parts = listOf(MediaPart(id = "file", key = "file.mkv", container = "mkv", sizeBytes = null, durationMs = null)))

    fun runAll() {
        reset(); migrationPreservesChoices()
        reset(); writesAreIndependent()
        reset(); switchesIgnoreAndPreserveMemories()
        reset(); invalidTracksFallBackIndependently()
        reset(); audioClearPreservesSubtitles()
        reset(); subtitleClearPreservesAudio()
        reset(); forgetServerRemovesOnlyItsRecords()
    }

    private fun reset() {
        check(prefs.edit().clear().commit())
        check(context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit())
        settings = Settings(context)
        memory = TrackMemory(context)
        choices = TrackPreferences(settings, memory, "server")
    }

    private fun migrationPreservesChoices() {
        prefs.edit().putString("server|part:file", "en|en-sub")
            .putString("server|part:off", "ja|")
            .putString("other|part:file", "ja|ja-sub")
            .putString("server|part:already", "en|en-sub")
            .putString("server|part:already:audio", "ja")
            .putString("server|part:already:sub", "")
            .putString("server|show:show:audio", "eng")
            .putString("server|show:show:sub", "off").commit()
        memory = TrackMemory(context)
        check(memory.fileAudio("server", "file") == "en" && memory.fileSubtitle("server", "file") == "en-sub")
        check(memory.fileSubtitle("server", "off") == "" && memory.fileSubtitle("server", "missing") == null)
        check(memory.fileAudio("server", "already") == "ja" && memory.fileSubtitle("server", "already") == "")
        check(memory.fileAudio("other", "file") == "ja" && memory.fileSubtitle("other", "file") == "ja-sub")
        check(memory.showAudio("server", "show") == "eng" && memory.showSubtitle("server", "show") == SubtitleRule.Off)
        check(!prefs.contains("server|part:file") && !prefs.contains("server|part:off"))
        memory.clearAudio()
        check(TrackMemory(context).fileAudio("server", "file") == null)
        check(memory.fileSubtitle("server", "file") == "en-sub")
    }

    private fun writesAreIndependent() {
        check(settings.rememberAudioSelection && settings.rememberSubtitleSelection)
        choices.rememberAudio(version, "show", english)
        check(memory.fileAudio("server", "file") == "en" && memory.fileSubtitle("server", "file") == null)
        check(memory.showAudio("server", "show") == "eng" && memory.showSubtitle("server", "show") == null)
        choices.rememberSubtitle(version, "show", japaneseSub)
        choices.rememberAudio(version, "show", japanese)
        check(memory.fileAudio("server", "file") == "ja" && memory.fileSubtitle("server", "file") == "ja-sub")
        choices.rememberSubtitle(version, "show", null)
        check(memory.fileAudio("server", "file") == "ja" && memory.fileSubtitle("server", "file") == "")
        check(memory.showSubtitle("server", "show") == SubtitleRule.Off)
    }

    private fun switchesIgnoreAndPreserveMemories() {
        choices.rememberAudio(version, "show", english)
        choices.rememberSubtitle(version, "show", englishSub)
        settings.audioLanguage = "jpn"
        settings.subtitleMode = SubtitleMode.LANGUAGE
        settings.subtitleLanguage = "jpn"
        for (rememberAudio in listOf(false, true)) for (rememberSubtitles in listOf(false, true)) {
            settings.rememberAudioSelection = rememberAudio
            settings.rememberSubtitleSelection = rememberSubtitles
            val audio = choices.audio(version, "show")
            check(audio == if (rememberAudio) english else japanese)
            check(choices.subtitle(version, "show", audio) == if (rememberSubtitles) englishSub else japaneseSub)
        }
        settings.rememberAudioSelection = false
        settings.rememberSubtitleSelection = false
        choices.rememberAudio(version, "show", japanese)
        choices.rememberSubtitle(version, "show", null)
        check(memory.fileAudio("server", "file") == "en" && memory.fileSubtitle("server", "file") == "en-sub")
        check(memory.showAudio("server", "show") == "eng" && memory.showSubtitle("server", "show") == SubtitleRule.Language("eng"))
        settings.rememberAudioSelection = true
        settings.rememberSubtitleSelection = true
        check(choices.audio(version, "show") == english && choices.subtitle(version, "show", english) == englishSub)
        settings.rememberAudioSelection = false
        settings.rememberSubtitleSelection = false
        choices.rememberAudio(version.copy(parts = listOf(version.part.copy(id = "new"))), "new", japanese)
        choices.rememberSubtitle(version.copy(parts = listOf(version.part.copy(id = "new"))), "new", japaneseSub)
        check(memory.fileAudio("server", "new") == null && memory.fileSubtitle("server", "new") == null)
        check(memory.showAudio("server", "new") == null && memory.showSubtitle("server", "new") == null)
    }

    private fun invalidTracksFallBackIndependently() {
        memory.rememberFileAudio("server", "file", "missing")
        memory.rememberFileSubtitle("server", "file", "en-sub")
        memory.rememberShowAudio("server", "show", "eng")
        settings.audioLanguage = "jpn"
        check(choices.audio(version, "show") == english)
        check(choices.subtitle(version, "show", english) == englishSub)
        memory.rememberFileAudio("server", "file", "en")
        memory.rememberFileSubtitle("server", "file", "missing")
        memory.rememberShowSubtitle("server", "show", SubtitleRule.Language("jpn"))
        check(choices.audio(version, "show") == english)
        check(choices.subtitle(version, "show", english) == japaneseSub)
        memory.rememberShowSubtitle("server", "show", SubtitleRule.Off)
        check(choices.subtitle(version, "show", english) == null)
        settings.rememberSubtitleSelection = false
        check(choices.subtitle(version, "show", english) == japaneseSub)
        settings.rememberAudioSelection = false
        settings.audioLanguage = "fra"
        check(choices.audio(version, "show") == japanese)
    }

    private fun seedBothServers() {
        choices.rememberAudio(version, "show", english)
        choices.rememberSubtitle(version, "show", null)
        val other = TrackPreferences(settings, memory, "other")
        other.rememberAudio(version, "show", japanese)
        other.rememberSubtitle(version, "show", japaneseSub)
        settings.rememberAudioSelection = false
        settings.rememberSubtitleSelection = false
    }

    private fun audioClearPreservesSubtitles() {
        seedBothServers()
        memory.clearAudio()
        for (server in listOf("server", "other")) {
            check(memory.fileAudio(server, "file") == null && memory.showAudio(server, "show") == null)
        }
        check(memory.fileSubtitle("server", "file") == "" && memory.showSubtitle("server", "show") == SubtitleRule.Off)
        check(memory.fileSubtitle("other", "file") == "ja-sub" && memory.showSubtitle("other", "show") == SubtitleRule.Language("jpn"))
        check(!settings.rememberAudioSelection && !settings.rememberSubtitleSelection)
    }

    private fun subtitleClearPreservesAudio() {
        seedBothServers()
        memory.clearSubtitles()
        for (server in listOf("server", "other")) {
            check(memory.fileSubtitle(server, "file") == null && memory.showSubtitle(server, "show") == null)
        }
        check(memory.fileAudio("server", "file") == "en" && memory.showAudio("server", "show") == "eng")
        check(memory.fileAudio("other", "file") == "ja" && memory.showAudio("other", "show") == "jpn")
        check(!settings.rememberAudioSelection && !settings.rememberSubtitleSelection)
    }

    private fun forgetServerRemovesOnlyItsRecords() {
        seedBothServers()
        memory.forgetServer("server")
        check(prefs.all.keys.none { it.startsWith("server|") })
        check(memory.fileAudio("other", "file") == "ja" && memory.fileSubtitle("other", "file") == "ja-sub")
        check(memory.showAudio("other", "show") == "jpn" && memory.showSubtitle("other", "show") != null)
    }
}
