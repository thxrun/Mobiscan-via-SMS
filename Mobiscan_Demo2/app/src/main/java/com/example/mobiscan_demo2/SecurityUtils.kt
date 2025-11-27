package com.example.mobiscan_demo2

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SecurityUtils {
    fun hashIdentifier(input: String?): String {
        if (input.isNullOrBlank()) return "unknown"
        if (input == "unknown" || input == "RESTRICTED") return input
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(digest, Base64.NO_WRAP)
        } catch (e: Exception) {
            "unknown"
        }
    }

    fun maskIdentifier(input: String?): String {
        if (input.isNullOrBlank()) return "unknown"
        if (input == "unknown" || input == "RESTRICTED") return input
        val visible = 4
        val length = input.length
        val prefix = input.take(visible)
        val suffix = input.takeLast(visible)
        return "$prefix…$suffix"
    }
}
