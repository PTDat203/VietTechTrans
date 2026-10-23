package vn.viettechtrans.app.mt.rules

/** Offline language guess of typed text (policy R06): only these four values. */
enum class LangGuess(val id: String) {
    VI_HIGH("vi_high"),
    EN_HIGH("en_high"),
    MIXED("mixed"),
    UNCERTAIN("uncertain"),
}

/**
 * Word evidence of a text once technical literals (URL, code, R03 literals, acronyms, technology names, numbers)
 * are removed. Words are letter runs; English contractions ("don't") stay one word.
 */
data class LangEvidence(
    /** Words with a Vietnamese diacritic or đ. */
    val marked: Int,
    /** Frequent Vietnamese words typed without diacritics that are not English words ("toi", "khong", "kiem"). */
    val plain: Int,
    /** Other ASCII words shaped like an unaccented Vietnamese syllable ("tra", "loi", but also "map"). */
    val shape: Int,
    /** Frequent English words ("the", "how", "are", "please"). */
    val english: Int,
    /** Other ASCII words: English content words and jargon ("deploy", "database"). */
    val foreign: Int,
) {
    val words: Int get() = marked + plain + shape + english + foreign
}

/**
 * Offline detector for R06 (wrong direction) and R07 (Vietnamese without diacritics). It only warns: it never
 * switches the direction or the model. Thresholds were set on the Validation sets (DR-07 §16).
 */
class LanguageGuesser(private val literals: TechLiterals) {

    fun evidence(text: String): LangEvidence {
        val masked = StringBuilder(text)
        for (l in literals.spans(text)) for (i in l.start until l.end) masked.setCharAt(i, ' ')
        var marked = 0
        var plain = 0
        var shape = 0
        var english = 0
        var foreign = 0
        val words = WORD.findAll(masked).toList()
        var skip = -1
        for ((i, m) in words.withIndex()) {
            if (i == skip) continue
            val typed = m.value
            val w = typed.lowercase()
            // "cam on", "xin loi", "co the": very frequent unaccented Vietnamese whose words alone look English ("on", "the").
            if (i + 1 < words.size && "$w ${words[i + 1].value.lowercase()}" in WordLists.VI_PAIRS) {
                plain += 2
                skip = i + 1
                continue
            }
            // Names and acronyms ("San Francisco, CA", "NYC") are not Vietnamese evidence even if they look like it.
            val allCaps = typed.length >= 2 && typed.none { it.isLowerCase() }
            val name = typed[0].isUpperCase() && !sentenceStart(masked, m.range.first)
            when {
                // A capitalized Vietnamese word inside a sentence is a name ("I love phở in Hà Nội"): no evidence.
                w.any { it in VI_ONLY } -> if (!name) marked++
                // á é í ó ú à è ì ò ù ã õ ý are also Spanish/French: Vietnamese only when the word is a Vietnamese
                // syllable without them ("bán" yes; "Pokémon", "José", "département" no).
                w.any { it in VI_MARKED } && VietnameseSyllable.isUnaccented(strip(w)) -> if (!name) marked++
                w.any { it.code > 0x7F } -> Unit // other scripts and accents: no evidence
                w.length == 1 && w != "a" && w != "i" -> Unit
                w in WordLists.NEUTRAL -> Unit
                w in WordLists.ENGLISH -> if (!allCaps) english++
                allCaps -> Unit
                w in WordLists.VI_PLAIN -> if (!name) plain++
                VietnameseSyllable.isUnaccented(w) -> if (!name) shape++
                // A capitalized word inside a sentence is a name ("Proton Mail"), in either language.
                else -> if (!name) foreign++
            }
        }
        return LangEvidence(marked, plain, shape, english, foreign)
    }

    fun guess(text: String): LangGuess = guess(evidence(text))

    fun guess(e: LangEvidence): LangGuess = when {
        e.words == 0 -> LangGuess.UNCERTAIN
        // Any Vietnamese diacritic is strong evidence; an English sentence quoting a Vietnamese word ("I love phở",
        // "Do you like bánh mì?") has English function words and at least twice as many other words as Vietnamese
        // ones, and stays mixed. English jargon alone does not count: "Tôi cần fix bug trong production server".
        e.marked > 0 -> if (e.english >= 2 && e.english > e.marked + e.plain || e.english >= 1 && e.english + e.foreign >= 2 * (e.marked + e.plain)) {
            LangGuess.MIXED
        } else {
            LangGuess.VI_HIGH
        }
        // No frequent Vietnamese word: English as soon as there is some English (or three foreign words).
        e.plain == 0 && (e.english >= 2 && e.english > e.shape || e.shape == 0 && (e.english >= 1 && e.foreign >= 2 || e.foreign >= 3)) -> LangGuess.EN_HIGH
        e.english >= 2 && e.english >= 3 * e.plain && e.english + e.foreign >= (e.words * 3 + 4) / 5 -> LangGuess.EN_HIGH
        // Vietnamese typed without diacritics, possibly with English jargon ("toi can deploy API len production").
        e.plain >= 2 && e.plain > e.english && e.plain + e.shape >= e.english + e.foreign -> LangGuess.VI_HIGH
        e.plain + e.shape >= 2 && e.english >= 2 -> LangGuess.MIXED
        else -> LangGuess.UNCERTAIN
    }

    private fun sentenceStart(text: CharSequence, index: Int): Boolean {
        var i = index - 1
        while (i >= 0 && (text[i].isWhitespace() || text[i] in "\"'“‘([*-")) i--
        return i < 0 || text[i] in ".!?…:;"
    }

    /**
     * Light R06 warning for an uncertain guess: only when the text hints at the other language of [englishSource]
     * (no point warning on "JPEG Images" typed as English).
     */
    fun hintsOtherLanguage(e: LangEvidence, englishSource: Boolean): Boolean =
        if (englishSource) e.marked + e.plain > 0 && e.english == 0 else e.marked + e.plain == 0 && e.english > 0

    /**
     * R07: the text has no Vietnamese diacritic at all but clearly is Vietnamese ("toi can kiem tra loi API").
     * Not for text that merely lacks diacritics ("database connection pool", "CPU", a URL, "API timeout 504").
     */
    fun unaccentedVietnamese(text: String): Boolean {
        if (text.any { it.lowercaseChar() in VI_ONLY }) return false
        val e = evidence(text)
        if (e.marked > 0) return false
        val vi = e.plain + e.shape
        // English jargon ("deploy len production bi loi") does not count against: only English function words do.
        return (e.plain >= 2 || e.plain >= 1 && e.shape >= 1) && vi >= 2 && vi >= e.english
    }

    companion object {
        private val WORD = Regex("[\\p{L}\\p{M}]+(?:['’][\\p{L}]+)?")

        /** Every lowercase Vietnamese letter with a diacritic, and đ. */
        const val VI_MARKED = "àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ"

        /** The ones no other Latin-script language of the data uses: ă â đ ê ô ơ ư, hook above, dot below, ẽ ĩ ũ ỹ. */
        const val VI_ONLY = "ảạăằắẳẵặâầấẩẫậẻẽẹêềếểễệỉĩịỏọôồốổỗộơờớởỡợủũụưừứửữựỳỷỹỵđ"

        private fun strip(w: String): String = java.text.Normalizer.normalize(w, java.text.Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.replace('đ', 'd')
    }
}

/** Word lists of the language guess (R06/R07), also used by TechLiterals to tell words from identifiers. */
internal object WordLists {
    /** Unaccented Vietnamese word pairs counted as two Vietnamese words (each alone may look English). */
    val VI_PAIRS = (
        "cam on,xin loi,xin chao,tam biet,co the,khong the,lam on,the nao,nhu the,bao nhieu,tai sao,o dau,bay gio," +
            "hom nay,ngay mai,hom qua,cam ta,the thi,the la,do la,an toan,to chuc,can thiet,to di,to la,on roi,on ma,on khong," +
            "no di,no la,no o,o day,di day,den day,the nay,the do,the kia,day la,ve nha,an com,di lam"
    ).split(',').toSet()

    /** Frequent in both languages once Vietnamese loses its diacritics: no evidence either way. */
    val NEUTRAL = "me can so hang long sang ten man may chat pin song net bus cam ok okay day".split(' ').toSet()

    val ENGLISH = (
        "the to an on no be do of " +
            "a i about above after again against all also always am among and another any anyone anything are aren't around as ask asked at " +
            "away back because been before being below best better between both but by came cannot can't could couldn't day did didn't " +
            "different does doesn't doing don't down during each either else enough even ever every everyone everything few find first for " +
            "found from full further get gets getting give given go goes going gone good got great had hadn't has hasn't have haven't having " +
            "he he's her here hers herself him himself his how however i'd i'll i'm i've if in into is isn't it it's its itself just keep know " +
            "known last least less let let's like little look looking made make makes making many more most much must my myself need needs " +
            "never new next not nothing now of off often old once one only or other others our ours ourselves out over own part people " +
            "please put quite rather really right said same say says see seem seems seen several shall she she's should shouldn't show since " +
            "small some someone something sometimes soon still such sure take than thank thanks that that's their theirs them themselves then " +
            "there there's these they they're thing things think this those though through thus till too took toward try trying two under " +
            "until up upon us use used uses using usually very want wants was wasn't way we we're well went were weren't what what's when where " +
            "whether which while who whole whom whose why will with within without won't work works would wouldn't yes yet you you're your " +
            "yours yourself hello hi hey sorry welcome bye goodbye morning night today tomorrow yesterday time year years week help thanks " +
            "fine nice great happy love like friend friends name where's how's here's isn't wasn't let's"
    ).split(' ').filter { it.isNotEmpty() && it !in NEUTRAL }.toSet()

    /** Frequent Vietnamese words without diacritics that are not English words. */
    val VI_PLAIN = (
        "toi ban co nam tim tam gia lo tin hay minh chung ho anh chi em ong ba cac nhung mot hai khong duoc chua roi dang se da vua moi cung deu rat qua lam nhieu " +
            "hon nhat cua voi trong ngoai tren duoi giua sau truoc nay kia day dau nao gi sao nhu vay la va hoac neu thi ma vi de cho tu " +
            "den len xuong ra vao lai nua nguoi viec cai chiec ngay thang gio phut giay tinh dien thoai mang thong phan mem du lieu chuong " +
            "trinh ung dung cap nhat dat kiem tra loi sua xoa tao mo dong chay gui nhan tai hien nhap xuat luu tep thu muc trang khi nen " +
            "phai muon biet hieu thay noi hoi giup xin chao vang oi nhe nha nhi bon bay chin muoi tram nghin trieu lan buoc cach ly boi " +
            "tuy nhien tat ca rieng toan bo khac giong cu lon nho tot xau dep nhanh cham kho dung sai that tien mua thue quan tri bao mat " +
            "khau khoan ky thiet bi ket hinh phim chuot loa nghe am thanh duong dan nhom chon bam nut cai dat tat bat khoi dong lenh dich " +
            "tieng viet nhung nguoi dung cua so bang ghi chep goi nhan tin nhan khach hang san pham dich vu cong ty hoc sinh vien giao dien " +
            "chuc nang tinh nang bai toan van de ket qua thong bao canh bao huong dan cau hinh tai khoan mat khau dang ky dang nhap"
    ).split(' ').filter { it.isNotEmpty() && it !in NEUTRAL }.toSet()

    fun isWord(w: String): Boolean = w in ENGLISH || w in VI_PLAIN || w in NEUTRAL
}

/** Unaccented Vietnamese syllable shape: onset + rhyme (diacritics already removed). */
object VietnameseSyllable {
    private val ONSETS = listOf("ngh", "ng", "nh", "ch", "gh", "gi", "kh", "ph", "qu", "th", "tr", "b", "c", "d", "g", "h", "k", "l", "m", "n", "p", "r", "s", "t", "v", "x", "")

    /** Vowel groups of Vietnamese rhymes without diacritics (ă/â→a, ê→e, ô/ơ→o, ư→u). */
    private val NUCLEI = (
        "a e i o u y ai ao au ay eo eu ia ie iu oa oe oi oo ua ue ui uo uu uy ya ye oai oay oao oeo uay uoi uou uya uye uyu ieu yeu"
        ).split(' ').toSet()
    private val CODAS = listOf("ng", "nh", "ch", "c", "m", "n", "p", "t", "")

    fun isUnaccented(word: String): Boolean {
        if (word.isEmpty() || word.length > 7 || word.any { it !in 'a'..'z' } || word.any { it in "fjwz" }) return false
        for (onset in ONSETS) {
            if (!word.startsWith(onset)) continue
            val rest = word.substring(onset.length)
            for (coda in CODAS) {
                if (!rest.endsWith(coda)) continue
                val nucleus = rest.substring(0, rest.length - coda.length)
                if (nucleus in NUCLEI && (coda.isEmpty() || nucleus.last() !in "iyou" || nucleus.length == 1 || nucleus in CLOSED_OK)) return true
            }
        }
        return false
    }

    /** Vowel groups that may be followed by a final consonant (iên, uôn, uyên, oan, oăn …). */
    private val CLOSED_OK = setOf("ie", "uo", "uye", "oa", "oe", "ua", "ue", "ye", "uy", "ia")
}
