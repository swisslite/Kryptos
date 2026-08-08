package com.kryptos.android.signal

import com.kryptos.android.core.CipherException
import com.kryptos.android.core.Deflate
import com.kryptos.android.core.LetterStego
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.StegoMode
import com.kryptos.android.core.StegoWire
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
            val padded = pad && StegoWire.fits(serialized.size, true)
            val payload = StegoWire.frame(serialized, ct.type, deflate, padded)
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

    private const val MAX_STEGO_INPUT_CHARS = 1_000_000

    private fun stegoPayload(armored: String): ByteArray? {
        if (armored.length > MAX_STEGO_INPUT_CHARS) return null
        return TextStego.decode(armored) ?: SmartTextStego.decode(armored) ?: LetterStego.decode(armored)
    }

    private fun decryptStego(cipher: SessionCipher, payload: ByteArray): String {
        val framed = StegoWire.unframe(payload) ?: throw CipherException(
            if (StegoWire.carriesUnknownFlags(payload)) CipherException.Kind.UNSUPPORTED_FORMAT
            else CipherException.Kind.NOT_A_KRYPTOS_MESSAGE
        )
        val raw = signalDecrypt(cipher, framed.type, framed.body)
        val data = if (framed.deflate) Deflate.decompress(raw) ?: ByteArray(0) else raw
        return String(data, Charsets.UTF_8)
    }

    private fun signalDecrypt(cipher: SessionCipher, type: Int, body: ByteArray): ByteArray =
        if (type == CiphertextMessage.PREKEY_TYPE) {
            cipher.decrypt(PreKeySignalMessage(body))
        } else {
            cipher.decrypt(SignalMessage(body))
        }
}
