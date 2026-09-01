package com.kryptos.android

import com.kryptos.android.keyboard.SuggestionEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TypingMemoryTests {

    @Before fun reset() = SuggestionEngine.clearPersonal()

    @After fun cleanUp() = SuggestionEngine.clearPersonal()

    @Test
    fun rollsBackEverythingLearnedWhileComposingAnEncryptedMessage() {
        SuggestionEngine.beginTypingSession()
        SuggestionEngine.learn("совещание", null)
        SuggestionEngine.learn("отменили", "совещание")
        assertEquals(1, SuggestionEngine.learnedForTest("совещание"))
        assertEquals(1, SuggestionEngine.learnedBigramForTest("совещание отменили"))

        SuggestionEngine.forgetTypingSession()

        assertEquals(0, SuggestionEngine.learnedForTest("совещание"))
        assertEquals(0, SuggestionEngine.learnedForTest("отменили"))
        assertEquals(0, SuggestionEngine.learnedBigramForTest("совещание отменили"))
    }

    @Test
    fun keepsWhatWasTypedBeforeTheFieldWasEntered() {
        SuggestionEngine.learn("график", null)

        SuggestionEngine.beginTypingSession()
        SuggestionEngine.learn("график", null)
        assertEquals(2, SuggestionEngine.learnedForTest("график"))

        SuggestionEngine.forgetTypingSession()

        assertEquals(1, SuggestionEngine.learnedForTest("график"))
    }

    @Test
    fun subtractsOnlyTheSessionShareOfAWordTypedEarlierToo() {
        SuggestionEngine.beginTypingSession()
        SuggestionEngine.learn("проект", null)
        SuggestionEngine.learn("проект", null)
        SuggestionEngine.beginTypingSession()
        SuggestionEngine.learn("проект", null)
        assertEquals(3, SuggestionEngine.learnedForTest("проект"))

        SuggestionEngine.forgetTypingSession()

        assertEquals(2, SuggestionEngine.learnedForTest("проект"))
    }

    @Test
    fun rollsBackWordsPromotedByRejectingAnAutocorrection() {
        SuggestionEngine.beginTypingSession()
        SuggestionEngine.noteRejectedCorrection("айсберг")
        assertEquals(2, SuggestionEngine.learnedForTest("айсберг"))

        SuggestionEngine.forgetTypingSession()

        assertEquals(0, SuggestionEngine.learnedForTest("айсберг"))
    }

    @Test
    fun keepsRecordingAfterARollbackSoASecondEncryptionIsCoveredToo() {
        SuggestionEngine.beginTypingSession()
        SuggestionEngine.learn("маршрут", null)
        SuggestionEngine.forgetTypingSession()
        SuggestionEngine.learn("маршрут", null)

        SuggestionEngine.forgetTypingSession()

        assertEquals(0, SuggestionEngine.learnedForTest("маршрут"))
    }

    @Test
    fun clearPersonalDropsEverything() {
        SuggestionEngine.beginTypingSession()
        SuggestionEngine.learn("документ", null)
        SuggestionEngine.clearPersonal()
        assertEquals(0, SuggestionEngine.learnedForTest("документ"))

        SuggestionEngine.forgetTypingSession()
        assertEquals(0, SuggestionEngine.learnedForTest("документ"))
    }
}
