package com.kryptos.android.screen

enum class SendOutcome { NO_SERVICE, NOT_FOUND, CLICKED, IDLE }

object SendButton {
    private val ID_HINTS = listOf("send", "otpravit")

    private val ID_EXCLUDE = listOf(
        "attach", "emoji", "sticker", "gif", "voice", "audio", "record", "mic",
        "camera", "photo", "gallery", "file", "poll", "money", "gift", "call",
        "delete", "cancel", "close", "search",
    )

    private val TEXT_HINTS = listOf(
        "отправить", "послать", "send", "senden", "absenden",
        "发送", "傳送", "传送", "送出",
    )

    private val TEXT_EXCLUDE = listOf(
        "удалить", "delete", "löschen", "позвонить", "call", "anrufen",
        "прикрепить", "вложени", "attach", "стикер", "sticker", "эмодзи", "emoji",
        "микрофон", "microphone", "mikrofon", "голосов", "voice", "sprachnachricht",
        "запись", "записать", "record", "камер", "camera", "kamera",
        "файл", "file", "datei", "фото", "photo", "foto", "галере", "gallery", "galerie",
        "подарок", "gift", "geschenk", "деньги", "money", "geld", "перевод",
        "отмен", "cancel", "abbrechen", "закрыть", "close", "schließen",
        "поиск", "search", "suche", "опрос", "poll", "umfrage",
        "删除", "刪除", "通话", "通話", "拨打", "撥打",
        "附件", "附加", "表情", "贴纸", "貼紙",
        "麦克风", "麥克風", "语音", "語音", "录音", "錄音",
        "相机", "相機", "照片", "图库", "圖庫", "相册", "相冊",
        "文件", "红包", "紅包", "转账", "轉賬", "礼物", "禮物",
        "取消", "关闭", "關閉", "搜索", "搜尋", "投票",
    )

    fun score(viewId: String?, label: String?): Int {
        if (!harmless(viewId, label)) return 0
        val id = viewId?.substringAfterLast('/')?.lowercase().orEmpty()
        val text = label?.lowercase().orEmpty()
        var score = 0
        if (ID_HINTS.any { it in id }) score += 2
        if (TEXT_HINTS.any { it in text }) score += 3
        return score
    }

    fun harmless(viewId: String?, label: String?): Boolean {
        val id = viewId?.substringAfterLast('/')?.lowercase().orEmpty()
        val text = label?.lowercase().orEmpty()
        return ID_EXCLUDE.none { it in id } && TEXT_EXCLUDE.none { it in text }
    }
}
