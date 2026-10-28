package com.dev.launcher.settings

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.dev.launcher.FontManager
import com.dev.launcher.R
import com.dev.launcher.data.Constants
import com.dev.launcher.data.Prefs
import java.io.File
import java.io.FileOutputStream

class FontSettingsActivity : AppCompatActivity() {
    private lateinit var btnPick: Button
    private lateinit var btnReset: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPreview: TextView

    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
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
            pickFont.launch(arrayOf("*/*", "font/*", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt"))
        }

        btnReset.setOnClickListener {
            resetFont()
        }

        updateUi()
    }

    private fun updateUi() {
        val prefs = Prefs(this)
        val hasCustom = FontManager.hasCustomFontFile(this)
        if (hasCustom) {
            val fontName = prefs.customFontName?.ifEmpty { "custom_font.ttf" } ?: "custom_font.ttf"
            tvStatus.text = "Current: $fontName"
            btnReset.visibility = View.VISIBLE
            try {
                val tf = Typeface.createFromFile(File(prefs.customFontPath!!))
                tvPreview.typeface = tf
            } catch (e: Exception) {
                tvPreview.typeface = Typeface.DEFAULT
            }
        } else {
            tvStatus.text = getString(R.string.import_font_tip)
            btnReset.visibility = View.GONE
            tvPreview.typeface = Typeface.DEFAULT
        }
    }

    private fun resetFont() {
        val prefs = Prefs(this)
        prefs.customFontPath = ""
        prefs.customFontName = ""
        prefs.fontSelection = Constants.Font.DEFAULT
        FontManager.clear()
        updateUi()
        Toast.makeText(this, R.string.font_removed, Toast.LENGTH_SHORT).show()
    }

    private fun applyFontFromUri(uri: Uri) {
        try {
            var fileName = "custom_font.ttf"
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex) ?: "custom_font.ttf"
                    }
                }
            } catch (ignored: Exception) {}

            val input = contentResolver.openInputStream(uri) ?: run {
                Toast.makeText(this, R.string.invalid_font_file, Toast.LENGTH_SHORT).show()
                return
            }

            val fontsDir = File(filesDir, "fonts").apply { if (!exists()) mkdirs() }
            val outFile = File(fontsDir, "custom_font.ttf")
            FileOutputStream(outFile).use { out -> input.copyTo(out) }

            // Validate that Android can parse this font
            try {
                val testTf = Typeface.createFromFile(outFile)
                if (testTf == null) throw Exception("Could not create typeface")
            } catch (e: Exception) {
                outFile.delete()
                Toast.makeText(this, R.string.invalid_font_file, Toast.LENGTH_SHORT).show()
                return
            }

            val prefs = Prefs(this)
            prefs.customFontPath = outFile.absolutePath
            prefs.customFontName = fileName
            prefs.fontSelection = Constants.Font.CUSTOM
            FontManager.clear()
            updateUi()
            Toast.makeText(this, R.string.font_applied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
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
