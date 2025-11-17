package com.kaj.studioreserve.auth

import at.favre.lib.crypto.bcrypt.BCrypt

interface PasswordHasher {
    fun hash(raw: String): String
    fun verify(raw: String, hash: String): Boolean
}

class BCryptPasswordHasher(private val cost: Int = 12) : PasswordHasher {
    override fun hash(raw: String): String {
        val hashed = BCrypt.withDefaults().hash(cost, raw.toCharArray())
        return String(hashed)
    }

    override fun verify(raw: String, hash: String): Boolean {
        val result = BCrypt.verifyer().verify(raw.toCharArray(), hash.toCharArray())
        return result.verified
    }
}
