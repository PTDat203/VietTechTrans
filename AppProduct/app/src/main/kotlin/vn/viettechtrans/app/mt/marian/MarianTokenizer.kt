package vn.viettechtrans.app.mt.marian

/**
 * Port of transformers 4.57 `MarianTokenizer` as Phase 03 used it:
 *  - encode: split the special tokens (`</s>`, `<unk>`, `<pad>`) out of the text, strip a
 *    leading `>>lang<<` code, SentencePiece the rest with `source.spm`, map pieces through
 *    `vocab.json` (unknown -> `<unk>`), append `</s>`;
 *  - decode (skip_special_tokens=True): drop special ids, concatenate the pieces, `▁` -> space, trim.
 *
 * @param prefix language control prepended to every source (EN→VI: `>>vie<<`, VI→EN: "").
 */
class MarianTokenizer(
    private val spm: SentencePiece,
    private val vocab: Array<String>,
    private val prefix: String,
    val eosId: Int,
    val unkId: Int,
    val padId: Int,
) {
    private val ids = HashMap<String, Int>(vocab.size * 2).apply { vocab.forEachIndexed { i, t -> putIfAbsent(t, i) } }
    private val special = listOf(vocab[eosId], vocab[unkId], vocab[padId])
    private val specialIds = setOf(eosId, unkId, padId)

    val vocabSize: Int get() = vocab.size

    fun encode(text: String): IntArray {
        val out = ArrayList<Int>()
        for ((segment, isSpecial) in splitSpecial(prefix + text)) {
            if (isSpecial) {
                out += ids.getValue(segment)
                continue
            }
            var rest = segment
            if (rest.startsWith(">>")) { // MarianTokenizer.remove_language_code, run by every _tokenize call
                val end = rest.indexOf("<<")
                if (end != -1) {
                    out += idOf(rest.substring(0, end + 2))
                    rest = rest.substring(end + 2)
                }
            }
            spm.encodeAsPieces(rest).forEach { out += idOf(it) }
        }
        out += eosId
        return out.toIntArray()
    }

    fun decode(tokenIds: IntArray): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            if (id in specialIds) continue
            sb.append(if (id in vocab.indices) vocab[id] else vocab[unkId])
        }
        return sb.toString().replace(SentencePiece.SPACE_SYMBOL, " ").trim()
    }

    private fun idOf(piece: String) = ids[piece] ?: unkId

    /**
     * PreTrainedTokenizer.tokenize splits on special tokens before `_tokenize`; text segments keep
     * their surrounding spaces (SentencePiece drops them anyway). Empty segments are skipped.
     */
    private fun splitSpecial(text: String): List<Pair<String, Boolean>> {
        val parts = ArrayList<Pair<String, Boolean>>()
        var pos = 0
        while (pos < text.length) {
            var hit = -1
            var token = ""
            for (s in special) {
                val i = text.indexOf(s, pos)
                if (i != -1 && (hit == -1 || i < hit || (i == hit && s.length > token.length))) {
                    hit = i
                    token = s
                }
            }
            if (hit == -1) {
                parts += text.substring(pos) to false
                break
            }
            if (hit > pos) parts += text.substring(pos, hit) to false
            parts += token to true
            pos = hit + token.length
        }
        return parts.filter { it.second || it.first.isNotBlank() }
    }

    companion object {
        /** `vocab.txt`: one token per line, line i = id i (written by tools/mt/package_mt.py). */
        fun readVocab(bytes: ByteArray): Array<String> {
            val text = String(bytes, Charsets.UTF_8)
            val lines = text.split('\n')
            return (if (lines.last().isEmpty()) lines.dropLast(1) else lines).toTypedArray()
        }
    }
}
