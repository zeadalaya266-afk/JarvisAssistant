package com.nexus.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var btnMic: ImageButton
    private lateinit var tts: TextToSpeech
    private val client = OkHttpClient()
    private var apiKey: String = ""

    // محرك التعرف على الصوت
    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val text = matches[0]
                addMessageToChat(text, true)
                sendToGemini(text)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatContainer = findViewById(R.id.chatContainer)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        btnMic = findViewById(R.id.btnMic)

        val prefs = getSharedPreferences("JarvisPrefs", MODE_PRIVATE)
        apiKey = prefs.getString("apiKey", "") ?: ""

        // إعداد الصوت
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("ar")
            }
        }

        addMessageToChat("مرحباً! أنا نيكسوس. كيف يمكنني مساعدتك اليوم؟", false)

        btnSend.setOnClickListener {
            val text = etMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                addMessageToChat(text, true)
                etMessage.text.clear()
                sendToGemini(text)
            }
        }

        btnMic.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startVoiceRecognition()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            }
        }
    }

    private fun startVoiceRecognition() {
        // استخدام واجهة جوجل المدمجة (أكثر أماناً وبدون أصوات مزعجة متكررة)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن...")
        }
        speechLauncher.launch(intent)
    }

    private fun addMessageToChat(message: String, isUser: Boolean) {
        val textView = TextView(this).apply {
            text = message
            textSize = 16f
            setPadding(24, 24, 24, 24)
            setTextColor(if (isUser) 0xFFFFFFFF.toInt() else 0xFFE2E8F0.toInt())
            setBackgroundColor(if (isUser) 0xFF4A90E2.toInt() else 0xFF252A34.toInt())
            
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(8, 8, 8, 16)
            params.gravity = if (isUser) android.view.Gravity.END else android.view.Gravity.START
            layoutParams = params
        }
        chatContainer.addView(textView)
        findViewById<android.widget.ScrollView>(R.id.chatScroll).post {
            findViewById<android.widget.ScrollView>(R.id.chatScroll).fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun sendToGemini(prompt: String) {
        if (apiKey.isEmpty()) {
            addMessageToChat("الرجاء إدخال مفتاح API من الإعدادات أولاً.", false)
            return
        }

        val systemInstruction = """
            أنت "نيكسوس"، مساعد ذكي ومبرمج خبير.
            القواعد:
            1. أجب باختصار وبدون إيموجي.
            2. إذا طلب المستخدم إضافة أمر جديد لفتح تطبيق (مثلاً: "أضف أمر لفتح واتساب")، يجب أن ترد بهذه الصيغة بالضبط:
            [ACTION:ADD_CMD:الكلمة_المفتاحية:اسم_الحزمة]
            مثال: [ACTION:ADD_CMD:افتح واتساب:com.whatsapp]
            3. إذا طلب منك برمجة لعبة، اكتب الكود بلغة HTML.
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
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { addMessageToChat("فشل الاتصال بالشبكة", false) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val responseData = response.body?.string()
                        try {
                            val jsonObject = JSONObject(responseData)
                            val aiText = jsonObject.getJSONArray("candidates").getJSONObject(0)
                                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                            
                            runOnUiThread {
                                handleAiResponse(aiText)
                            }
                        } catch (e: Exception) {
                            runOnUiThread { addMessageToChat("حدث خطأ في قراءة الرد", false) }
                        }
                    }
                }
            }
        })
    }

    private fun handleAiResponse(aiText: String) {
        // التحقق من ميزة "أضف أمر"
        if (aiText.contains("[ACTION:ADD_CMD:")) {
            val parts = aiText.substringAfter("[ACTION:ADD_CMD:").substringBefore("]").split(":")
            if (parts.size == 2) {
                val trigger = parts[0].trim()
                val packageName = parts[1].trim()
                
                val prefs = getSharedPreferences("JarvisPrefs", MODE_PRIVATE)
                prefs.edit().putString("cmd_$trigger", packageName).apply()
                
                addMessageToChat("تمت إضافة الأمر بنجاح! يمكنك الآن قول: $trigger", false)
                tts.speak("تمت إضافة الأمر بنجاح", TextToSpeech.QUEUE_FLUSH, null, "TTS")
                return
            }
        }

        // عرض الرد العادي
        addMessageToChat(aiText, false)
        tts.speak(aiText.replace(Regex("[*#_`]"), ""), TextToSpeech.QUEUE_FLUSH, null, "TTS")
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
    }
}
