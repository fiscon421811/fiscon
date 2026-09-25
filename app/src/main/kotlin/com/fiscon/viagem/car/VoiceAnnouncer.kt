package com.fiscon.viagem.car

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Avisos falados (radares, postos, manobras) com "ducking" do áudio do carro. */
class VoiceAnnouncer(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private var ready = false
    private var counter = 0
    var enabled = true

    private val tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale("pt", "BR")
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = abandonFocusIfIdle()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = abandonFocusIfIdle()
        })
        ready = true
    }

    fun speak(text: String) {
        if (!ready || !enabled) return
        audioManager.requestAudioFocus(focusRequest)
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "msg${counter++}")
    }

    private fun abandonFocusIfIdle() {
        if (!tts.isSpeaking) audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
