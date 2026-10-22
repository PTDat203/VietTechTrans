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
        val text: String,
        val segments: Int,
        val inputTokens: Int,
        val outputTokens: Int,
        val decodeSteps: Int,
        val encoderMs: Long,
        val decoderMs: Long,
    )

    /** [checkCancelled] runs before every decoder step and may throw to abort. */
    fun translate(text: String, checkCancelled: () -> Unit = {}): Result {
        var inTok = 0
        var outTok = 0
        var steps = 0
        var encMs = 0L
        var decMs = 0L
        val parts = segment(text).map { seg ->
            val ids = tokenizer.encode(seg)
            val out = generate(ids, checkCancelled)
            inTok += ids.size
            outTok += out.ids.size
            steps += out.steps
            encMs += out.encoderMs
            decMs += out.decoderMs
            OutputCleanup.collapseRepeats(tokenizer.decode(out.ids))
        }
        return Result(parts.filter { it.isNotEmpty() }.joinToString(" "), parts.size, inTok, outTok, steps, encMs, decMs)
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
     * Phase 04 policy for inputs longer than the Student's 512 positions (never truncate):
     * split into sentences, pack as many as fit per segment, split a too-long sentence by words.
     */
    internal fun segment(text: String): List<String> {
        if (fits(text)) return listOf(text)
        val out = ArrayList<String>()
        var current = ""
        fun flush() {
            if (current.isNotBlank()) out += current.trim()
            current = ""
        }
        for (sentence in text.split(SENTENCE_BREAK).filter { it.isNotBlank() }) {
            val candidate = if (current.isEmpty()) sentence else "$current $sentence"
            when {
                fits(candidate) -> current = candidate
                fits(sentence) -> { flush(); current = sentence }
                else -> {
                    flush()
                    for (word in sentence.split(WHITESPACE).filter { it.isNotEmpty() }) {
                        val next = if (current.isEmpty()) word else "$current $word"
                        if (fits(next) || current.isEmpty()) current = next else { flush(); current = word }
                    }
                }
            }
        }
        flush()
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
        private val SENTENCE_BREAK = Regex("(?<=[.!?…;:])\\s+|\\n+")
        private val WHITESPACE = Regex("\\s+")
    }
}
