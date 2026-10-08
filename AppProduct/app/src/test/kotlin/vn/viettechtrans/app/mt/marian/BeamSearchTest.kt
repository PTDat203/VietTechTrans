package vn.viettechtrans.app.mt.marian

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BeamSearchTest {
    @Test
    fun topKIsSortedAndPrefersLowerIndexOnTies() {
        val s = floatArrayOf(0.1f, 0.9f, 0.5f, 0.9f, -1f, 0.5f)
        assertArrayEquals(intArrayOf(1, 3, 2, 5), BeamSearch.topK(s.size, 4) { s[it] })
    }

    /** Toy model: token 2 is best until step 3, then EOS (0) wins; beams must agree on "2 2 2 EOS". */
    @Test
    fun stopsAtEosAndReturnsBestHypothesis() {
        val vocab = 4
        var calls = 0
        val out = BeamSearch(startId = 3, eosId = 0, numBeams = 2, maxLength = 20).run(vocab, { tokens, _ ->
            calls++
            val step = calls
            FloatArray(tokens.size * vocab) { i ->
                val tok = i % vocab
                when {
                    tok == 3 -> Float.NEGATIVE_INFINITY // pad/start is banned by the graph
                    step < 4 -> if (tok == 2) -0.1f else -3f
                    else -> if (tok == 0) -0.05f else -4f
                }
            }
        })
        assertArrayEquals(intArrayOf(2, 2, 2, 0), out.ids)
    }

    @Test
    fun forcedEosAtMaxLength() {
        val vocab = 3
        val out = BeamSearch(startId = 2, eosId = 0, numBeams = 2, maxLength = 4).run(vocab, { tokens, _ ->
            FloatArray(tokens.size * vocab) { i -> if (i % vocab == 1) -0.01f else -5f }
        })
        assertEquals(3, out.ids.size)
        assertEquals(0, out.ids.last())
    }

    /**
     * Toy model where EOS right away (-1.0) is the only complete sentence and token 1 then repeats at
     * -0.01: HF's forced EOS at max_length makes the repetition win on its average log-prob; the app's
     * preferFinished returns the complete sentence instead.
     */
    @Test
    fun preferFinishedKeepsTheCompleteSentenceAtTheCap() {
        val vocab = 3
        fun search(preferFinished: Boolean) = BeamSearch(startId = 2, eosId = 0, numBeams = 2, maxLength = 8, preferFinished = preferFinished)
            .run(vocab, { tokens, _ ->
                FloatArray(tokens.size * vocab) { i ->
                    val last = tokens[i / vocab]
                    when (i % vocab) {
                        2 -> Float.NEGATIVE_INFINITY
                        0 -> if (last == 2) -1.0f else -6f
                        else -> if (last == 2) -1.2f else -0.01f
                    }
                }
            })
        val hf = search(preferFinished = false)
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 1, 1, 0), hf.ids)
        val app = search(preferFinished = true)
        assertArrayEquals(intArrayOf(0), app.ids)
        assertEquals(hf.steps - 1, app.steps)
    }
}
