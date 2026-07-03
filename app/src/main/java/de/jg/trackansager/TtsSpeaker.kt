package de.jg.trackansager

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TtsSpeaker(private val ctx: Context) {

    enum class Position { START, END }

    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .build()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var configured = false

    init {
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                configure()
            } else {
                Log.e(TAG, "TTS-Initialisierung fehlgeschlagen")
            }
        }
        if (ready && !configured) configure()
    }

    private fun configure() {
        val t = tts ?: return
        if (configured) return
        configured = true
        t.setAudioAttributes(audioAttributes)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                audioManager.abandonAudioFocusRequest(focusRequest)
            }
        })
    }

    fun announce(title: String, artist: String, position: Position, languageMode: String) {
        val t = tts ?: return
        if (!ready) return
        if (!configured) configure()

        val useGerman = when (languageMode) {
            Prefs.LANG_DE -> true
            Prefs.LANG_EN -> false
            else -> looksGerman("$title $artist")
        }

        val locale = if (useGerman) Locale.GERMANY else Locale.US
        val text = buildText(title, artist, position, useGerman)

        val result = t.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.getDefault())
        }

        audioManager.requestAudioFocus(focusRequest)

        val params = Bundle()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, params, "announce_${System.currentTimeMillis()}")
    }

    fun speakTest(languageMode: String) {
        announce("Bohemian Rhapsody", "Queen", Position.START, languageMode)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun buildText(title: String, artist: String, position: Position, german: Boolean): String {
        val hasArtist = artist.isNotBlank()
        return if (german) {
            when (position) {
                Position.START -> if (hasArtist) "Jetzt: $title, von $artist" else "Jetzt: $title"
                Position.END -> if (hasArtist) "Das war $title, von $artist" else "Das war $title"
            }
        } else {
            when (position) {
                Position.START -> if (hasArtist) "Now playing: $title, by $artist" else "Now playing: $title"
                Position.END -> if (hasArtist) "That was $title, by $artist" else "That was $title"
            }
        }
    }

    private fun looksGerman(text: String): Boolean {
        if (Regex("[äöüßÄÖÜ]").containsMatchIn(text)) return true
        val germanWords = setOf(
            "der", "die", "das", "und", "ich", "du", "wir", "ihr", "nicht",
            "ein", "eine", "mit", "für", "auf", "von", "zu", "mein", "dein",
            "liebe", "herz", "nacht", "zeit", "leben", "immer", "wieder",
            "alles", "nur", "wenn", "dann", "auch", "noch", "so", "wie",
            "kein", "mehr", "gegen", "über", "unter", "durch", "bis", "ohne"
        )
        val words = text.lowercase(Locale.GERMAN).split(Regex("[^a-zäöüß]+")).filter { it.isNotBlank() }
        return words.any { it in germanWords }
    }

    companion object {
        private const val TAG = "TtsSpeaker"
    }
}
