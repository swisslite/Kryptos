package com.kryptos.android

import com.kryptos.android.keyboard.SuggestionEngine
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SuggestionConcurrencyTests {

    @Before
    fun load() {
        SuggestionEngine.clearPersonal()
        SuggestionEngine.loadForTest(
            ruWords = listOf("привет", "правда", "проверка", "просто", "работа", "разговор"),
            enWords = listOf("hello", "help", "here", "there", "these", "through"),
            ruPairs = listOf("привет как", "просто так"),
            enPairs = listOf("hello there", "help me"),
            ruForms = "привета\nприветом\nработы\n",
            enForms = "helping\nhelped\n",
        )
    }

    @Test
    fun scoringOffTheMainThreadSurvivesConcurrentLearning() {
        val threads = 6
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failure = AtomicReference<Throwable?>(null)
        val words = listOf("привт", "прверка", "рабта", "helo", "helpp", "ther")

        repeat(threads) { index ->
            Thread {
                try {
                    start.await()
                    repeat(300) { round ->
                        val word = words[(index + round) % words.size]
                        SuggestionEngine.suggest(word.dropLast(1), null, if (index % 2 == 0) "ru" else "en")
                        SuggestionEngine.autocorrect(word, null, if (index % 2 == 0) "ru" else "en")
                        if (round % 5 == 0) SuggestionEngine.learn(word, null)
                        if (round % 50 == 0) SuggestionEngine.beginTypingSession()
                        if (round % 97 == 0) SuggestionEngine.forgetTypingSession()
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }.apply { isDaemon = true }.start()
        }

        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("scoring is not thread safe", it) }
    }

    @Test
    fun suggestionsStayCorrectWhenComputedOffThread() {
        val onMain = SuggestionEngine.suggest("прив", null, "ru")
        val result = AtomicReference<List<String>>(emptyList())
        val worker = Thread { result.set(SuggestionEngine.suggest("прив", null, "ru")) }
        worker.start()
        worker.join(10_000)
        assertTrue(onMain.isNotEmpty())
        assertTrue(onMain == result.get())
    }

    @Test
    fun beginSessionForgetsSkippedCorrections() {
        SuggestionEngine.noteUndoneCorrection("привт")
        assertTrue(SuggestionEngine.autocorrect("привт", null, "ru") == null)
        SuggestionEngine.beginTypingSession()
        assertTrue(SuggestionEngine.autocorrect("привт", null, "ru") != null)
    }
}
