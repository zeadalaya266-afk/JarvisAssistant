package com.nexus.jarvis

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

class JarvisService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var apiKey: String
    private var isListening = false
    private val client = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        
        val prefs = getSharedPreferences("JarvisPrefs", MODE_PRIVATE)
        apiKey = prefs.getString("apiKey", "") ?: ""

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("ar"))
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    val voices = tts?.voices
                    if (voices != null) {
                        val femaleVoice = voices.firstOrNull { it.locale.language == "ar" && it.name.contains("female", ignoreCase = true) }
                        if (femaleVoice != null) tts?.voice = femaleVoice
                    }
                }
            }
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        setupSpeechListener()
        
        startForegroundService()
        startListeningLoop()
    }

    private fun startForegroundService() {
        val channelId = "JarvisChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Jarvis Assistant", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Nexus Jarvis")
            .setContentText("Background service is active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupSpeechListener() {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (isListening) Handler(Looper.getMainLooper()).postDelayed({ startListeningLoop() }, 1000)
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) processUserCommand(matches[0])
                if (isListening) startListeningLoop()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListeningLoop() {
        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
        }
        speechRecognizer?.startListening(intent)
    }

    private fun processUserCommand(text: String) {
        val prefs = getSharedPreferences("JarvisPrefs", MODE_PRIVATE)
        for ((key, value) in prefs.all) {
            if (key.startsWith("cmd_")) {
                val triggerWord = key.replace("cmd_", "")
                if (text.contains(triggerWord)) {
                    openApp(value.toString())
                    return
                }
            }
        }
        sendToGemini(text)
    }

    private fun openApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                speak("Application not found")
            }
        } catch (e: Exception) { speak("Error opening application") }
    }

    private fun sendToGemini(prompt: String) {
        if (apiKey.isEmpty()) {
            speak("Please configure the API key in settings")
            return
        }

        val systemInstruction = """
            You are Nexus, a highly advanced AI assistant. You must reply concisely without any emojis.
            
            Rule 1: If the user asks to open the app store or search for an app, reply exactly with:
            [ACTION:PLAYSTORE:App Name]
            
            Rule 2: If the user asks for a tutorial or video, reply exactly with:
            [ACTION:YOUTUBE:Search Query]
            
            Rule 3: If the user asks you to write code or create a game, reply exactly with:
            [ACTION:CODE]
            FILENAME: game.html
            EXPLANATION: A brief audio explanation of the game rules in Arabic.
            CODE:
            (Insert the full HTML/JS code here to run a playable game)
            [/ACTION:CODE]
            
            For all other normal questions, reply naturally and helpfully in Arabic.
        """.trimIndent()

        val json = """
            {
              "system_instruction": { "parts": [{"text": "$systemInstruction"}] },
              "contents": [{ "parts":[{"text": "$prompt"}] }]
            }
        """.trimIndent()

        val requestBody = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { speak("Network connection failed") }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val responseData = response.body?.string()
                        try {
                            val jsonObject = JSONObject(responseData)
                            val aiText = jsonObject.getJSONArray("candidates").getJSONObject(0)
                                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                            processAiResponse(aiText)
                        } catch (e: Exception) { speak("Error reading response") }
                    }
                }
            }
        })
    }

    private fun processAiResponse(aiText: String) {
        if (aiText.contains("[ACTION:PLAYSTORE:")) {
            val query = aiText.substringAfter("[ACTION:PLAYSTORE:").substringBefore("]").trim()
            speak("Searching the store for $query")
            openPlayStore(query)
        } 
        else if (aiText.contains("[ACTION:YOUTUBE:")) {
            val query = aiText.substringAfter("[ACTION:YOUTUBE:").substringBefore("]").trim()
            speak("Searching YouTube for $query")
            openYouTube(query)
        } 
        else if (aiText.contains("[ACTION:CODE]")) {
            handleCodeGeneration(aiText)
        }
        else {
            speak(aiText)
        }
    }

    private fun handleCodeGeneration(aiText: String) {
        try {
            val fileName = aiText.substringAfter("FILENAME:").substringBefore("EXPLANATION:").trim()
            val explanation = aiText.substringAfter("EXPLANATION:").substringBefore("CODE:").trim()
            val code = aiText.substringAfter("CODE:").substringBefore("[/ACTION:CODE]").trim()

            speak(explanation)

            val file = File(getExternalFilesDir(null), fileName)
            val outputStream = FileOutputStream(file)
            outputStream.write(code.toByteArray())
            outputStream.close()

            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            startActivity(Intent.createChooser(intent, "Open with:").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })

        } catch (e: Exception) {
            Log.e("Jarvis", "Error generating code: ${e.message}")
            speak("Error creating file")
        }
    }

    private fun openPlayStore(query: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$query"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$query"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    private fun openYouTube(query: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) { speak("Error opening YouTube") }
    }

    private fun speak(text: String) {
        if (text.contains("[ACTION:")) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JarvisUtterance")
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}