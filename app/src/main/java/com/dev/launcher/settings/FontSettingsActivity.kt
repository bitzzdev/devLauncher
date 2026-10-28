package com.dev.launcher.settings

import android.app.Activity
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.dev.launcher.FontManager
import com.dev.launcher.R
import com.dev.launcher.data.Prefs

class FontSettingsActivity : AppCompatActivity() {
    private lateinit var btnPick: Button
    private lateinit var btnReset: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPreview: TextView

    private val pickFont = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { applyFontFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_font_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.import_custom_font)

        btnPick = findViewById(R.id.btn_pick_font)
        btnReset = findViewById(R.id.btn_reset_font)
        tvStatus = findViewById(R.id.tv_font_status)
        tvPreview = findViewById(R.id.tv_font_preview)

        btnPick.setOnClickListener {
            pickFont.launch("*/*")
        }

        btnReset.setOnClickListener {
            resetFont()
        }

        updateUi()
        FontManager.applyToView(findViewById(android.R.id.content), this)
    }

    private fun updateUi() {
        val prefs = Prefs(this)
        val hasCustom = FontManager.hasCustomFontFile(this)
        if (hasCustom) {
            val fontName = prefs.customFontName?.ifEmpty { "custom_font.ttf" } ?: "custom_font.ttf"
            tvStatus.text = "Current: $fontName"
            btnReset.visibility = View.VISIBLE
            val tf = FontManager.load(this)
            tvPreview.typeface = tf ?: Typeface.DEFAULT
        } else {
            tvStatus.text = getString(R.string.import_font_tip)
            btnReset.visibility = View.GONE
            tvPreview.typeface = Typeface.DEFAULT
        }
    }

    private fun resetFont() {
        FontManager.resetCustomFont(this)
        updateUi()
        setResult(Activity.RESULT_OK)
        Toast.makeText(this, R.string.font_removed, Toast.LENGTH_SHORT).show()
    }

    private fun applyFontFromUri(uri: Uri) {
        val success = FontManager.saveCustomFont(this, uri)
        if (success) {
            updateUi()
            setResult(Activity.RESULT_OK)
            Toast.makeText(this, R.string.font_applied, Toast.LENGTH_SHORT).show()
            FontManager.applyToView(findViewById(android.R.id.content), this)
        } else {
            Toast.makeText(this, R.string.invalid_font_file, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
