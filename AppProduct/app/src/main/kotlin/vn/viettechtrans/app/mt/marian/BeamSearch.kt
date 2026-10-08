package vn.viettechtrans.app.mt.marian

/**
 * Beam search of transformers 4.57 `GenerationMixin._beam_search` for one sentence, with the
 * Main-Core generation_config: num_beams per direction (EN→VI 4, VI→EN 6), length_penalty 1.0,
 * early_stopping false, max_length 512, forced EOS at max_length-1. Port of `beam_search` in tools/mt/marian_ref.py,
 * which reproduces Hugging Face token for token on the fp32 graph. Float32 arithmetic and the
 * -1e9 sentinels are kept on purpose so ties resolve the same way.
 *
 * The pad-token ban and log-softmax renormalization are already applied by the graph.
 *
 * [preferFinished] (app policy, with [GenerationParams.maxLengthFor]): on reaching [maxLength], return
 * the best finished hypothesis if there is one instead of forcing EOS on the running beams like HF.
 * Forced EOS lets a long repetitive beam beat a complete short sentence by its average log-prob. With
 * it, every output shorter than maxLength - 1 tokens is the same as without the cap.
 */
class BeamSearch(
    private val startId: Int,
    private val eosId: Int,
    private val numBeams: Int = 4,
    private val maxLength: Int = 512,
    private val lengthPenalty: Float = 1f,
    private val preferFinished: Boolean = false,
) {
    /** One decoder step for the running beams. */
    fun interface Step {
        /**
         * @param tokens last token of each running beam
         * @param parents beam (of the previous call) each running beam continues; reorders the KV-cache
         * @return log-probs, row-major [tokens.size][vocab]
         */
        fun run(tokens: IntArray, parents: IntArray): FloatArray
    }

    class Output(val ids: IntArray, val steps: Int)

    /** Generated ids without the decoder start token (ends with EOS when produced). */
    fun run(vocab: Int, step: Step, checkCancelled: () -> Unit = {}): Output {
        val k = numBeams
        val keep = 2 * numBeams
        val promptLen = 1
        var curLen = 1
        // HF starts with num_beams copies scored [0, -1e9, ...]; the copies never reach the top-k when the
        // first beam alone offers 2*num_beams candidates, so one beam is enough (saves a 4-6x first step).
        val first = if (vocab >= keep) 1 else k
        var running = List(first) { intArrayOf(startId) }
        var runningScores = FloatArray(first) { if (it == 0) 0f else NEG }
        var parents = IntArray(first)
        var finSeqs = List(k) { intArrayOf(startId) }
        var finScores = FloatArray(k) { NEG }
        var finDone = BooleanArray(k)
        var earlyUnsatisfied = true
        var steps = 0
        while (true) {
            checkCancelled()
            if (preferFinished && curLen == maxLength - 1 && finDone.any { it }) break
            val lp = step.run(IntArray(running.size) { running[it].last() }, parents)
            steps++
            if (curLen == maxLength - 1) { // ForcedEOSTokenLogitsProcessor (+ renormalization)
                lp.fill(Float.NEGATIVE_INFINITY)
                for (b in running.indices) lp[b * vocab + eosId] = 0f
            }
            // c. top 2*num_beams continuations over all beams
            val top = topK(running.size * vocab, keep) { i -> lp[i] + runningScores[i / vocab] }
            val topLp = FloatArray(keep) { lp[top[it]] + runningScores[top[it] / vocab] }
            val topSeqs = List(keep) { running[top[it] / vocab] + (top[it] % vocab) }
            val hits = BooleanArray(keep) { top[it] % vocab == eosId || curLen + 1 >= maxLength }
            // e. next running beams: best non-finished continuations
            val runLp = FloatArray(keep) { topLp[it] + (if (hits[it]) NEG else 0f) }
            val next = topK(keep, k) { runLp[it] }
            running = List(k) { topSeqs[next[it]] }
            runningScores = FloatArray(k) { runLp[next[it]] }
            parents = IntArray(k) { top[next[it]] / vocab }
            // f. finished hypotheses (only the top num_beams candidates may finish)
            val denom = Math.pow((curLen + 1 - promptLen).toDouble(), lengthPenalty.toDouble()).toFloat()
            val finLp = FloatArray(keep) {
                var s = topLp[it] / denom
                if (!earlyUnsatisfied) s += NEG
                if (!(hits[it] && it < k)) s += NEG
                s
            }
            val mergedScores = finScores + finLp
            val mergedSeqs = finSeqs + topSeqs
            val mergedDone = finDone + BooleanArray(keep) { hits[it] && it < k }
            val best = topK(mergedScores.size, k) { mergedScores[it] }
            finSeqs = List(k) { mergedSeqs[best[it]] }
            finScores = FloatArray(k) { mergedScores[best[it]] }
            finDone = BooleanArray(k) { mergedDone[best[it]] }
            // g. stopping
            curLen++
            val bestPossible = runningScores[0] / Math.pow((curLen - promptLen).toDouble(), lengthPenalty.toDouble()).toFloat()
            val minFinished = finScores.min()
            earlyUnsatisfied = earlyUnsatisfied && finDone.indices.any { bestPossible > (if (finDone[it]) minFinished else NEG) }
            if (!(earlyUnsatisfied && !hits.all { it })) break
        }
        val best = finSeqs[0]
        return Output(best.copyOfRange(1, best.size), steps)
    }

    companion object {
        private const val NEG = -1.0e9f

        /** Indices of the k largest scores, best first; ties keep the lower index (torch.topk on CPU). */
        internal inline fun topK(n: Int, k: Int, score: (Int) -> Float): IntArray {
            val idx = IntArray(k) { -1 }
            val sc = FloatArray(k) { Float.NEGATIVE_INFINITY }
            var size = 0
            for (i in 0 until n) {
                val s = score(i)
                if (size == k && !(s > sc[k - 1])) continue
                var pos = if (size < k) size else k - 1
                while (pos > 0 && s > sc[pos - 1]) pos--
                val last = if (size < k) size else k - 1
                for (j in last downTo pos + 1) {
                    sc[j] = sc[j - 1]
                    idx[j] = idx[j - 1]
                }
                sc[pos] = s
                idx[pos] = i
                if (size < k) size++
            }
            return if (size == k) idx else idx.copyOf(size)
        }
    }
}
