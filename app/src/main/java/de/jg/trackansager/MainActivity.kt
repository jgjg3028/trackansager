package de.jg.trackansager

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private var testSpeaker: TtsSpeaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val switchStart = findViewById<SwitchMaterial>(R.id.switchStart)
        val switchEnd = findViewById<SwitchMaterial>(R.id.switchEnd)
        val switchAmazonOnly = findViewById<SwitchMaterial>(R.id.switchAmazonOnly)
        val radioLang = findViewById<RadioGroup>(R.id.radioLanguage)
        val seekLead = findViewById<SeekBar>(R.id.seekLead)
        val textLead = findViewById<TextView>(R.id.textLeadValue)
        val btnPermission = findViewById<Button>(R.id.btnPermission)
        val btnTest = findViewById<Button>(R.id.btnTest)

        // Aktuelle Werte laden
        switchStart.isChecked = Prefs.announceStart(this)
        switchEnd.isChecked = Prefs.announceEnd(this)
        switchAmazonOnly.isChecked = Prefs.amazonOnly(this)
        when (Prefs.language(this)) {
            Prefs.LANG_DE -> radioLang.check(R.id.radioDe)
            Prefs.LANG_EN -> radioLang.check(R.id.radioEn)
            else -> radioLang.check(R.id.radioAuto)
        }
        val lead = Prefs.endLeadSeconds(this)
        seekLead.progress = lead - 3 // Bereich 3–20 s
        textLead.text = getString(R.string.lead_seconds, lead)

        // Änderungen speichern
        switchStart.setOnCheckedChangeListener { _, v -> Prefs.setAnnounceStart(this, v) }
        switchEnd.setOnCheckedChangeListener { _, v -> Prefs.setAnnounceEnd(this, v) }
        switchAmazonOnly.setOnCheckedChangeListener { _, v -> Prefs.setAmazonOnly(this, v) }
        radioLang.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.radioDe -> Prefs.LANG_DE
                R.id.radioEn -> Prefs.LANG_EN
                else -> Prefs.LANG_AUTO
            }
            Prefs.setLanguage(this, mode)
        }
        seekLead.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress + 3
                textLead.text = getString(R.string.lead_seconds, seconds)
                if (fromUser) Prefs.setEndLeadSeconds(this@MainActivity, seconds)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnTest.setOnClickListener {
            if (testSpeaker == null) testSpeaker = TtsSpeaker(this)
            // Kurz warten, bis TTS bereit ist (bei erstem Klick)
            btnTest.postDelayed({
                testSpeaker?.speakTest(Prefs.language(this))
            }, 400)
        }

        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        testSpeaker?.shutdown()
        testSpeaker = null
    }

    private fun updatePermissionStatus() {
        val status = findViewById<TextView>(R.id.textPermissionStatus)
        val granted = isNotificationListenerEnabled()
        status.text = if (granted) {
            getString(R.string.permission_granted)
        } else {
            getString(R.string.permission_missing)
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, AnnouncerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.split(":").any {
            ComponentName.unflattenFromString(it) == cn
        }
    }
}
