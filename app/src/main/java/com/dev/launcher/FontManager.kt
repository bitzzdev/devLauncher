package com.dev.launcher

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.dev.launcher.data.Constants
import com.dev.launcher.data.Prefs
import java.io.File

object FontManager {
    private var typeface: Typeface? = null

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
        if (typeface != null) return typeface
        val path = prefs.customFontPath
        if (path.isNullOrEmpty()) return null
        val file = File(path)
        return if (file.exists() && file.length() > 0) {
            try {
                Typeface.createFromFile(file).also { typeface = it }
            } catch (e: Exception) {
                null
            }
        } else null
    }

    fun applyToView(root: View?, context: Context) {
        if (root == null) return
        val tf = load(context) ?: return
        applyRecursively(root, tf)
    }

    private fun applyRecursively(v: View, tf: Typeface) {
        if (v is TextView) {
            v.typeface = tf
        } else if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                applyRecursively(v.getChildAt(i), tf)
            }
        }
    }

    fun clear() {
        typeface = null
    }
}
