package com.delivery.app.data

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object SetupConfig {

    private const val ITERATIONS = 600_000
    private const val KEY_LENGTH = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    private val SALT = byteArrayOf(
        0x2A, 0x3B.toByte(), 0x4C, 0x5D.toByte(), 0x6E, 0x7F.toByte(),
        0x10, 0x21, 0x32, 0x43, 0x54, 0x65.toByte(),
        0x76, 0x87.toByte(), 0x98.toByte(), 0xA9.toByte()
    )

    private const val SETUP_KEY_HASH_HEX = "7ac3c5cc0fb2510140c7d1861db2e8d3d65f223bbd23fd254d584c81ab07b109"

    fun verifySetupKey(input: String): Boolean {
        val hash = hashKey(input.trim())
        return hash == SETUP_KEY_HASH_HEX
    }

    fun hashKey(key: String): String {
        val spec = PBEKeySpec(key.toCharArray(), SALT, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        val hash = factory.generateSecret(spec).encoded
        return hash.joinToString("") { "%02x".format(it) }
    }
}
