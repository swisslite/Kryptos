package com.kryptos.android.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class KeyStream(key: ByteArray, label: String) {
    private val mac: Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key, "HmacSHA256"))
    }
    private val label = label.toByteArray(Charsets.UTF_8)
    private var block = ByteArray(0)
    private var offset = 0
    private var counter = 0

    private fun refill() {
        val input = ByteArray(label.size + 4)
        label.copyInto(input, 0)
        input[label.size] = ((counter ushr 24) and 0xFF).toByte()
        input[label.size + 1] = ((counter ushr 16) and 0xFF).toByte()
        input[label.size + 2] = ((counter ushr 8) and 0xFF).toByte()
        input[label.size + 3] = (counter and 0xFF).toByte()
        block = mac.doFinal(input)
        offset = 0
        counter++
    }

    fun byte(): Int {
        if (offset >= block.size) refill()
        return block[offset++].toInt() and 0xFF
    }

    fun uint32(): Long {
        var v = 0L
        repeat(4) { v = (v shl 8) or byte().toLong() }
        return v
    }

    fun index(modulus: Int): Int {
        if (modulus <= 1) return 0
        val m = modulus.toLong()
        val bound = (4294967296L / m) * m
        var v = uint32()
        while (v >= bound) v = uint32()
        return (v % m).toInt()
    }
}

object ImageStego {
    const val CONTAINER_VERSION: Byte = 2
    const val SALT_BITS = PasswordCipher.SALT_LEN * 8
    const val LENGTH_BITS = 32
    const val SELECTION_PERCENT = 25
    const val PLACEMENT_KEY_LEN = 32
    const val MEASURE_CAP = 255

    fun candidates(rgba: ByteArray, width: Int, height: Int): IntArray {
        if (width <= 2 || height <= 2 ||
            rgba.size.toLong() != width.toLong() * height.toLong() * 4L
        ) {
            throw CipherException(CipherException.Kind.INVALID_INPUT)
        }
        val count = width * height
        val measures = ByteArray(count)
        val histogram = IntArray(MEASURE_CAP + 1)
        var interior = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val p = y * width + x
                val base = p * 4
                var m = 0
                for (c in 0 until 2) {
                    val v = rgba[base + c].toInt() and 0xFF
                    m += kotlin.math.abs(v - (rgba[(p - 1) * 4 + c].toInt() and 0xFF))
                    m += kotlin.math.abs(v - (rgba[(p + 1) * 4 + c].toInt() and 0xFF))
                    m += kotlin.math.abs(v - (rgba[(p - width) * 4 + c].toInt() and 0xFF))
                    m += kotlin.math.abs(v - (rgba[(p + width) * 4 + c].toInt() and 0xFF))
                }
                if (m > MEASURE_CAP) m = MEASURE_CAP
                measures[p] = m.toByte()
                histogram[m]++
                interior++
            }
        }
        val limit = kotlin.math.max(1, (interior.toLong() * SELECTION_PERCENT / 100).toInt())
        var threshold = MEASURE_CAP
        var running = histogram[MEASURE_CAP]
        var t = MEASURE_CAP - 1
        while (t >= 1) {
            if (running + histogram[t] > limit) break
            running += histogram[t]
            threshold = t
            t--
        }
        if (running <= 0) return IntArray(0)
        val list = IntArray(running)
        var n = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val p = y * width + x
                if ((measures[p].toInt() and 0xFF) >= threshold) {
                    list[n++] = p * 4 + 2
                }
            }
        }
        return if (n == list.size) list else list.copyOf(n)
    }

    fun publicStream(width: Int, height: Int, total: Int, label: String): KeyStream {
        val prefix = "kryptos/stego/v2/pub".toByteArray(Charsets.UTF_8)
        val seed = ByteArray(prefix.size + 12)
        prefix.copyInto(seed, 0)
        var off = prefix.size
        for (value in intArrayOf(width, height, total)) {
            seed[off] = ((value ushr 24) and 0xFF).toByte()
            seed[off + 1] = ((value ushr 16) and 0xFF).toByte()
            seed[off + 2] = ((value ushr 8) and 0xFF).toByte()
            seed[off + 3] = (value and 0xFF).toByte()
            off += 4
        }
        return KeyStream(MessageDigest.getInstance("SHA-256").digest(seed), label)
    }

    fun place(count: Int, total: Int, used: BooleanArray, stream: KeyStream): IntArray {
        if (count <= 0 || total <= 0 || count > total) {
            throw CipherException(CipherException.Kind.STEGO_CAPACITY_EXCEEDED)
        }
        val slots = IntArray(count)
        var cursor = 0
        for (i in 0 until count) {
            val start = (i.toLong() * total / count).toInt()
            var end = ((i + 1).toLong() * total / count).toInt()
            if (end <= start) end = start + 1
            if (end > total) end = total
            val span = end - start
            var idx = start + stream.index(span)
            var probes = 0
            while (used[idx] && probes < span) {
                idx = start + ((idx - start + 1) % span)
                probes++
            }
            if (used[idx]) {
                while (cursor < total && used[cursor]) cursor++
                if (cursor >= total) throw CipherException(CipherException.Kind.STEGO_CAPACITY_EXCEEDED)
                idx = cursor
            }
            used[idx] = true
            slots[i] = idx
        }
        return slots
    }

    fun apply(pixels: ByteArray, index: Int, bit: Int, stream: KeyStream) {
        val v = pixels[index].toInt() and 0xFF
        if ((v and 1) == bit) return
        if (v == 0) { pixels[index] = 1; return }
        if (v == 255) { pixels[index] = 254.toByte(); return }
        pixels[index] = (if ((stream.byte() and 1) == 0) v - 1 else v + 1).toByte()
    }

    fun capacity(rgba: ByteArray, width: Int, height: Int): Int {
        val list = try {
            candidates(rgba, width, height)
        } catch (e: CipherException) {
            return 0
        }
        val bits = list.size - SALT_BITS - LENGTH_BITS
        if (bits <= 0) return 0
        return kotlin.math.max(0, bits / 8 - PasswordCipher.TAG_LEN - 1)
    }

    fun hide(
        message: ByteArray,
        password: String,
        rgba: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray = hideInto(message, password, rgba.copyOf(), width, height)

    fun hideInto(
        message: ByteArray,
        password: String,
        rgba: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val list = candidates(rgba, width, height)
        val total = list.size
        if (total <= SALT_BITS + LENGTH_BITS) throw CipherException(CipherException.Kind.STEGO_CAPACITY_EXCEEDED)

        val salt = randomBytes(PasswordCipher.SALT_LEN)
        val km = Argon2id.derive(
            password, salt,
            PasswordCipher.DERIVED_LEN + PLACEMENT_KEY_LEN,
        )
        val key = km.copyOfRange(0, PasswordCipher.KEY_LEN)
        val nonce = km.copyOfRange(PasswordCipher.KEY_LEN, PasswordCipher.DERIVED_LEN)
        val placementKey = km.copyOfRange(PasswordCipher.DERIVED_LEN, km.size)
        km.fill(0)
        val sealed = try {
            PasswordCipher.sealBody(message, key, nonce, CONTAINER_VERSION, false)
        } finally {
            key.fill(0)
            nonce.fill(0)
        }

        try {
            val payloadBits = sealed.size * 8
            if (SALT_BITS + LENGTH_BITS + payloadBits > total) {
                throw CipherException(CipherException.Kind.STEGO_CAPACITY_EXCEEDED)
            }

            val used = BooleanArray(total)
            val saltSlots = place(SALT_BITS, total, used, publicStream(width, height, total, "slots"))
            val keyed = KeyStream(placementKey, "kryptos/stego/v2/slots")
            val lengthSlots = place(LENGTH_BITS, total, used, keyed)
            val payloadSlots = place(payloadBits, total, used, keyed)

            val mask = KeyStream(placementKey, "kryptos/stego/v2/mask")
            val maskedLength = (sealed.size.toLong() and 0xFFFFFFFFL) xor mask.uint32()

            val out = rgba
            val publicFlip = publicStream(width, height, total, "flip")
            val flip = KeyStream(placementKey, "kryptos/stego/v2/flip")

            for (i in 0 until SALT_BITS) {
                val bit = ((salt[i / 8].toInt() and 0xFF) ushr (7 - i % 8)) and 1
                apply(out, list[saltSlots[i]], bit, publicFlip)
            }
            for (i in 0 until LENGTH_BITS) {
                val bit = ((maskedLength ushr (31 - i)) and 1L).toInt()
                apply(out, list[lengthSlots[i]], bit, flip)
            }
            for (i in 0 until payloadBits) {
                val bit = ((sealed[i / 8].toInt() and 0xFF) ushr (7 - i % 8)) and 1
                apply(out, list[payloadSlots[i]], bit, flip)
            }
            return out
        } finally {
            placementKey.fill(0)
        }
    }

    fun reveal(rgba: ByteArray, width: Int, height: Int, password: String): ByteArray {
        val list = candidates(rgba, width, height)
        val total = list.size
        if (total <= SALT_BITS + LENGTH_BITS) throw CipherException(CipherException.Kind.DECRYPTION_FAILED)

        val used = BooleanArray(total)
        val saltSlots = place(SALT_BITS, total, used, publicStream(width, height, total, "slots"))
        val salt = ByteArray(PasswordCipher.SALT_LEN)
        for (i in 0 until SALT_BITS) {
            val bit = rgba[list[saltSlots[i]]].toInt() and 1
            salt[i / 8] = (salt[i / 8].toInt() or (bit shl (7 - i % 8))).toByte()
        }

        val km = Argon2id.derive(
            password, salt,
            PasswordCipher.DERIVED_LEN + PLACEMENT_KEY_LEN,
        )
        val key = km.copyOfRange(0, PasswordCipher.KEY_LEN)
        val nonce = km.copyOfRange(PasswordCipher.KEY_LEN, PasswordCipher.DERIVED_LEN)
        val placementKey = km.copyOfRange(PasswordCipher.DERIVED_LEN, km.size)
        km.fill(0)
        try {
            return revealBody(rgba, list, total, used, key, nonce, placementKey)
        } finally {
            key.fill(0)
            nonce.fill(0)
            placementKey.fill(0)
        }
    }

    private fun revealBody(
        rgba: ByteArray,
        list: IntArray,
        total: Int,
        used: BooleanArray,
        key: ByteArray,
        nonce: ByteArray,
        placementKey: ByteArray,
    ): ByteArray {
        val keyed = KeyStream(placementKey, "kryptos/stego/v2/slots")
        val lengthSlots = place(LENGTH_BITS, total, used, keyed)
        var maskedLength = 0L
        for (i in 0 until LENGTH_BITS) {
            val bit = (rgba[list[lengthSlots[i]]].toInt() and 1).toLong()
            maskedLength = maskedLength or (bit shl (31 - i))
        }
        val mask = KeyStream(placementKey, "kryptos/stego/v2/mask")
        val length = (maskedLength xor mask.uint32()).toInt()
        if (length <= PasswordCipher.TAG_LEN ||
            SALT_BITS.toLong() + LENGTH_BITS + length.toLong() * 8 > total.toLong()
        ) {
            throw CipherException(CipherException.Kind.DECRYPTION_FAILED)
        }

        val payloadSlots = place(length * 8, total, used, keyed)
        val sealed = ByteArray(length)
        for (i in 0 until length * 8) {
            val bit = rgba[list[payloadSlots[i]]].toInt() and 1
            sealed[i / 8] = (sealed[i / 8].toInt() or (bit shl (7 - i % 8))).toByte()
        }
        return PasswordCipher.openBody(sealed, key, nonce, CONTAINER_VERSION)
    }
}
