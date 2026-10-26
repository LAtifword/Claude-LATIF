package com.latifbrain.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.graphics.Color
import android.widget.*
import android.view.Gravity
import java.util.Locale
import java.io.File
import java.util.UUID

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var languages: Spinner
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.rgb(248, 249, 252))
        }
        layout.addView(TextView(this).apply {
            text = "LATIF BRAIN"
            textSize = 28f
            setTextColor(Color.rgb(20, 40, 70))
        })
        layout.addView(TextView(this).apply { text = "Offline audiobook reader • Arabic / English" })
        languages = Spinner(this)
        languages.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("العربية", "English"))
        layout.addView(languages)
        editor = EditText(this).apply {
            hint = "Paste or import text / ألصق النص أو استورده"
            gravity = Gravity.TOP
            minLines = 8
        }
        layout.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Import UTF-8 text") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "text/plain"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, 201)
        }
        button("Read aloud") { speakBook() }
        button("Stop") {
            if (ready) tts.stop()
            status.text = "Stopped"
        }
        button("Export short passage as WAV") {
            if (!ready || editor.text.isBlank()) {
                status.text = "Speech engine not ready or text is empty"
            } else {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "audio/wav"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, "latif-brain.wav")
                }, 202)
            }
        }
        status = TextView(this).apply { text = "Initializing speech engine..." }
        layout.addView(status)
        setContentView(layout)
        tts = TextToSpeech(this, this)
    }

    override fun onInit(result: Int) {
        ready = result == TextToSpeech.SUCCESS
        status.text = if (ready) "Speech engine ready" else "Install an Android TTS engine and offline voice"
    }

    private fun configureVoice(): Boolean {
        val locale = if (languages.selectedItemPosition == 0) Locale("ar") else Locale.ENGLISH
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            status.text = "Selected offline voice is unavailable"
            return false
        }
        return true
    }

    private fun speakBook() {
        if (!ready || editor.text.isBlank() || !configureVoice()) return
        tts.stop()
        val max = TextToSpeech.getMaxSpeechInputLength() - 100
        editor.text.toString().chunked(max).forEachIndexed { index, part ->
            val queue = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(part, queue, null, "part-$index-${UUID.randomUUID()}") != TextToSpeech.SUCCESS) {
                status.text = "Narration queue error at part $index"
                return
            }
        }
        status.text = "Narration queued"
    }

    @Deprecated("Using Android's built-in document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            201 -> try {
                val text = contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
                editor.setText(text)
                status.text = "Imported ${text.length} characters"
            } catch (e: Exception) {
                status.text = "Import failed: ${e.message}"
            }
            202 -> {
                if (!ready || !configureVoice()) return
                val text = editor.text.toString()
                if (text.length >= TextToSpeech.getMaxSpeechInputLength() - 100) {
                    status.text = "WAV export supports passages shorter than ${TextToSpeech.getMaxSpeechInputLength() - 100} characters"
                    return
                }
                val file = File(cacheDir, "narration-${UUID.randomUUID()}.wav")
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) {
                        runOnUiThread { status.text = "Synthesizing..." }
                    }
                    override fun onError(id: String) {
                        file.delete()
                        runOnUiThread { status.text = "Synthesis failed" }
                    }
                    override fun onDone(id: String) {
                        try {
                            contentResolver.openOutputStream(uri, "w")!!.use { output ->
                                file.inputStream().use { input -> input.copyTo(output) }
                            }
                            runOnUiThread { status.text = "WAV exported" }
                        } catch (e: Exception) {
                            runOnUiThread { status.text = "Export failed: ${e.message}" }
                        } finally { file.delete() }
                    }
                })
                if (tts.synthesizeToFile(text, null, file, "wav-${UUID.randomUUID()}") != TextToSpeech.SUCCESS) {
                    status.text = "Speech engine rejected export"
                }
            }
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
