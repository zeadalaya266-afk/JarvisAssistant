package com.nexus.jarvis

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var etApiKey: TextInputEditText
    private lateinit var etTrigger: TextInputEditText
    private lateinit var etAction: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etApiKey = findViewById(R.id.etApiKey)
        etTrigger = findViewById(R.id.etTrigger)
        etAction = findViewById(R.id.etAction)

        val prefs = getSharedPreferences("JarvisPrefs", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("apiKey", ""))

        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            val key = etApiKey.text.toString()
            prefs.edit().putString("apiKey", key).apply()
            Toast.makeText(this, "API Key Saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnAddCommand).setOnClickListener {
            val trigger = etTrigger.text.toString()
            val action = etAction.text.toString()

            if (trigger.isNotEmpty() && action.isNotEmpty()) {
                prefs.edit().putString("cmd_$trigger", action).apply()
                etTrigger.text?.clear()
                etAction.text?.clear()
                Toast.makeText(this, "Command Added", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }
}