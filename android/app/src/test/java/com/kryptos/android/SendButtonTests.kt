package com.kryptos.android

import com.kryptos.android.screen.SendButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendButtonTests {

    @Test
    fun recognisesVkSendButton() {
        assertTrue(SendButton.score("com.vkontakte.android:id/writebar_send", "Отправить") > 0)
    }

    @Test
    fun recognisesSendByIdAlone() {
        assertTrue(SendButton.score("org.telegram.messenger:id/send_button", null) > 0)
        assertTrue(SendButton.score("com.whatsapp:id/send", "") > 0)
    }

    @Test
    fun recognisesSendByLabelAlone() {
        assertTrue(SendButton.score(null, "Send") > 0)
        assertTrue(SendButton.score(null, "Senden") > 0)
        assertTrue(SendButton.score(null, "отправить сообщение") > 0)
    }

    @Test
    fun labelOutranksIdSoWrongIdCannotWin() {
        val byLabel = SendButton.score(null, "Отправить")
        val byId = SendButton.score("com.example:id/send", null)
        assertTrue(byLabel > byId)
    }

    @Test
    fun rejectsNeighbouringComposerButtons() {
        for (label in listOf(
            "Прикрепить вложение", "Стикеры", "Записать голосовое сообщение",
            "Камера", "Удалить", "Позвонить", "Поиск", "Отправить подарок",
            "Attach file", "Voice message", "Delete", "Sprachnachricht",
        )) {
            assertEquals(label, 0, SendButton.score(null, label))
            assertFalse(label, SendButton.harmless(null, label))
        }
    }

    @Test
    fun recognisesChineseSendButton() {
        assertTrue(SendButton.score(null, "发送") > 0)
        assertTrue(SendButton.score(null, "傳送") > 0)
        assertTrue(SendButton.score(null, "发送消息") > 0)
    }

    @Test
    fun rejectsChineseComposerButtons() {
        for (label in listOf(
            "语音", "錄音", "相机", "表情", "附件", "贴纸",
            "红包", "礼物", "取消", "删除", "搜索", "投票",
        )) {
            assertEquals(label, 0, SendButton.score(null, label))
            assertFalse(label, SendButton.harmless(null, label))
        }
        assertEquals(0, SendButton.score(null, "发送红包"))
    }

    @Test
    fun rejectsDangerousIdsEvenWhenTheyContainSend() {
        assertEquals(0, SendButton.score("com.example:id/send_money", "Отправить"))
        assertEquals(0, SendButton.score("com.example:id/send_gift", null))
    }

    @Test
    fun unknownButHarmlessButtonScoresZeroYetStaysEligible() {
        assertEquals(0, SendButton.score(null, "Gönder"))
        assertTrue(SendButton.harmless(null, "Gönder"))
    }

    @Test
    fun emptyInputIsHarmlessButNeverScores() {
        assertEquals(0, SendButton.score(null, null))
        assertTrue(SendButton.harmless(null, null))
    }
}
