package com.kryptos.android.signal

import com.kryptos.android.core.CipherException
import com.kryptos.android.core.Deflate
import com.kryptos.android.core.SmartTextStego
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
        smart: Boolean = false,
        pad: Boolean = false,
    ): String {
        val addr = SignalProtocolAddress(toFingerprint, 1)
        val myAddr = SignalProtocolAddress(myFingerprint, 1)
        val cipher = SessionCipher(store, myAddr, addr)

        if (stego != null) {
            val ct = cipher.encrypt(text.toByteArray(Charsets.UTF_8))
            val serialized = ct.serialize()
            val payload = ByteArray(2 + serialized.size)
            payload[0] = SIGNAL_PREFIX.toByte()
            payload[1] = ct.type.toByte()
            serialized.copyInto(payload, 2)
            if (payload.size <= TextStego.MAX_PAYLOAD_BYTES) {
                return if (smart) SmartTextStego.encode(payload, stego) else TextStego.encode(payload, stego)
            }
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

        WireFormat.unwrap(armored, pairKey(myFingerprint, fromFingerprint))?.let { u ->
            val raw = signalDecrypt(cipher, u.type, u.body)
            val data = if (u.deflate) Deflate.decompress(raw) ?: ByteArray(0) else raw
            return String(data, Charsets.UTF_8)
        }

        val payload = TextStego.decode(armored)
            ?: SmartTextStego.decode(armored)
            ?: throw CipherException(CipherException.Kind.NOT_A_KRYPTOS_MESSAGE)
        if (payload.size < 2 || (payload[0].toInt() and 0xFF) != SIGNAL_PREFIX) {
            throw CipherException(CipherException.Kind.NOT_A_KRYPTOS_MESSAGE)
        }
        val plain = signalDecrypt(cipher, payload[1].toInt() and 0xFF, payload.copyOfRange(2, payload.size))
        return String(plain, Charsets.UTF_8)
    }

    private fun signalDecrypt(cipher: SessionCipher, type: Int, body: ByteArray): ByteArray =
        if (type == CiphertextMessage.PREKEY_TYPE) {
            cipher.decrypt(PreKeySignalMessage(body))
        } else {
            cipher.decrypt(SignalMessage(body))
        }
}
