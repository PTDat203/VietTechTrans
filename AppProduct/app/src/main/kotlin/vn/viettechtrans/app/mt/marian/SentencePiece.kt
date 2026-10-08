package vn.viettechtrans.app.mt.marian

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin SentencePiece **unigram encoder** for the Marian `source.spm` files (no native
 * library). Ports the C++ reference (sentencepiece normalizer.cc + unigram_model.cc
 * EncodeOptimized + the unknown-run merging of PopulateSentencePieceText) so that the app
 * produces exactly the pieces `MarianTokenizer` produced in Phase 03; parity is checked by
 * MarianTokenizerParityTest against fixtures generated with Hugging Face.
 */
class SentencePiece private constructor(
    private val pieces: HashMap<String, Int>,
    private val types: IntArray,
    private val scores: FloatArray,
    private val maxPieceChars: Int,
    private val unkId: Int,
    private val minScore: Float,
    private val maxScore: Float,
    private val normalizer: Normalizer,
) {
    /** Normalized text split into pieces (strings, as `spm.encode(text, out_type=str)`). */
    fun encodeAsPieces(text: String): List<String> {
        val normalized = normalizer.normalize(text)
        if (normalized.isEmpty()) return emptyList()
        return viterbi(normalized)
    }

    private fun viterbi(s: String): List<String> {
        val size = s.length
        val bestScore = FloatArray(size + 1)
        val startsAt = IntArray(size + 1) { -1 }
        val ids = IntArray(size + 1) { -1 }
        val unkScore = minScore - UNK_PENALTY
        var start = 0
        while (start < size) {
            val tillHere = bestScore[start]
            val charLen = Character.charCount(s.codePointAt(start))
            var hasSingleNode = false
            // Every piece that is a prefix of s[start:], shortest first (trie traversal order).
            var end = start
            var chars = 0
            while (end < size && chars < maxPieceChars) {
                end += Character.charCount(s.codePointAt(end))
                chars++
                val id = pieces[s.substring(start, end)] ?: continue
                if (types[id] == TYPE_UNUSED) continue
                val score = if (types[id] == TYPE_USER_DEFINED) {
                    s.substring(start, end).toByteArray(Charsets.UTF_8).size * maxScore - 0.1f
                } else {
                    scores[id]
                }
                val candidate = score + tillHere
                if (startsAt[end] == -1 || candidate > bestScore[end]) {
                    bestScore[end] = candidate
                    startsAt[end] = start
                    ids[end] = id
                }
                if (end - start == charLen) hasSingleNode = true
            }
            if (!hasSingleNode) {
                val target = start + charLen
                val candidate = unkScore + tillHere
                if (startsAt[target] == -1 || candidate > bestScore[target]) {
                    bestScore[target] = candidate
                    startsAt[target] = start
                    ids[target] = unkId
                }
            }
            start += charLen
        }
        // Backtrack, then merge runs of unknown pieces like PopulateSentencePieceText.
        val reversed = ArrayList<Pair<String, Boolean>>()
        var endsAt = size
        while (endsAt > 0) {
            val from = startsAt[endsAt]
            reversed.add(s.substring(from, endsAt) to (ids[endsAt] == unkId))
            endsAt = from
        }
        val out = ArrayList<String>(reversed.size)
        var prevUnk = false
        for (i in reversed.indices.reversed()) {
            val (piece, unk) = reversed[i]
            if (prevUnk && unk) out[out.size - 1] = out.last() + piece else out.add(piece)
            prevUnk = unk
        }
        return out
    }

    companion object {
        const val SPACE_SYMBOL = "▁"
        private const val UNK_PENALTY = 10.0f
        private const val TYPE_NORMAL = 1
        private const val TYPE_UNKNOWN = 2
        private const val TYPE_USER_DEFINED = 4
        private const val TYPE_UNUSED = 5

        /** Parses a serialized `ModelProto` (the .spm file). */
        fun load(bytes: ByteArray): SentencePiece {
            val pieceList = ArrayList<Triple<String, Float, Int>>()
            var modelType = 1
            var normalizer = Normalizer(null, addDummyPrefix = true, removeExtraWhitespaces = true, escapeWhitespaces = true)
            ProtoReader(bytes).forEachField { field, reader ->
                when (field) {
                    1 -> { // SentencePiece { piece = 1; score = 2; type = 3 }
                        var piece = ""
                        var score = 0f
                        var type = TYPE_NORMAL
                        reader.message { f, r ->
                            when (f) {
                                1 -> piece = r.string()
                                2 -> score = r.fixed32Float()
                                3 -> type = r.varint().toInt()
                                else -> r.skip()
                            }
                        }
                        pieceList.add(Triple(piece, score, type))
                    }
                    2 -> reader.message { f, r -> if (f == 3) modelType = r.varint().toInt() else r.skip() } // TrainerSpec.model_type
                    3 -> { // NormalizerSpec
                        var charsmap: ByteArray? = null
                        var dummy = true
                        var removeWs = true
                        var escapeWs = true
                        reader.message { f, r ->
                            when (f) {
                                2 -> charsmap = r.bytes()
                                3 -> dummy = r.varint() != 0L
                                4 -> removeWs = r.varint() != 0L
                                5 -> escapeWs = r.varint() != 0L
                                else -> r.skip()
                            }
                        }
                        normalizer = Normalizer(charsmap, dummy, removeWs, escapeWs)
                    }
                    else -> reader.skip()
                }
            }
            require(modelType == 1) { "Chỉ hỗ trợ SentencePiece unigram (model_type=$modelType)" }
            val map = HashMap<String, Int>(pieceList.size * 2)
            val types = IntArray(pieceList.size)
            val scores = FloatArray(pieceList.size)
            var unk = -1
            var minScore = Float.MAX_VALUE
            var maxScore = -Float.MAX_VALUE
            var maxChars = 1
            pieceList.forEachIndexed { i, (piece, score, type) ->
                types[i] = type
                scores[i] = score
                if (type == TYPE_UNKNOWN) unk = i
                if (type == TYPE_NORMAL || type == TYPE_USER_DEFINED || type == TYPE_UNUSED) {
                    map.putIfAbsent(piece, i)
                    maxChars = maxOf(maxChars, piece.codePointCount(0, piece.length))
                }
                if (type == TYPE_NORMAL) {
                    minScore = minOf(minScore, score)
                    maxScore = maxOf(maxScore, score)
                }
            }
            require(unk >= 0) { "Model SentencePiece không có <unk>" }
            return SentencePiece(map, types, scores, maxChars, unk, minScore, maxScore, normalizer)
        }
    }

    /** normalizer.cc: precompiled charsmap (Darts double-array) + whitespace rules. */
    class Normalizer(
        charsmap: ByteArray?,
        private val addDummyPrefix: Boolean,
        private val removeExtraWhitespaces: Boolean,
        private val escapeWhitespaces: Boolean,
    ) {
        private val trie: IntArray
        private val normalized: ByteArray

        init {
            if (charsmap == null || charsmap.size < 4) {
                trie = IntArray(0)
                normalized = ByteArray(0)
            } else {
                val buf = ByteBuffer.wrap(charsmap).order(ByteOrder.LITTLE_ENDIAN)
                val trieBytes = buf.getInt(0)
                trie = IntArray(trieBytes / 4) { buf.getInt(4 + it * 4) }
                normalized = charsmap.copyOfRange(4 + trieBytes, charsmap.size)
            }
        }

        fun normalize(text: String): String {
            val input = text.toByteArray(Charsets.UTF_8)
            var pos = 0
            if (removeExtraWhitespaces) { // ignore heading space
                while (pos < input.size) {
                    val (piece, consumed) = normalizePrefix(input, pos)
                    if (!(piece.size == 1 && piece[0] == SPACE)) break
                    pos += consumed
                }
            }
            if (pos >= input.size) return ""
            val out = java.io.ByteArrayOutputStream(input.size * 2)
            val space = SPACE_SYMBOL.toByteArray(Charsets.UTF_8)
            if (addDummyPrefix) out.write(if (escapeWhitespaces) space else byteArrayOf(SPACE))
            var prevSpace = removeExtraWhitespaces
            while (pos < input.size) {
                val (piece, consumed) = normalizePrefix(input, pos)
                var from = 0
                if (prevSpace) while (from < piece.size && piece[from] == SPACE) from++
                if (from < piece.size) {
                    for (i in from until piece.size) {
                        if (escapeWhitespaces && piece[i] == SPACE) out.write(space) else out.write(piece[i].toInt())
                    }
                    prevSpace = piece[piece.size - 1] == SPACE
                }
                pos += consumed
                if (!removeExtraWhitespaces) prevSpace = false
            }
            var bytes = out.toByteArray()
            if (removeExtraWhitespaces) { // ignore tailing space
                val tail = if (escapeWhitespaces) space else byteArrayOf(SPACE)
                var len = bytes.size
                while (len >= tail.size && (0 until tail.size).all { bytes[len - tail.size + it] == tail[it] }) len -= tail.size
                if (len != bytes.size) bytes = bytes.copyOf(len)
            }
            return String(bytes, Charsets.UTF_8)
        }

        /** Longest charsmap rule at [pos] -> (replacement bytes, consumed input bytes). */
        private fun normalizePrefix(input: ByteArray, pos: Int): Pair<ByteArray, Int> {
            var longestLength = 0
            var longestValue = 0
            if (trie.isNotEmpty()) {
                var id = 0
                var unit = trie[0]
                id = id xor offset(unit)
                var i = pos
                while (i < input.size) {
                    val c = input[i].toInt() and 0xFF
                    if (c == 0) break
                    id = id xor c
                    if (id < 0 || id >= trie.size) break
                    unit = trie[id]
                    if (label(unit) != c) break
                    id = id xor offset(unit)
                    if (hasLeaf(unit)) {
                        val length = i + 1 - pos
                        if (longestLength == 0 || length > longestLength) {
                            longestLength = length
                            longestValue = trie[id] and 0x7FFFFFFF
                        }
                    }
                    i++
                }
            }
            if (longestLength == 0) {
                val len = utf8Length(input, pos)
                return if (len == 0) REPLACEMENT to 1 else input.copyOfRange(pos, pos + len) to len
            }
            var end = longestValue
            while (end < normalized.size && normalized[end] != 0.toByte()) end++
            return normalized.copyOfRange(longestValue, end) to longestLength
        }

        private fun hasLeaf(unit: Int) = (unit ushr 8) and 1 == 1
        private fun label(unit: Int) = unit and (0x80000000.toInt() or 0xFF)
        private fun offset(unit: Int) = (unit ushr 10) shl ((unit and (1 shl 9)) ushr 6)

        /** Length of a valid UTF-8 character at [pos], or 0 when malformed (string_util::IsValidDecodeUTF8). */
        private fun utf8Length(b: ByteArray, pos: Int): Int {
            val c = b[pos].toInt() and 0xFF
            fun cont(k: Int) = pos + k < b.size && (b[pos + k].toInt() and 0xC0) == 0x80
            return when {
                c < 0x80 -> 1
                c in 0xC2..0xDF && cont(1) -> 2
                c in 0xE0..0xEF && cont(1) && cont(2) -> {
                    val cp = ((c and 0x0F) shl 12) or ((b[pos + 1].toInt() and 0x3F) shl 6) or (b[pos + 2].toInt() and 0x3F)
                    if (cp < 0x800 || cp in 0xD800..0xDFFF) 0 else 3
                }
                c in 0xF0..0xF4 && cont(1) && cont(2) && cont(3) -> {
                    val cp = ((c and 0x07) shl 18) or ((b[pos + 1].toInt() and 0x3F) shl 12) or
                        ((b[pos + 2].toInt() and 0x3F) shl 6) or (b[pos + 3].toInt() and 0x3F)
                    if (cp < 0x10000 || cp > 0x10FFFF) 0 else 4
                }
                else -> 0
            }
        }

        private companion object {
            const val SPACE: Byte = ' '.code.toByte()
            val REPLACEMENT = byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())
        }
    }
}

/** Minimal protobuf wire-format reader (varint, 32-bit, length-delimited). */
internal class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    private var wireType = 0

    fun forEachField(block: (field: Int, reader: ProtoReader) -> Unit) {
        while (pos < end) {
            val key = varint()
            wireType = (key and 7).toInt()
            block((key ushr 3).toInt(), this)
        }
    }

    fun message(block: (field: Int, reader: ProtoReader) -> Unit) {
        val len = varint().toInt()
        ProtoReader(buf, pos, pos + len).forEachField(block)
        pos += len
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun fixed32Float(): Float {
        val bits = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return Float.fromBits(bits)
    }

    fun bytes(): ByteArray {
        val len = varint().toInt()
        return buf.copyOfRange(pos, pos + len).also { pos += len }
    }

    fun string(): String = String(bytes(), Charsets.UTF_8)

    fun skip() {
        when (wireType) {
            0 -> varint()
            1 -> pos += 8
            2 -> {
                val len = varint().toInt() // not `pos += varint()`: that reads pos before varint() advances it
                pos += len
            }
            5 -> pos += 4
            else -> error("Unsupported protobuf wire type $wireType")
        }
    }
}
