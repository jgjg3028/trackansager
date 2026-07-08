package de.jg.trackansager

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
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
 * Überwacht ALLE aktiven MediaSessions (Amazon Music, Spotify, YouTube Music, ...)
 * und folgt automatisch derjenigen, die gerade spielt. Bei jedem Titelwechsel
 * werden Titel und Interpret per TTS angesagt — je nach Einstellung am Anfang
 * und/oder kurz vor dem Ende des Songs.
 */
class AnnouncerService : NotificationListenerService() {

    private lateinit var sessionManager: MediaSessionManager
    private var speaker: TtsSpeaker? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Alle Sessions, bei denen wir gerade lauschen */
    private val watched = mutableMapOf<MediaSession.Token, Watched>()

    /** Die Session, die zuletzt gespielt hat — auf sie beziehen sich die Ansagen */
    private var currentToken: MediaSession.Token? = null

    private var lastAnnouncedKey: String? = null
    private var endRunnable: Runnable? = null

    private val amazonPackages = setOf("com.amazon.mp3", "com.amazon.music")

    private data class Watched(
        val controller: MediaController,
        val callback: MediaController.Callback
    )

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            syncWatched(controllers ?: emptyList())
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener verbunden")
        speaker = TtsSpeaker(this)
        sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, AnnouncerService::class.java)
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component)
            syncWatched(sessionManager.getActiveSessions(component))
            // Falls beim Start schon etwas läuft: Titel merken, aber nicht sofort ansagen
            watched.values.firstOrNull {
                it.controller.playbackState?.state == PlaybackState.STATE_PLAYING
            }?.let {
                currentToken = it.controller.sessionToken
                it.controller.metadata?.let { md -> lastAnnouncedKey = keyOf(md) }
                scheduleEndAnnouncement()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Keine Berechtigung für MediaSessions", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        cancelEndAnnouncement()
        watched.values.forEach { it.controller.unregisterCallback(it.callback) }
        watched.clear()
        currentToken = null
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        speaker?.shutdown()
        speaker = null
    }

    /** Callback-Registrierung mit der aktuellen Session-Liste abgleichen */
    private fun syncWatched(controllers: List<MediaController>) {
        val activeTokens = controllers.map { it.sessionToken }.toSet()

        // Verschwundene Sessions abmelden
        val gone = watched.keys.filter { it !in activeTokens }
        gone.forEach { token ->
            watched.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
            if (token == currentToken) {
                cancelEndAnnouncement()
                currentToken = null
            }
        }

        // Neue Sessions anmelden
        controllers.forEach { c ->
            if (c.sessionToken !in watched) {
                val cb = makeCallback(c)
                c.registerCallback(cb, handler)
                watched[c.sessionToken] = Watched(c, cb)
                Log.i(TAG, "Beobachte MediaSession von ${c.packageName}")
            }
        }
    }

    /** Reagiert die App auf diese Musik-App? */
    private fun isEligible(c: MediaController): Boolean {
        return if (Prefs.amazonOnly(this)) c.packageName in amazonPackages else true
    }

    private fun makeCallback(c: MediaController) = object : MediaController.Callback() {

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (!isEligible(c)) return
            if (c.playbackState?.state != PlaybackState.STATE_PLAYING) return
            currentToken = c.sessionToken
            handleMetadata(c, metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state?.state == PlaybackState.STATE_PLAYING && isEligible(c)) {
                if (currentToken != c.sessionToken) {
                    // Der Nutzer hat zu einer anderen Musik-App gewechselt
                    currentToken = c.sessionToken
                    handleMetadata(c, c.metadata)
                } else {
                    // Gleiche App: Play/Pause/Seek -> End-Ansage neu terminieren
                    scheduleEndAnnouncement()
                }
            } else if (currentToken == c.sessionToken) {
                // Aktuelle App pausiert/stoppt -> geplante End-Ansage verwerfen
                scheduleEndAnnouncement()
            }
        }

        override fun onSessionDestroyed() {
            watched.remove(c.sessionToken)?.let { it.controller.unregisterCallback(it.callback) }
            if (currentToken == c.sessionToken) {
                cancelEndAnnouncement()
                currentToken = null
            }
        }
    }

    private fun handleMetadata(c: MediaController, metadata: MediaMetadata?) {
        metadata ?: return
        val key = keyOf(metadata)
        if (key == lastAnnouncedKey) {
            scheduleEndAnnouncement()
            return
        }
        lastAnnouncedKey = key

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""

        Log.i(TAG, "Neuer Titel (${c.packageName}): $title – $artist")

        if (Prefs.enabled(this) && Prefs.announceStart(this)) {
            handler.postDelayed({
                speaker?.announce(title, artist, TtsSpeaker.Position.START, Prefs.language(this))
            }, 800)
        }

        scheduleEndAnnouncement()
    }

    /** Plant die Ansage kurz vor dem Songende für die aktuell spielende Session. */
    private fun scheduleEndAnnouncement() {
        cancelEndAnnouncement()

        if (!Prefs.enabled(this)) return
        if (!Prefs.announceEnd(this)) return
        val token = currentToken ?: return
        val c = watched[token]?.controller ?: return
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
        if (delay < 1500) return

        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val keyAtSchedule = keyOf(md)

        val r = Runnable {
            val nowC = currentToken?.let { watched[it]?.controller }
            val nowMd = nowC?.metadata
            val nowState = nowC?.playbackState
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
