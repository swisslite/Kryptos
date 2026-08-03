package com.kryptos.android

import com.kryptos.android.core.CipherException
import com.kryptos.android.core.ImageStego
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageStegoTests {
    private val width = 128
    private val height = 128

    private fun photo(seed: Long = 7L): ByteArray {
        var state = seed
        fun next(): Byte {
            state = state * 6364136223846793005L + 1442695040888963407L
            return ((state ushr 33) and 0xFF).toByte()
        }
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = (y * width + x) * 4
                if (x < width / 2) {
                    pixels[base] = 120
                    pixels[base + 1] = 130.toByte()
                    pixels[base + 2] = 140.toByte()
                } else {
                    pixels[base] = next()
                    pixels[base + 1] = next()
                    pixels[base + 2] = next()
                }
                pixels[base + 3] = 255.toByte()
            }
        }
        return pixels
    }

    @Test
    fun hideRevealRoundTrip() {
        val cover = photo()
        val message = "совершенно секретное сообщение 🕵️".toByteArray(Charsets.UTF_8)
        val stego = ImageStego.hide(message, "correct horse", cover, width, height)
        assertArrayEquals(message, ImageStego.reveal(stego, width, height, "correct horse"))
    }

    @Test
    fun revealsIosStegoImage() {
        val cover = photo()
        val stego = cover.copyOf()
        for (entry in IOS_STEGO_DIFF.split(",")) {
            val parts = entry.split(":")
            stego[parts[0].toInt()] = parts[1].toInt().toByte()
        }
        assertEquals("KX-STEGO-TEST", String(ImageStego.reveal(stego, width, height, "stego pw"), Charsets.UTF_8))
    }

    @Test
    fun candidateSetSurvivesEmbedding() {
        val cover = photo()
        val stego = ImageStego.hide(ByteArray(200) { it.toByte() }, "pw", cover, width, height)
        assertArrayEquals(
            ImageStego.candidates(cover, width, height),
            ImageStego.candidates(stego, width, height),
        )
    }

    @Test
    fun onlyBlueChannelChangesAndOnlyByOne() {
        val cover = photo()
        val stego = ImageStego.hide("hello".toByteArray(Charsets.UTF_8), "pw", cover, width, height)
        var changed = 0
        for (i in cover.indices) {
            if (cover[i] == stego[i]) continue
            changed++
            assertEquals(2, i % 4)
            val delta = kotlin.math.abs((cover[i].toInt() and 0xFF) - (stego[i].toInt() and 0xFF))
            assertEquals(1, delta)
        }
        assertTrue(changed > 0)
    }

    @Test
    fun flatRegionIsNeverTouched() {
        val cover = photo()
        val stego = ImageStego.hide(ByteArray(120) { (it * 3).toByte() }, "pw", cover, width, height)
        for (y in 0 until height) {
            for (x in 0 until width / 2) {
                val base = (y * width + x) * 4
                assertEquals(cover[base + 2], stego[base + 2])
            }
        }
    }

    @Test
    fun wrongPasswordFails() {
        val cover = photo()
        val stego = ImageStego.hide("секрет".toByteArray(Charsets.UTF_8), "right", cover, width, height)
        try {
            ImageStego.reveal(stego, width, height, "wrong")
            throw AssertionError("expected failure")
        } catch (e: CipherException) {
            assertEquals(CipherException.Kind.DECRYPTION_FAILED, e.kind)
        }
    }

    @Test
    fun cleanPhotoYieldsNoMessage() {
        try {
            ImageStego.reveal(photo(99L), width, height, "pw")
            throw AssertionError("expected failure")
        } catch (e: CipherException) {
            assertEquals(CipherException.Kind.DECRYPTION_FAILED, e.kind)
        }
    }

    @Test
    fun twoRunsProduceDifferentCarriers() {
        val cover = photo()
        val message = "одно и то же".toByteArray(Charsets.UTF_8)
        val a = ImageStego.hide(message, "pw", cover, width, height)
        val b = ImageStego.hide(message, "pw", cover, width, height)
        assertNotEquals(a.toList(), b.toList())
        assertArrayEquals(message, ImageStego.reveal(a, width, height, "pw"))
        assertArrayEquals(message, ImageStego.reveal(b, width, height, "pw"))
    }

    @Test
    fun capacityIsEnforced() {
        val cover = photo()
        val capacity = ImageStego.capacity(cover, width, height)
        assertTrue(capacity > 0)
        try {
            val noise = ByteArray(capacity + 64).also { java.util.Random(1).nextBytes(it) }
            ImageStego.hide(noise, "pw", cover, width, height)
            throw AssertionError("expected failure")
        } catch (e: CipherException) {
            assertEquals(CipherException.Kind.STEGO_CAPACITY_EXCEEDED, e.kind)
        }
    }

    @Test
    fun nearCapacityRoundTrip() {
        val cover = photo()
        val capacity = ImageStego.capacity(cover, width, height)
        assertTrue(capacity > 0)
        val message = ByteArray(capacity).also { java.util.Random(3).nextBytes(it) }
        val stego = ImageStego.hide(message, "pw", cover, width, height)
        assertArrayEquals(message, ImageStego.reveal(stego, width, height, "pw"))
        assertArrayEquals(
            ImageStego.candidates(cover, width, height),
            ImageStego.candidates(stego, width, height),
        )
    }

    @Test
    fun rejectsMalformedBuffer() {
        try {
            ImageStego.hide("x".toByteArray(), "pw", ByteArray(10), width, height)
            throw AssertionError("expected failure")
        } catch (e: CipherException) {
            assertEquals(CipherException.Kind.INVALID_INPUT, e.kind)
        }
    }

    private companion object {
        const val IOS_STEGO_DIFF = "1862:244,1930:24,1950:92,2974:10,3026:24,3574:250,3918:85,4954:226,4974:166,5026:239,5030:" +
            "248,5614:51,5934:69,6086:183,6406:10,6550:79,7010:91,7134:62,7526:202,7610:85,8086:154,846" +
            "2:162,9130:182,9138:68,9498:59,9698:153,10502:149,10650:250,10678:105,10742:218,10746:136," +
            "11242:49,12150:19,12182:79,12622:2,13266:150,13702:105,13710:235,13802:27,14302:84,14674:1" +
            "80,14826:7,15126:81,15230:113,15726:184,16198:230,16294:36,16822:24,17314:200,17738:217,17" +
            "806:175,17890:82,18222:99,18358:203,18906:15,19266:49,20238:240,20826:25,20894:111,20910:1" +
            "32,21402:48,21494:148,22006:230,22382:64,23538:173,23542:235,24886:50,25362:45,25398:81,26" +
            "038:4,26070:169,26534:46,26966:78,26994:61,27394:125,27454:243,27582:56,27974:156,29070:89" +
            ",29134:67,29474:241,29546:149,29646:123,29658:172,29678:117,30606:3,30686:192,30994:85,312" +
            "02:233,31738:135,32170:154,32538:76,33126:176,33622:123,33666:68,33750:141,34050:179,34562" +
            ":103,34810:208,35186:201,35210:182,35810:70,36138:216,36190:227,36690:216,36766:142,37174:" +
            "21,37286:86,37366:127,37782:172,37794:147,38170:204,38226:132,38834:2,39250:11,39298:33,40" +
            "226:61,40382:30,40386:252,40882:196,40934:174,41314:51,41798:222,41838:137,41950:157,42366" +
            ":99,42442:62,42802:214,42938:124,43290:132,43422:27,43486:48,43786:250,43974:169,44402:28," +
            "44826:217,44942:62,44974:119,45402:241,45410:180,45458:187,46862:184,46902:38,47030:155,47" +
            "086:94,47558:173,47886:107,47890:95,48014:233,48022:192,48426:219,48518:195,49058:62,49546" +
            ":93,50054:49,50530:130,50678:219,50946:249,51058:180,53014:79,53610:198,54266:173,55106:18" +
            "6,55290:215,55566:92,55578:99,55718:14,56226:95,56290:80,56734:199,57242:201,57722:1,58238" +
            ":141,58826:187,59138:120,59306:251,60266:206,60922:219,61206:138,61278:111,61414:174,61738" +
            ":81,61902:97,61922:163,62390:147,62442:81,62742:212,62862:223,63762:186,64966:29,64974:208"
    }
}
