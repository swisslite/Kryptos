package com.kryptos.android.signal

import com.kryptos.android.core.CipherException
import com.kryptos.android.core.Deflate
import com.kryptos.android.core.LetterStego
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.StegoMode
import com.kryptos.android.core.StegoLanguage
import com.kryptos.android.core.TextStego
import com.kryptos.android.core.WireFormat
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.SignalProtocolStore

object SignalWire {
    private const val SIGNAL_PREFIX = 0x03

    fun pairKey(a: String, b: String): ByteArray =
        (if (a <= b) a + b else b + a).toByteArray(Charsets.UTF_8)

    fun encrypt(
        text: String,
        toFingerprint: String,
        myFingerprint: String,
        store: SignalProtocolStore,
        stego: StegoLanguage? = null,
        mode: StegoMode = StegoMode.WORDS,
        pad: Boolean = false,
    ): String {
        val addr = SignalProtocolAddress(toFingerprint, 1)
        val myAddr = SignalProtocolAddress(myFingerprint, 1)
        val cipher = SessionCipher(store, myAddr, addr)

        if (stego != null) {
            val raw = text.toByteArray(Charsets.UTF_8)
            val compressed = Deflate.compress(raw)
            val deflate = compressed != null
            val ct = cipher.encrypt(if (deflate) compressed!! else raw)
            val serialized = ct.serialize()
            val payload = ByteArray(2 + serialized.size)
            payload[0] = SIGNAL_PREFIX.toByte()
            payload[1] = ((ct.type and 0x0F) or (if (deflate) 0x10 else 0)).toByte()
            serialized.copyInto(payload, 2)
            if (payload.size <= TextStego.MAX_PAYLOAD_BYTES) {
                return when (mode) {
                    StegoMode.WORDS -> TextStego.encode(payload, stego)
                    StegoMode.SMART -> SmartTextStego.encode(payload, stego)
                    StegoMode.LETTERS -> LetterStego.encode(payload, stego)
                }
            }
            return WireFormat.wrap(serialized, ct.type, deflate, pad, pairKey(myFingerprint, toFingerprint))
        }

        val plaintext = text.toByteArray(Charsets.UTF_8)
        val compressed = Deflate.compress(plaintext)
        val deflate = compressed != null
        val ct = cipher.encrypt(if (deflate) compressed!! else plaintext)
        return WireFormat.wrap(ct.serialize(), ct.type, deflate, pad, pairKey(myFingerprint, toFingerprint))
    }

    fun decrypt(armored: String, fromFingerprint: String, myFingerprint: String, store: SignalProtocolStore): String {
        val addr = SignalProtocolAddress(fromFingerprint, 1)
        val myAddr = SignalProtocolAddress(myFingerprint, 1)
        val cipher = SessionCipher(store, myAddr, addr)

        val unwrapped = WireFormat.unwrap(armored, pairKey(myFingerprint, fromFingerprint))
        if (unwrapped != null) {
            try {
                val raw = signalDecrypt(cipher, unwrapped.type, unwrapped.body)
                val data = if (unwrapped.deflate) Deflate.decompress(raw) ?: ByteArray(0) else raw
                return String(data, Charsets.UTF_8)
            } catch (e: Exception) {
                val fallback = stegoPayload(armored) ?: throw e
                return decryptStego(cipher, fallback)
            }
        }

        val payload = stegoPayload(armored) ?: throw CipherException(CipherException.Kind.NOT_A_KRYPTOS_MESSAGE)
        return decryptStego(cipher, payload)
    }

    private fun stegoPayload(armored: String): ByteArray? =
        TextStego.decode(armored) ?: SmartTextStego.decode(armored) ?: LetterStego.decode(armored)

    private fun decryptStego(cipher: SessionCipher, payload: ByteArray): String {
        if (payload.size < 2 || (payload[0].toInt() and 0xFF) != SIGNAL_PREFIX) {
            throw CipherException(CipherException.Kind.NOT_A_KRYPTOS_MESSAGE)
        }
        val flags = payload[1].toInt() and 0xFF
        val raw = signalDecrypt(cipher, flags and 0x0F, payload.copyOfRange(2, payload.size))
        val data = if (flags and 0x10 != 0) Deflate.decompress(raw) ?: ByteArray(0) else raw
        return String(data, Charsets.UTF_8)
    }

    private fun signalDecrypt(cipher: SessionCipher, type: Int, body: ByteArray): ByteArray =
        if (type == CiphertextMessage.PREKEY_TYPE) {
            cipher.decrypt(PreKeySignalMessage(body))
        } else {
            cipher.decrypt(SignalMessage(body))
        }
}
