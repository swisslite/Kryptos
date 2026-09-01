package com.kryptos.android.core

object StegoSafety {
    const val RUNTIME_MIN_LENGTH = 4
    const val HAN_RUNTIME_MIN_LENGTH = 2

    const val SEED_ATTEMPTS = 256
    const val MIN_SEED_ATTEMPTS = 8
    const val SEED_BUDGET_BYTES = 500_000

    fun seedAllowance(payload: Int): Int =
        minOf(SEED_ATTEMPTS, maxOf(MIN_SEED_ATTEMPTS, SEED_BUDGET_BYTES / maxOf(payload, 1)))

    fun firstCleanCover(payload: Int, make: (Int) -> String): String? {
        val start = randomBytes(1)[0].toInt() and 0xFF
        for (attempt in 0 until seedAllowance(payload)) {
            val text = make((start + attempt) and 0xFF)
            if (!containsBlocked(text)) return text
        }
        return null
    }

    private val stems = listOf(
        "airstrike", "ammunit", "amphetamin", "anschlag", "arschloch", "arsehole",
        "arson", "assassin", "asshole", "attentat", "aufstand", "auftragsmoerd",
        "bastard", "behead", "bestech", "bestial", "betrueger", "bisexual",
        "bisexuell", "bitch", "blowjob", "bollock", "bomb", "boob",
        "bordell", "brandstift", "bribery", "brothel", "bullshit", "cannabis",
        "cocaine", "cocksuck", "corrupt", "crap", "creampie", "cripple",
        "cumshot", "cunt", "decapitat", "demonstration", "deserter", "deserteur",
        "detonat", "dickhead", "douchebag", "dschihad", "dynamit", "ecstasy",
        "entfuehr", "ermord", "erotic", "erotik", "erpress", "erschlag",
        "erschoss", "erstoch", "erwuergt", "espionage", "executed", "execution",
        "explos", "extort", "extremis", "faggot", "fart", "faschis",
        "fascist", "fentanyl", "fick", "firearm", "folter", "fotze",
        "fraudul", "fuck", "gefickt", "geil", "geisel", "geldwaesch",
        "genocide", "geschoss", "getoetet", "gewalt", "gewehr", "granate",
        "grenade", "gunfire", "gunman", "gunshot", "handjob", "haschisch",
        "hashish", "hentai", "heroin", "hijack", "hinricht", "hitman",
        "hochverrat", "holocaust", "homicid", "homosexual", "homosexuell", "hooker",
        "hostage", "hure", "incest", "insurrect", "inzest", "isis",
        "jihad", "kanake", "kanone", "kidnap", "knarre", "kokain",
        "korrupt", "krieg", "kruppel", "launder", "lesb", "lgbt",
        "luftangriff", "lynch", "manslaught", "marihuana", "marijuana", "massacre",
        "massaker", "masturb", "metamphet", "methamphet", "midget", "missbrauch",
        "mobiliz", "mobilmach", "moese", "molest", "mord", "motherfuck",
        "munition", "murder", "muschi", "nackt", "narcotic", "nazi",
        "neger", "nigga", "nigger", "nutte", "opioid", "opium",
        "orgasm", "orgy", "overdose", "paedophil", "pedophil", "piss",
        "pistol", "pogrom", "poisoning", "porn", "prick", "prostituier",
        "prostitut", "protest", "pussy", "putsch", "queer", "radicalis",
        "radicaliz", "radikalis", "rapes", "raping", "rapist", "rauschgift",
        "retard", "revolt", "revolution", "revolver", "rifle", "sabotage",
        "scharfschuetz", "scheiss", "schlampe", "schmuggel", "schrotflinte", "schuss",
        "schwachsinn", "schwanz", "schwul", "selbstmord", "selbstverletz", "selfharm",
        "separatis", "shemale", "shit", "shooting", "shootout", "shotgun",
        "shrapnel", "shroom", "silencer", "slaughter", "slut", "smuggl",
        "sniper", "spasti", "spion", "sprengs", "stabbed", "stabbing",
        "strangl", "streik", "suicide", "suizid", "taliban", "terror",
        "titten", "titties", "toeten", "torture", "totschlag", "traffick",
        "tranny", "transgender", "transsexual", "transsexuell", "treason", "tunte",
        "turd", "twat", "ueberdosis", "umgebracht", "umsturz", "uprising",
        "verfick", "vergewalt", "vergiftet", "verrat", "voelkermord", "waffe",
        "wank", "warfare", "warhead", "weapon", "whore", "wichsen",
        "wichser", "zigeuner", "автомат", "амфетам", "анальн", "барыг",
        "бисекс", "бля", "боевик", "боеприпас", "бомб", "бордел",
        "вербовк", "взорв", "взрыв", "взятк", "винтовк", "военны",
        "войн", "восстани", "выеб", "вымогател", "выродок", "выстрел",
        "ганджа", "гандон", "гашиш", "гей", "гексоген", "геноцид",
        "героин", "герыч", "глушител", "говн", "гомик", "гомосек",
        "гондон", "госизмен", "гранат", "даун", "дегенерат", "дезертир",
        "дерьм", "детонат", "джихад", "диверс", "динамит", "доеб",
        "долбоеб", "долбоё", "дохера", "дохрена", "дроч", "еба",
        "ебе", "ебл", "ебн", "ебс", "ебт", "ебу",
        "ебы", "ебё", "жоп", "забастовк", "задниц", "задуш",
        "заеб", "закладк", "заложник", "залуп", "зарез", "застрел",
        "зоофил", "игил", "избие", "избил", "изнасил", "инцест",
        "истяза", "казнен", "казни", "казнь", "казнён", "калашн",
        "квир", "киллер", "кокаин", "конопл", "контрабанд", "коррупц",
        "крэк", "куни", "лгбт", "лесб", "линчев", "манда",
        "марихуан", "мастурб", "метадон", "метамфет", "мефедрон", "минет",
        "минир", "митинг", "мобилиз", "москал", "мошенн", "муда",
        "мудил", "мудо", "наеб", "наемник", "накурен", "нападени",
        "нарик", "нарком", "наркот", "насилов", "насра", "нахер",
        "нахрен", "нахуй", "нацист", "наёмник", "негр", "некрофил",
        "неонац", "обдолб", "обосра", "обстрел", "олигофрен", "оппозиц",
        "оргаз", "оргия", "оруж", "отмыван", "отрав", "отъеб",
        "охуе", "патрон", "педераст", "педик", "педофил", "пердн",
        "пердун", "переворот", "пидар", "пидер", "пидор", "пидр",
        "пизд", "пизж", "пикет", "пиндос", "пистолет", "повешен",
        "погром", "поджог", "подрыв", "покушени", "поножовщ", "порно",
        "порнух", "посра", "похер", "похрен", "похуй", "презерв",
        "прикончи", "проститут", "протест", "пытк", "пёрд", "радикализ",
        "разврат", "разъеб", "расстрел", "растлен", "револьвер", "революц",
        "резня", "ружь", "саботаж", "самосожж", "самосуд", "самоубий",
        "свергн", "сепаратист", "смертник", "снайпер", "снаряд", "сперм",
        "спизд", "срак", "срал", "сран", "срач", "стрельб",
        "стрелял", "суицид", "сучар", "сучен", "сучк", "съеб",
        "талиб", "теракт", "террор", "торчк", "травести", "трансгендер",
        "транссекс", "трах", "тротил", "убей", "убив", "убий",
        "убил", "убит", "убье", "убью", "убьё", "уеба",
        "уеби", "укурен", "урод", "фашист", "хер", "холокост",
        "хохол", "хрен", "хуе", "хуи", "хуй", "хую",
        "хуя", "хуё", "целка", "черножоп", "членовред", "чурбан",
        "чурк", "шалав", "шахид", "ширка", "шлюх", "шмаль",
        "шпион", "экстази", "экстрем", "эрот", "ёб",
        "آدمربا", "آدمکش", "آشوب", "احمق", "اخاذ", "اختلاس",
        "ارتش", "ارگاسم", "استبداد", "اسلحه", "اسپرم", "اعتراض",
        "اعتصاب", "اعتیاد", "اعدام", "افیون", "الجیبیتی", "القاعده",
        "انتحار", "انفجار", "انقلاب", "اوردوز", "اکستازی", "باجگیر",
        "برانداز", "برهنه", "بسیج", "بمب", "بکش", "بگا",
        "بیشرف", "بیشعور", "بیعفت", "بیغیرت", "بیناموس", "تجاوز",
        "تحصن", "تراجنس", "ترامادول", "ترنس", "ترور", "تریاک",
        "تظاهرات", "تفنگ", "توهمزا", "تیرانداز", "جاکش", "جعل",
        "جلق", "جنازه", "جنای", "جنده", "جنگ", "جنگجو",
        "جنگنده", "جهاد", "حرامزاده", "حرومزاده", "حشیش", "حملات",
        "حمله", "خشونت", "خفه", "خمپاره", "خودارضا", "خودزنی",
        "خودکامه", "خودکشی", "خونریز", "داعش", "دزد", "دوجنس",
        "دیکتاتور", "راهپیمای", "رشوه", "روانگردان", "روسپی", "رید",
        "زنازاده", "زناکار", "ساواک", "سرباز", "سربرید", "سرقت",
        "سرنگون", "سرکوب", "سلاح", "سنگسار", "سپاه", "سکس",
        "شاش", "شلاق", "شلیک", "شهادتطلب", "شهوان", "شهوت",
        "شهید", "شورش", "شکنجه", "طالبان", "طغیان", "عریان",
        "عوضی", "فاحشه", "فشنگ", "قاتل", "قاچاق", "قتل",
        "قرمساق", "قصاص", "لاپای", "لخت", "لزبین", "مادرجنده",
        "ماریجوانا", "مافیا", "متامفتامین", "مخدر", "مرگ", "مستهجن",
        "مسلح", "مسلسل", "معتاد", "معترض", "منفجر", "موشک",
        "میکش", "نارنجک", "نافرمان", "نظامی", "نعوظ", "نفهم",
        "نمیکش", "نکش", "هرزه", "هرزگ", "هروئین", "هرویین",
        "هفتتیر", "همجنس", "همخواب", "پدرسگ", "پستان", "پورن",
        "پولشوی", "کامجو", "کتک", "کثافت", "کراک", "کسکش",
        "کشت", "کلاهبردار", "کودتا", "کوسکش", "کولی", "کون",
        "کوکائین", "کوکایین", "کوییر", "کیر", "کیری", "گانگستر",
        "گاید", "گایید", "گراس", "گروگان", "گلوله", "گوزید",
    )

    private val exactWords = listOf(
        "anal", "armee", "army", "arsch", "arse", "ass",
        "asses", "blade", "blades", "chink", "chinks", "coca",
        "cock", "cocks", "coke", "convict", "coon", "coons",
        "corpse", "coup", "coups", "crack", "cum", "cums",
        "dealer", "dealers", "dick", "dicks", "dope", "droge",
        "drogen", "drug", "drugs", "dyke", "fag", "fags",
        "galgen", "gay", "gays", "gefaengnis", "gift", "giftig",
        "gook", "gooks", "goon", "goons", "gras", "gun",
        "guns", "hang", "hanged", "homo", "jail", "junkie",
        "kike", "kikes", "kill", "killed", "killer", "killers",
        "killing", "kills", "knast", "knife", "knives", "koks",
        "leiche", "leichen", "messer", "meth", "meths", "mongo",
        "naked", "narc", "narcs", "negro", "negroes", "noose",
        "nude", "nudes", "paki", "pakis", "poison", "prison",
        "puss", "rape", "raped", "raub", "rauben", "rauber",
        "raubes", "raubs", "raubst", "raubt", "raubte", "riot",
        "riots", "sex", "sexy", "shiv", "shoot", "shoots",
        "shot", "shots", "slag", "slags", "slay", "slays",
        "soldat", "soldaten", "soldier", "soldiers", "spast", "spic",
        "spics", "stab", "stabs", "syringe", "syringes", "tit",
        "tits", "tot", "tote", "totem", "toten", "toter",
        "totes", "toxic", "troops", "war", "warrior", "warriors",
        "wars", "weed", "wog", "wogs", "боец", "бойца",
        "бойцы", "бунт", "бунта", "бунты", "воин", "воина",
        "воинов", "воины", "голая", "голую", "голые", "голый",
        "дебил", "дебила", "дебилы", "дури", "дурь", "дурью",
        "жид", "жида", "жидов", "жиды", "захват", "захвата",
        "зек", "зека", "зеки", "имбецил", "кокс", "крек",
        "ломка", "ломки", "мин", "мина", "мине", "миной",
        "мину", "мины", "мятеж", "мятежа", "нагая", "нагой",
        "нары", "нах", "нож", "ножа", "ножи", "ножом",
        "ножу", "секс", "секса", "сексе", "сексом", "сексу",
        "солдат", "солдата", "солдатам", "солдатов", "солдаты", "спид",
        "стриптиз", "сука", "суке", "суки", "сукин", "сукины",
        "сукой", "суку", "трава", "травка", "травки", "травку",
        "травой", "траву", "травы", "труп", "трупа", "трупов",
        "трупы", "угнал", "угон", "хач", "хача", "хачи",
        "член", "члена", "членом", "члену", "члены", "штык",
        "яд", "яда", "ядом", "яду",
        "ابله", "افیونی", "بنگ", "تخمی", "جسد", "جق",
        "خنجر", "زنا", "زندان", "زندانه", "زندانها", "زندانی",
        "زندانیان", "زندون", "زندونی", "شیره", "عن", "قیام",
        "لز", "ممه", "منی", "نعش", "چاقو", "کس",
        "کص", "کودن", "کوس", "کونی", "گه", "گی",
    )

    val hanStems = listOf(
        "万岁", "三级片", "上吊", "上床", "上访", "下流", "专制", "专政",
        "串联", "乱伦", "乱党", "乳房", "二奶", "交火", "人妖", "人肉",
        "人质", "人贩", "他妈", "传功", "传销", "伤亡", "伤人", "伪造",
        "你妈", "侵略", "保护费", "做爱", "偷渡", "偷窃", "傻子", "傻逼",
        "内战", "军火", "军队", "冰毒", "凶器", "凶手", "凶杀", "出柜",
        "刃", "分尸", "分裂", "利刃", "制毒", "制贩", "割喉", "割脉",
        "割腕", "动员", "劫", "劫持", "劫机", "勒死", "勒索", "包养",
        "匕", "匪", "卖春", "卖淫", "占领", "卧槽", "卧轨", "卵蛋",
        "去死", "参军", "双性", "受贿", "变天", "变态", "变性", "变装",
        "可卡", "台独", "同志", "同性", "吗啡", "吸毒", "吸食", "命案",
        "团伙", "围堵", "圣战", "地雷", "垃圾", "基佬", "堵门", "士兵",
        "声援", "处女", "大便", "大麻", "女同", "奴", "奸", "妈的",
        "妓", "威胁", "娘炮", "娼", "婊", "嫖", "嫖客", "安眠药",
        "害死", "寻死", "导弹", "封路", "封锁", "射杀", "射精", "小三",
        "小便", "小偷", "小日本", "尸", "尸体", "尻", "尼玛", "尿",
        "屁", "屁股", "屁话", "屄", "屌", "屎", "屎尿", "屠戮",
        "帮派", "干你", "干死", "废物", "开火", "异议", "弄死", "引爆",
        "弩", "弹", "弹匣", "弹壳", "弹药", "强暴", "征兵", "怖",
        "性交", "性感", "性爱", "恐吓", "恐怖", "情色", "惨案", "惨死",
        "戒严", "戒断", "战争", "手淫", "手雷", "扒手", "打倒", "打手",
        "打架", "打死", "打飞机", "扫射", "找死", "抗议", "折磨", "抢劫",
        "抢夺", "抢钱", "报复", "拉屎", "拉拉", "拐卖", "捅死", "掐死",
        "接吻", "推翻", "揍死", "援交", "摇头", "摇头丸", "摔死", "撒尿",
        "撕票", "撞死", "撸管", "操", "支那", "放屁", "放荡", "政变",
        "敲诈", "整死", "斗殴", "斩首", "断头", "早泄", "春药", "智障",
        "暴乱", "暴力", "暴动", "暴徒", "服毒", "杀", "杀死", "杂种",
        "极权", "极端", "枪", "枪战", "枪支", "枪械", "核弹", "械斗",
        "棒子", "横尸", "武装", "死者", "死鬼", "殉情", "残废", "毒",
        "毒枭", "毒死", "毒贩", "毒资", "毙", "毛片", "民兵", "沦陷",
        "法轮", "注射", "洋垃圾", "洋鬼", "洗脑", "洗钱", "活埋", "海洛",
        "淫", "淫荡", "混混", "混蛋", "混账", "淹死", "清场", "港独",
        "游行", "溜冰", "溺死", "激进", "火并", "火药", "灭口", "灭门",
        "炮", "炸", "炸毁", "炸药", "烧死", "烧炭", "爆", "爆破",
        "牛逼", "牢房", "牺牲", "狗东西", "狗屎", "狙击", "独裁", "猥亵",
        "猥琐", "王八", "男同", "畜牲", "畜生", "疆独", "疯子", "瘾",
        "白痴", "白皮", "白粉", "白纸", "皮条", "监狱", "盗", "盗窃",
        "砍人", "砍死", "碎尸", "示威", "空袭", "窑子", "精液", "绑架",
        "绑票", "绝笔", "维权", "罢工", "罢市", "罢课", "联署", "肏",
        "肢解", "自尽", "自慰", "自杀", "自残", "自爆", "致幻", "色情",
        "艳照", "草泥马", "荡妇", "蕾丝", "藏独", "虐待", "血案", "血腥",
        "行凶", "行窃", "行贿", "袭击", "装逼", "裸", "诈骗", "该死",
        "请愿", "谋杀", "贩毒", "贪污", "贱", "贱人", "贱货", "贼",
        "贿赂", "赃款", "走私", "起义", "越狱", "跨性", "跳桥", "跳楼",
        "跳河", "轰击", "轰炸", "轻生", "过量", "迷药", "追杀", "造反",
        "遇害", "遗书", "遗言", "邪教", "邪说", "部队", "酷刑", "野种",
        "针管", "销赃", "镇压", "阉", "阳具", "阳痿", "阴道", "阵亡",
        "阿三", "难民", "集会", "雷管", "露骨", "静坐", "靠北", "革命",
        "颠覆", "骗", "骚", "骚乱", "鬼佬", "鬼子", "鸡巴", "鸡鸡",
        "鸦片", "黄片", "黑帮", "黑社会", "黑钱", "黑鬼", "龟头",
    )

    private val runtimeStems: List<String> =
        stems.filter { it.length >= RUNTIME_MIN_LENGTH } +
            hanStems.filter { it.length >= HAN_RUNTIME_MIN_LENGTH }
    private val prefixIndex: Array<List<String>> = bucket(stems + hanStems)
    private val runtimeIndex: Array<List<String>> = bucket(runtimeStems)
    private val shortestRuntimeStem: Int = runtimeStems.minOf { it.length }
    private val exactSet: Set<String> = exactWords.toSet()

    fun blocks(word: String): Boolean {
        val folded = word.lowercase()
        if (folded in exactSet) return true
        val first = folded.firstOrNull() ?: return false
        for (stem in prefixIndex[first.code and 0xFF]) {
            if (folded.startsWith(stem)) return true
        }
        return false
    }

    fun containsBlocked(text: String): Boolean {
        val folded = text.lowercase()
        if (folded.length < shortestRuntimeStem) return false
        val last = folded.length - shortestRuntimeStem
        for (i in 0..last) {
            for (stem in runtimeIndex[folded[i].code and 0xFF]) {
                if (folded.startsWith(stem, i)) return true
            }
        }
        return false
    }

    private fun bucket(words: List<String>): Array<List<String>> {
        val buckets = Array(256) { mutableListOf<String>() }
        for (word in words) {
            val first = word.firstOrNull() ?: continue
            buckets[first.code and 0xFF].add(word)
        }
        return Array(256) { buckets[it].toList() }
    }
}
