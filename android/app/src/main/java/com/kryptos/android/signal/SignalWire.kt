package com.kryptos.android.signal

import com.kryptos.android.core.CachePurge
import com.kryptos.android.core.CipherException
import com.kryptos.android.core.Deflate
import com.kryptos.android.core.LetterStego
import com.kryptos.android.core.SmartTextStego
import com.kryptos.android.core.StegoMode
import com.kryptos.android.core.StegoLanguage
import com.kryptos.android.core.TextStego
import com.kryptos.android.core.WireFormat
import com.kryptos.android.core.sha256Hex
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.SignalProtocolStore

object SignalWire {

    class Sealed(val armored: String, val hidden: Boolean) {
        override fun toString(): String = "Sealed(hidden=$hidden, chars=${armored.length})"
    }

    init {
        CachePurge.register { forgetStegoMemo() }
    }

    const val MAX_MESSAGE_BYTES = 1 shl 20

    fun fitsMessage(text: String): Boolean =
        text.length <= MAX_MESSAGE_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES

    fun pairSecret(store: SignalProtocolStore, myFingerprint: String, peerFingerprint: String): ByteArray {
        val peer = SignalFormat.bytes(peerFingerprint)?.let { runCatching { ECPublicKey(it) }.getOrNull() }
            ?: throw CipherException(CipherException.Kind.INVALID_INPUT)
        val agreement = store.identityKeyPair.privateKey.calculateAgreement(peer)
        return try {
            WireFormat.pairSecret(agreement, myFingerprint, peerFingerprint)
        } finally {
            agreement.fill(0)
        }
    }

    fun encrypt(
        text: String,
        toFingerprint: String,
        myFingerprint: String,
        store: SignalProtocolStore,
        stego: StegoLanguage? = null,
        mode: StegoMode = StegoMode.WORDS,
        pad: Boolean = false,
    ): Sealed {
        if (!fitsMessage(text)) throw CipherException(CipherException.Kind.INVALID_INPUT)
        val addr = SignalProtocolAddress(toFingerprint, 1)
        val myAddr = SignalProtocolAddress(myFingerprint, 1)
        val cipher = SessionCipher(store, myAddr, addr)
        val secret = pairSecret(store, myFingerprint, toFingerprint)

        val body = Deflate.body(text)
        val ct = cipher.encrypt(body.bytes)
        val serialized = ct.serialize()

        if (stego != null) {
            val padded = pad && WireFormat.fitsStego(serialized.size, true)
            val payload = WireFormat.seal(serialized, ct.type, body.deflated, padded, secret)
            if (payload.size <= TextStego.MAX_PAYLOAD_BYTES) {
                val cover = when (mode) {
                    StegoMode.WORDS -> TextStego.encode(payload, stego)
                    StegoMode.SMART -> SmartTextStego.encode(payload, stego)
                    StegoMode.LETTERS -> LetterStego.encode(payload, stego)
                }
                if (cover != null) return Sealed(cover, true)
            }
        }

        return Sealed(WireFormat.wrap(serialized, ct.type, body.deflated, pad, secret), false)
    }

    fun decrypt(armored: String, fromFingerprint: String, myFingerprint: String, store: SignalProtocolStore): String {
        val addr = SignalProtocolAddress(fromFingerprint, 1)
        val myAddr = SignalProtocolAddress(myFingerprint, 1)
        val cipher = SessionCipher(store, myAddr, addr)
        val secret = pairSecret(store, myFingerprint, fromFingerprint)

        return when (val opened = WireFormat.unwrap(armored, secret)) {
            is WireFormat.Opened.Message ->
                try {
                    inflate(signalDecrypt(cipher, opened.type, opened.body), opened.deflate)
                } catch (e: Exception) {
                    val fallback = stegoPayload(armored) ?: throw e
                    decryptStego(cipher, fallback, secret)
                }
            WireFormat.Opened.Unsupported -> throw CipherException(CipherException.Kind.UNSUPPORTED_FORMAT)
            WireFormat.Opened.Absent -> {
                val payload = stegoPayload(armored) ?: throw CipherException(CipherException.Kind.NOT_A_KRYPTOS_MESSAGE)
                decryptStego(cipher, payload, secret)
            }
        }
    }

    private const val MAX_STEGO_INPUT_CHARS = 1_000_000

    private val memoLock = Any()
    private var memoKey: String? = null
    private var memoPayload: ByteArray? = null

    private fun forgetStegoMemo() = synchronized(memoLock) {
        memoKey = null
        memoPayload = null
    }

    private fun stegoPayload(armored: String): ByteArray? {
        if (armored.length > MAX_STEGO_INPUT_CHARS) return null
        val key = sha256Hex(armored.toByteArray(Charsets.UTF_8))
        synchronized(memoLock) {
            if (key == memoKey) return memoPayload?.copyOf()
        }
        val payload = TextStego.decode(armored) ?: SmartTextStego.decode(armored) ?: LetterStego.decode(armored)
        synchronized(memoLock) {
            memoKey = key
            memoPayload = payload?.copyOf()
        }
        return payload
    }

    private fun decryptStego(cipher: SessionCipher, payload: ByteArray, secret: ByteArray): String =
        when (val opened = WireFormat.open(payload, secret)) {
            is WireFormat.Opened.Message -> inflate(signalDecrypt(cipher, opened.type, opened.body), opened.deflate)
            WireFormat.Opened.Unsupported -> throw CipherException(CipherException.Kind.UNSUPPORTED_FORMAT)
            WireFormat.Opened.Absent -> throw CipherException(CipherException.Kind.NOT_A_KRYPTOS_MESSAGE)
        }

    private fun inflate(raw: ByteArray, deflate: Boolean): String =
        Deflate.text(raw, deflate, MAX_MESSAGE_BYTES) ?: throw CipherException(CipherException.Kind.DECRYPTION_FAILED)

    private fun signalDecrypt(cipher: SessionCipher, type: Int, body: ByteArray): ByteArray =
        if (type == CiphertextMessage.PREKEY_TYPE) {
            cipher.decrypt(PreKeySignalMessage(body))
        } else {
            cipher.decrypt(SignalMessage(body))
        }
}
