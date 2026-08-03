package com.kryptos.android.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

object Argon2id {
    const val PROFILE_VERSION: Byte = 1
    const val MEMORY_KIB = 65536
    const val ITERATIONS = 3
    const val LANES = 1
    const val MIN_SALT_LENGTH = 16

    fun derive(password: ByteArray, salt: ByteArray, length: Int): ByteArray {
        if (salt.size < MIN_SALT_LENGTH) throw CipherException(CipherException.Kind.INVALID_INPUT)
        return hash(password, salt, MEMORY_KIB, ITERATIONS, LANES, length)
    }

    fun hash(
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        lanes: Int,
        length: Int,
    ): ByteArray {
        if (length <= 0) throw CipherException(CipherException.Kind.INVALID_INPUT)
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKiB)
            .withParallelism(lanes)
            .withSalt(salt)
            .build()
        try {
            val generator = Argon2BytesGenerator()
            generator.init(params)
            val out = ByteArray(length)
            generator.generateBytes(password, out)
            return out
        } finally {
            params.clear()
        }
    }
}
