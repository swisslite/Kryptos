package com.kryptos.android.core

object KryptosCore {
    fun encrypt(text: String, password: String, pad: Boolean = false): String =
        WireFormat.token(PasswordCipher.encrypt(text.toByteArray(Charsets.UTF_8), password, pad))

    fun decrypt(armored: String, password: String): String {
        val raw = WireFormat.tokenBytes(armored)
            ?: throw CipherException(CipherException.Kind.NOT_A_KRYPTOS_MESSAGE)
        return String(PasswordCipher.decrypt(raw, password), Charsets.UTF_8)
    }

    fun containsMessage(text: String): Boolean = WireFormat.isToken(text)
}
