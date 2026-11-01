package org.zeravrix.kotatsu.extension.util

import java.security.MessageDigest

object Hash {

    private val hexArray = "0123456789abcdef".toCharArray()

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hexChars = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    fun sha256(string: String): String = sha256(string.toByteArray())
}
