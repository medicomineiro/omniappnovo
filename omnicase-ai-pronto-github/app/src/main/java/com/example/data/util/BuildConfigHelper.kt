package com.example.data.util

import com.example.BuildConfig

object BuildConfigHelper {
    fun getInjectedGeminiKey(): String {
        return try {
            val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
            val key = field.get(null) as? String
            if (key != null && key.isNotBlank() && key != "MY_GEMINI_API_KEY") key else ""
        } catch (_: Throwable) {
            ""
        }
    }

    fun hasInjectedGeminiKey(): Boolean = getInjectedGeminiKey().isNotBlank()
}
