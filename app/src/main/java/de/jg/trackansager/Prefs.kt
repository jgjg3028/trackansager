package de.jg.trackansager

import android.content.Context
import android.content.SharedPreferences

/**
 * Zentrale Einstellungen der App, gespeichert in SharedPreferences.
 * Wird sowohl von der MainActivity (UI) als auch vom AnnouncerService gelesen.
 */
object Prefs {

    const val LANG_AUTO = "auto"
    const val LANG_DE = "de"
    const val LANG_EN = "en"

    private const val FILE = "trackansager_prefs"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Ansage beim Start eines neuen Songs */
    fun announceStart(ctx: Context): Boolean = sp(ctx).getBoolean("announce_start", true)
    fun setAnnounceStart(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("announce_start", v).apply()

    /** Ansage kurz vor Ende des Songs */
    fun announceEnd(ctx: Context): Boolean = sp(ctx).getBoolean("announce_end", true)
    fun setAnnounceEnd(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("announce_end", v).apply()

    /** Vorlauf in Sekunden: wie viele Sekunden vor Songende die End-Ansage startet */
    fun endLeadSeconds(ctx: Context): Int = sp(ctx).getInt("end_lead_seconds", 8)
    fun setEndLeadSeconds(ctx: Context, v: Int) = sp(ctx).edit().putInt("end_lead_seconds", v).apply()

    /** Ansagesprache: auto / de / en */
    fun language(ctx: Context): String = sp(ctx).getString("language", LANG_AUTO) ?: LANG_AUTO
    fun setLanguage(ctx: Context, v: String) = sp(ctx).edit().putString("language", v).apply()

    /** Nur Amazon Music beachten (true) oder jede Musik-App (false) */
    fun amazonOnly(ctx: Context): Boolean = sp(ctx).getBoolean("amazon_only", false)
    fun setAmazonOnly(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("amazon_only", v).apply()
}
