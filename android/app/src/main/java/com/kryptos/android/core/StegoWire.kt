package com.kryptos.android.core

object StegoWire {
    const val PREFIX = 0x03
    private const val TYPE_MASK = 0x0F
    private const val DEFLATE_FLAG = 0x10
    private const val PADDED_FLAG = 0x20
    private const val KNOWN_FLAGS = TYPE_MASK or DEFLATE_FLAG or PADDED_FLAG

    class Framed(val type: Int, val deflate: Boolean, val body: ByteArray)

    fun carriesUnknownFlags(payload: ByteArray): Boolean {
        if (payload.size < 2 || (payload[0].toInt() and 0xFF) != PREFIX) return false
        return (payload[1].toInt() and 0xFF) and KNOWN_FLAGS.inv() != 0
    }

    fun payloadSize(ciphertext: Int, padded: Boolean): Int =
        2 + (if (padded) Padding.target(4 + ciphertext) else ciphertext)

    fun fits(ciphertext: Int, padded: Boolean): Boolean =
        payloadSize(ciphertext, padded) <= TextStego.MAX_PAYLOAD_BYTES

    fun frame(ciphertext: ByteArray, type: Int, deflate: Boolean, padded: Boolean): ByteArray {
        val body = if (padded) Padding.frame(ciphertext) else ciphertext
        val payload = ByteArray(2 + body.size)
        payload[0] = PREFIX.toByte()
        payload[1] = ((type and TYPE_MASK) or
            (if (deflate) DEFLATE_FLAG else 0) or
            (if (padded) PADDED_FLAG else 0)).toByte()
        body.copyInto(payload, 2)
        return payload
    }

    fun unframe(payload: ByteArray): Framed? {
        if (payload.size < 2 || (payload[0].toInt() and 0xFF) != PREFIX) return null
        val flags = payload[1].toInt() and 0xFF
        if (flags and KNOWN_FLAGS.inv() != 0) return null
        var body = payload.copyOfRange(2, payload.size)
        if (flags and PADDED_FLAG != 0) {
            body = Padding.unframe(body) ?: return null
        }
        return Framed(flags and TYPE_MASK, flags and DEFLATE_FLAG != 0, body)
    }
}
