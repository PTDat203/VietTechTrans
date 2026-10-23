package vn.viettechtrans.app.mt.marian

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Decoding settings from the package manifest (= the checkpoint's generation_config). */
data class GenerationParams(
    val numBeams: Int,
    val maxLength: Int,
    val lengthPenalty: Float,
    val decoderStartId: Int,
    val eosId: Int,
) {
    /**
     * App policy on top of the generation_config (DR-07, 2026-10-07; `app_max_length` in
     * tools/mt/marian_ref.py): the output may be at most 2 x source tokens + 20 long, and at that
     * length the best finished hypothesis wins ([BeamSearch] preferFinished). Without it,
     * beam search with early_stopping=false keeps extending the other beams up to 512 when the best
     * one finished early (VI→EN "chào bạn" -> "Hello" took 511 steps, 12 s on a phone), and
     * degenerate repetitions run to 512 tokens. On Validation only degenerate outputs are longer.
     */
    fun maxLengthFor(srcLen: Int): Int = minOf(maxLength, LENGTH_CAP_RATIO * srcLen + LENGTH_CAP_SLACK)

    companion object {
        const val LENGTH_CAP_RATIO = 2
        const val LENGTH_CAP_SLACK = 20
    }
}

/**
 * One translation direction: the graph of tools/mt/build_marian_onnx.py (`If(is_encoder)`:
 * encoder | one decoder step with KV-cache) plus its tokenizer. Not thread-safe; the
 * translator calls it from a single thread.
 */
class MarianEngine(
    modelBytes: ByteArray,
    val tokenizer: MarianTokenizer,
    private val gen: GenerationParams,
    private val maxInputTokens: Int,
    threads: Int = 2,
    /** Ranges of [segment]'s input that must not be split: URLs, e-mails, code, technical literals (policy R09). */
    private val protectedRanges: (String) -> List<IntRange> = { emptyList() },
) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = OrtSession.SessionOptions().use { opts ->
        opts.setIntraOpNumThreads(threads)
        opts.setInterOpNumThreads(1)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Do not busy-wait between steps: the audio threads share the CPU.
        opts.addConfigEntry("session.intra_op.allow_spinning", "0")
        env.createSession(modelBytes, opts)
    }

    class Result(
        /** After [OutputCleanup.collapseRepeats] (R11), segments joined by one space. */
        val text: String,
        val segments: Int,
        val inputTokens: Int,
        val outputTokens: Int,
        val decodeSteps: Int,
        val encoderMs: Long,
        val decoderMs: Long,
        /** Decoded output before the repeat cleanup (evidence: "output thô"). */
        val rawText: String = text,
        /** R11 changed at least one segment (flag repeat_cleanup_applied). */
        val repeatCleanupApplied: Boolean = false,
        /** Source ids of every segment, EOS included (evidence: source_ids). */
        val sourceIds: List<IntArray> = emptyList(),
    )

    /** [checkCancelled] runs before every decoder step and may throw to abort. */
    fun translate(text: String, checkCancelled: () -> Unit = {}): Result {
        var inTok = 0
        var outTok = 0
        var steps = 0
        var encMs = 0L
        var decMs = 0L
        val ids = ArrayList<IntArray>()
        val raw = ArrayList<String>()
        val parts = segment(text).map { seg ->
            val src = tokenizer.encode(seg)
            val out = generate(src, checkCancelled)
            ids += src
            inTok += src.size
            outTok += out.ids.size
            steps += out.steps
            encMs += out.encoderMs
            decMs += out.decoderMs
            val decoded = tokenizer.decode(out.ids)
            raw += decoded
            OutputCleanup.collapseRepeats(decoded)
        }
        return Result(
            parts.filter { it.isNotEmpty() }.joinToString(" "), parts.size, inTok, outTok, steps, encMs, decMs,
            rawText = raw.filter { it.isNotEmpty() }.joinToString(" "),
            repeatCleanupApplied = parts.indices.any { parts[it] != raw[it] },
            sourceIds = ids,
        )
    }

    class Generated(val ids: IntArray, val steps: Int, val encoderMs: Long, val decoderMs: Long)

    /** Beam search for one tokenized source (≤ [maxInputTokens] ids, ending with EOS). */
    fun generate(src: IntArray, checkCancelled: () -> Unit = {}): Generated {
        require(src.size <= maxInputTokens) { "Input ${src.size} > $maxInputTokens tokens" }
        val owned = ArrayList<AutoCloseable>()
        fun <T : AutoCloseable> T.own(): T = also { owned += it }
        try {
            val t0 = System.nanoTime()
            val isEncoder = OnnxTensor.createTensor(env, true).own()
            val isDecoder = OnnxTensor.createTensor(env, false).own()
            val emptyPast = floats(longArrayOf(LAYERS, 1, HEADS, 0, HEAD_DIM)).own()
            val encoded = session.run(
                mapOf(
                    "input_ids" to longs(LongArray(src.size) { src[it].toLong() }, longArrayOf(1, src.size.toLong())).own(),
                    "is_encoder" to isEncoder,
                    "past_k" to emptyPast,
                    "past_v" to emptyPast,
                    "beam_idx" to longs(longArrayOf(0), longArrayOf(1)).own(),
                    "cross_k_t" to floats(longArrayOf(LAYERS, 1, HEADS, HEAD_DIM, 0)).own(),
                    "cross_v" to floats(longArrayOf(LAYERS, 1, HEADS, 0, HEAD_DIM)).own(),
                ),
                setOf("out_k", "out_v"),
            ).own()
            val crossK = encoded.get("out_k").get() as OnnxTensor
            val crossV = encoded.get("out_v").get() as OnnxTensor
            val t1 = System.nanoTime()

            val vocab = tokenizer.vocabSize
            var past: OrtSession.Result? = null
            val search = BeamSearch(gen.decoderStartId, gen.eosId, gen.numBeams, gen.maxLengthFor(src.size), gen.lengthPenalty, preferFinished = true)
            val out = try {
                search.run(vocab, { tokens, parents ->
                    val n = tokens.size.toLong()
                    OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(tokens.size) { tokens[it].toLong() }), longArrayOf(n, 1)).use { ids ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(parents.size) { parents[it].toLong() }), longArrayOf(n)).use { beamIdx ->
                            val prev = past
                            val res = session.run(
                                mapOf(
                                    "input_ids" to ids,
                                    "is_encoder" to isDecoder,
                                    "past_k" to (prev?.get(0) as OnnxTensor? ?: emptyPast),
                                    "past_v" to (prev?.get(1) as OnnxTensor? ?: emptyPast),
                                    "beam_idx" to beamIdx,
                                    "cross_k_t" to crossK,
                                    "cross_v" to crossV,
                                ),
                            )
                            prev?.close()
                            past = res
                            val buf = (res.get(2) as OnnxTensor).floatBuffer
                            FloatArray(buf.remaining()).also { buf.get(it) }
                        }
                    }
                }, checkCancelled)
            } finally {
                past?.close()
            }
            val t2 = System.nanoTime()
            return Generated(out.ids, out.steps, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000)
        } finally {
            owned.asReversed().forEach { runCatching { it.close() } }
        }
    }

    /**
     * Policy R09 for inputs longer than [maxInputTokens] source ids (never truncate): split after . ! ? … ; : (or at
     * a line break), pack as many sentences as fit per segment, split a too-long sentence at spaces, never inside a
     * protected range (URL, e-mail, code, literal). Only a single run without any allowed break that is still too long
     * ("a.a.a.…", 500 characters = 501 ids) is cut between characters, as the last resort.
     */
    internal fun segment(text: String): List<String> {
        if (fits(text)) return listOf(text)
        val keep = protectedRanges(text)
        fun allowed(cut: IntRange) = keep.none { it.first < cut.last + 1 && cut.first <= it.last }
        val out = ArrayList<String>()
        var current = ""
        fun flush() {
            if (current.isNotBlank()) out += current.trim()
            current = ""
        }
        fun place(piece: String) {
            val candidate = if (current.isEmpty()) piece else "$current $piece"
            if (fits(candidate)) current = candidate else { flush(); current = piece }
        }
        for ((sentenceStart, sentence) in pieces(text, 0, text.length, SENTENCE_BREAK, ::allowed)) {
            if (fits(sentence)) { place(sentence); continue }
            flush()
            for ((_, word) in pieces(text, sentenceStart, sentenceStart + sentence.length, WHITESPACE, ::allowed)) {
                if (fits(word)) place(word) else { flush(); out += byCharacters(word) }
            }
        }
        flush()
        return out
    }

    /**
     * [text] between [from] and [to] cut at the matches of [breaks] that [ok] accepts (ranges in [text] coordinates);
     * each piece with its start offset in [text], trimmed, empty ones dropped.
     */
    private fun pieces(text: String, from: Int, to: Int, breaks: Regex, ok: (IntRange) -> Boolean): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var start = from
        fun emit(end: Int) {
            val raw = text.substring(start, end)
            val lead = raw.length - raw.trimStart().length
            val piece = raw.trim()
            if (piece.isNotEmpty()) out += (start + lead) to piece
        }
        for (m in breaks.findAll(text.substring(from, to))) {
            val cut = (m.range.first + from)..(m.range.last + from)
            if (!ok(cut)) continue
            emit(cut.first)
            start = cut.last + 1
        }
        emit(to)
        return out
    }

    /** Longest prefixes that fit, one after the other (binary search on the length). */
    private fun byCharacters(word: String): List<String> {
        val out = ArrayList<String>()
        var rest = word
        while (rest.isNotEmpty()) {
            if (fits(rest)) { out += rest; break }
            var lo = 1
            var hi = rest.length - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (fits(rest.substring(0, mid))) lo = mid else hi = mid - 1
            }
            // Do not split a surrogate pair.
            val cut = if (lo < rest.length && Character.isLowSurrogate(rest[lo]) && lo > 1) lo - 1 else lo
            out += rest.substring(0, cut)
            rest = rest.substring(cut)
        }
        return out
    }

    private fun fits(text: String) = tokenizer.encode(text).size <= maxInputTokens

    private fun longs(values: LongArray, shape: LongArray) = OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)

    private fun floats(shape: LongArray) = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), shape)

    override fun close() = session.close()

    companion object {
        const val LAYERS = 6L
        const val HEADS = 8L
        const val HEAD_DIM = 64L
        /** The whitespace after a sentence end (or a line break): the cut removes it. */
        private val SENTENCE_BREAK = Regex("(?<=[.!?…;:])\\s+|\\n+")
        private val WHITESPACE = Regex("\\s+")
    }
}
