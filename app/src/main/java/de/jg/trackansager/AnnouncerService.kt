package de.jg.trackansager

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Herzstück der App.
 *
 * Läuft als NotificationListenerService (dadurch bekommt die App die
 * Berechtigung, aktive MediaSessions auszulesen). Sie hängt sich an die
 * MediaSession von Amazon Music (oder einer anderen Musik-App), bekommt
 * bei jedem Titelwechsel die Metadaten (Titel, Interpret, Dauer) und
 * sagt sie per TTS an — je nach Einstellung am Anfang und/oder kurz
 * vor dem Ende des Songs.
 *
 * Die Musik selbst läuft weiterhin in der Amazon-Music-App; diese App
 * greift nicht in den Amazon-Account ein und streamt nichts selbst.
 */
class AnnouncerService : NotificationListenerService() {

    private lateinit var sessionManager: MediaSessionManager
    private var speaker: TtsSpeaker? = null
    private val handler = Handler(Looper.getMainLooper())

    private var controller: MediaController? = null
    private var lastAnnouncedKey: String? = null
    private var endRunnable: Runnable? = null

    /** Amazon-Music-Paketnamen (Handy-App) */
    private val amazonPackages = setOf("com.amazon.mp3", "com.amazon.music")

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attachBestController(controllers)
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadata(metadata, announceStartAllowed = true)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Bei Play/Pause/Seek die End-Ansage neu terminieren
            scheduleEndAnnouncement()
        }

        override fun onSessionDestroyed() {
            detachController()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener verbunden")
        speaker = TtsSpeaker(this)
        sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, AnnouncerService::class.java)
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component)
            attachBestController(sessionManager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.e(TAG, "Keine Berechtigung für MediaSessions", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        detachController()
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        speaker?.shutdown()
        speaker = null
    }

    /**
     * Wählt aus den aktiven MediaSessions die passende aus:
     * bevorzugt Amazon Music, sonst (falls erlaubt) die erste spielende Session.
     */
    private fun attachBestController(controllers: List<MediaController>?) {
        val list = controllers ?: emptyList()
        val amazon = list.firstOrNull { it.packageName in amazonPackages }
        val chosen = when {
            amazon != null -> amazon
            Prefs.amazonOnly(this) -> null
            else -> list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list.firstOrNull()
        }

        if (chosen?.sessionToken == controller?.sessionToken) return

        detachController()

        if (chosen != null) {
            controller = chosen
            chosen.registerCallback(controllerCallback, handler)
            Log.i(TAG, "Verbunden mit MediaSession von ${chosen.packageName}")
            // Aktuellen Titel übernehmen, aber nicht sofort ansagen
            // (sonst quatscht die App los, sobald man sie aktiviert)
            val md = chosen.metadata
            if (md != null) {
                lastAnnouncedKey = keyOf(md)
                scheduleEndAnnouncement()
            }
        }
    }

    private fun detachController() {
        cancelEndAnnouncement()
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    private fun handleMetadata(metadata: MediaMetadata?, announceStartAllowed: Boolean) {
        metadata ?: return
        val key = keyOf(metadata)
        if (key == lastAnnouncedKey) {
            // Gleicher Titel, nur aktualisierte Metadaten -> ggf. End-Ansage neu planen
            scheduleEndAnnouncement()
            return
        }
        lastAnnouncedKey = key

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""

        Log.i(TAG, "Neuer Titel: $title – $artist")

        if (announceStartAllowed && Prefs.announceStart(this)) {
            // Minimale Verzögerung, damit der Song hörbar angefangen hat
            handler.postDelayed({
                speaker?.announce(title, artist, TtsSpeaker.Position.START, Prefs.language(this))
            }, 800)
        }

        scheduleEndAnnouncement()
    }

    /**
     * Plant die Ansage kurz vor dem Songende.
     * Berechnet aus PlaybackState (Position + Zeitstempel + Geschwindigkeit)
     * die aktuelle Abspielposition und terminiert die Ansage auf
     * (Dauer - Vorlauf) Sekunden.
     */
    private fun scheduleEndAnnouncement() {
        cancelEndAnnouncement()

        if (!Prefs.announceEnd(this)) return
        val c = controller ?: return
        val md = c.metadata ?: return
        val state = c.playbackState ?: return
        if (state.state != PlaybackState.STATE_PLAYING) return

        val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (duration <= 0) return

        val speed = if (state.playbackSpeed > 0f) state.playbackSpeed else 1f
        val elapsedSinceUpdate = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        val position = state.position + (elapsedSinceUpdate * speed).toLong()

        val leadMs = Prefs.endLeadSeconds(this) * 1000L
        val delay = duration - position - leadMs

        // Nur planen, wenn noch sinnvoll Zeit bleibt
        if (delay < 1500) return

        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val keyAtSchedule = keyOf(md)

        val r = Runnable {
            // Sicherstellen, dass immer noch derselbe Titel läuft
            val nowMd = controller?.metadata
            val nowState = controller?.playbackState
            if (nowMd != null && keyOf(nowMd) == keyAtSchedule &&
                nowState?.state == PlaybackState.STATE_PLAYING
            ) {
                speaker?.announce(title, artist, TtsSpeaker.Position.END, Prefs.language(this))
            }
        }
        endRunnable = r
        handler.postDelayed(r, delay)
    }

    private fun cancelEndAnnouncement() {
        endRunnable?.let { handler.removeCallbacks(it) }
        endRunnable = null
    }

    private fun keyOf(md: MediaMetadata): String {
        val t = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val a = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        return "$t|$a"
    }

    companion object {
        private const val TAG = "AnnouncerService"
    }
}
