package vn.viettechtrans.app.mt.marian

import android.content.res.AssetManager
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import vn.viettechtrans.app.mt.Direction
import vn.viettechtrans.app.mt.MtException
import vn.viettechtrans.app.mt.MtPackageManifest
import vn.viettechtrans.app.mt.TranslationResult
import vn.viettechtrans.app.mt.Translator
import vn.viettechtrans.app.mt.TranslatorInfo
import vn.viettechtrans.app.mt.TranslatorKind
import vn.viettechtrans.app.mt.TranslatorStatus
import vn.viettechtrans.app.mt.rules.TextRules

/**
 * The Main-Core MT (Phase 03 Student, MarianMT) from `assets/mt/` (docs/contracts/mt_package_v2.md).
 * One [MarianEngine] per direction, loaded on demand and freed by [release] (RAM policy).
 * All engine work runs on one "mt" thread, so loading, translating and releasing never overlap.
 * The text the model receives is the user's text after R01 only: no rule rewrites it (policy v1, R01/R08).
 */
class MarianTranslator(
    private val assets: AssetManager,
    private val manifest: MtPackageManifest,
    private val threads: Int = 2,
    /** Literal ranges that R09 never splits (TextRules.literals); none by default. */
    private val protectedRanges: (String) -> List<IntRange> = { emptyList() },
) : Translator {
    override val info = TranslatorInfo(
        id = "core-mt:${manifest.packageId}",
        kind = TranslatorKind.CORE_MT,
        version = manifest.packageVersion,
        directions = manifest.directions.keys.mapNotNull { id -> Direction.entries.firstOrNull { it.id == id } }.toSet(),
        offlineEvidenceEligible = true,
        artifacts = manifest.files.associate { it.path to it.sha256 },
    )

    private val _status = MutableStateFlow<TranslatorStatus>(TranslatorStatus.NotPrepared)
    override val status: StateFlow<TranslatorStatus> = _status

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mt").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val engines = HashMap<Direction, MarianEngine>() // touched on the "mt" thread only

    override suspend fun prepare(directions: Set<Direction>) = withContext(dispatcher) {
        val missing = directions.filter { it !in engines }
        if (missing.isEmpty()) return@withContext
        _status.value = TranslatorStatus.Preparing(null)
        try {
            missing.forEach { engines[it] = load(it) }
            _status.value = TranslatorStatus.Ready
        } catch (e: Exception) {
            _status.value = TranslatorStatus.Unavailable("Không nạp được mô hình dịch: ${e.message}")
            throw MtException.RuntimeFailure(e)
        }
    }

    override suspend fun translate(direction: Direction, text: String): TranslationResult {
        val job = currentCoroutineContext()[Job]
        return withContext(dispatcher) {
            val engine = engines.getOrPut(direction) { load(direction) }
            val r = try {
                engine.translate(text) { if (job?.isActive == false) throw CancellationException("MT cancelled") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw MtException.RuntimeFailure(e)
            }
            TranslationResult(
                r.text,
                mapOf(
                    "encoder_ms" to r.encoderMs, "decoder_ms" to r.decoderMs, "decode_steps" to r.decodeSteps.toLong(),
                    "input_tokens" to r.inputTokens.toLong(), "output_tokens" to r.outputTokens.toLong(), "segments" to r.segments.toLong(),
                ),
                rawText = r.rawText,
                repeatCleanupApplied = r.repeatCleanupApplied,
                sourceIds = r.sourceIds,
            )
        }
    }

    override fun release(directions: Set<Direction>) {
        executor.execute {
            directions.forEach { engines.remove(it)?.close() }
            if (engines.isEmpty()) _status.value = TranslatorStatus.NotPrepared
        }
    }

    override fun close() {
        release(Direction.entries.toSet())
        executor.shutdown()
    }

    private fun load(direction: Direction): MarianEngine {
        val spec = manifest.directions[direction.id] ?: throw MtException.NotReady("Gói MT không có chiều ${direction.id}")
        val tok = spec.tokenizer
        val tokenizer = MarianTokenizer(
            SentencePiece.load(read(tok.sourceSpm)),
            MarianTokenizer.readVocab(read(tok.vocab)),
            spec.inputPrefix,
            eosId = tok.eosId,
            unkId = tok.unkId,
            padId = tok.padId,
        )
        val g = spec.generation
        return MarianEngine(
            read(spec.model),
            tokenizer,
            GenerationParams(g.numBeams, g.maxLength, g.lengthPenalty, g.decoderStartTokenId, g.eosTokenId),
            // R09: at most 500 source ids per call (the model has 512 positions).
            minOf(spec.inputLengthPolicy.maxInputTokens, TextRules.MAX_SOURCE_IDS),
            threads,
            protectedRanges,
        )
    }

    /** Reads `assets/mt/<path>` into an array of the size the manifest declares (no growth copies). */
    private fun read(path: String): ByteArray {
        val size = manifest.files.firstOrNull { it.path == path }?.bytes ?: throw IOException("$path không có trong manifest")
        val out = ByteArray(Math.toIntExact(size))
        assets.open("mt/$path").use { input ->
            var off = 0
            while (off < out.size) {
                val n = input.read(out, off, out.size - off)
                if (n < 0) throw IOException("$path ngắn hơn manifest ($off/${out.size} B)")
                off += n
            }
            if (input.read() != -1) throw IOException("$path dài hơn manifest")
        }
        return out
    }
}
