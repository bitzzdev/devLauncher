package com.dev.launcher

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.dev.launcher.data.Constants
import com.dev.launcher.data.Prefs
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object FontManager {
    @Volatile
    private var cachedTypeface: Typeface? = null
    @Volatile
    private var cachedPath: String? = null

    fun isCustomFontActive(context: Context): Boolean {
        val prefs = Prefs(context)
        return prefs.fontSelection == Constants.Font.CUSTOM
    }

    fun hasCustomFontFile(context: Context): Boolean {
        val prefs = Prefs(context)
        val path = prefs.customFontPath
        if (path.isNullOrEmpty()) return false
        val file = File(path)
        return file.exists() && file.length() > 0
    }

    fun load(context: Context): Typeface? {
        val prefs = Prefs(context)
        if (prefs.fontSelection != Constants.Font.CUSTOM) {
            return null
        }
        val path = prefs.customFontPath
        if (path.isNullOrEmpty()) return null

        val currentTf = cachedTypeface
        if (currentTf != null && cachedPath == path) {
            return currentTf
        }

        val file = File(path)
        return if (file.exists() && file.length() > 0) {
            try {
                val tf = Typeface.createFromFile(file)
                if (tf != null) {
                    cachedTypeface = tf
                    cachedPath = path
                }
                tf
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else null
    }

    fun applyToView(root: View?, context: Context) {
        if (root == null) return
        val tf = load(context) ?: return
        applyRecursively(root, tf)
    }

    fun applyRecursively(v: View, tf: Typeface) {
        if (v is TextView) {
            v.typeface = tf
        }
        if (v is ViewGroup) {
            v.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    if (child != null) applyRecursively(child, tf)
                }
                override fun onChildViewRemoved(parent: View?, child: View?) {}
            })
            for (i in 0 until v.childCount) {
                applyRecursively(v.getChildAt(i), tf)
            }
        }
    }

    fun saveCustomFont(context: Context, uri: Uri): Boolean {
        var inputStream: InputStream? = null
        try {
            var fileName = "custom_font.ttf"
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val name = cursor.getString(nameIndex)
                        if (!name.isNullOrBlank()) fileName = name
                    }
                }
            } catch (_: Exception) {}

            val fontsDir = File(context.filesDir, "fonts")
            if (!fontsDir.exists()) fontsDir.mkdirs()

            val ext = when {
                fileName.endsWith(".otf", ignoreCase = true) -> ".otf"
                fileName.endsWith(".ttc", ignoreCase = true) -> ".ttc"
                else -> ".ttf"
            }
            val tempFile = File(fontsDir, "temp_font$ext")
            val finalFile = File(fontsDir, "custom_font$ext")

            inputStream = context.contentResolver.openInputStream(uri) ?: return false
            FileOutputStream(tempFile).use { out ->
                inputStream?.copyTo(out)
            }

            if (tempFile.length() < 100) {
                tempFile.delete()
                return false
            }

            // Test if Android can parse this font file
            val testTf = try {
                Typeface.createFromFile(tempFile)
            } catch (e: Exception) {
                null
            }

            if (testTf == null) {
                tempFile.delete()
                return false
            }

            // Remove previous font files
            fontsDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("custom_font") || file.name.startsWith("temp_font")) {
                    if (file.absolutePath != tempFile.absolutePath) {
                        file.delete()
                    }
                }
            }

            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            val prefs = Prefs(context)
            prefs.customFontPath = finalFile.absolutePath
            prefs.customFontName = fileName
            prefs.fontSelection = Constants.Font.CUSTOM
            clear()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {}
        }
    }

    fun resetCustomFont(context: Context) {
        val prefs = Prefs(context)
        val fontsDir = File(context.filesDir, "fonts")
        if (fontsDir.exists()) {
            fontsDir.listFiles()?.forEach { it.delete() }
        }
        prefs.customFontPath = ""
        prefs.customFontName = ""
        prefs.fontSelection = Constants.Font.DEFAULT
        clear()
    }

    fun clear() {
        cachedTypeface = null
        cachedPath = null
    }
}
