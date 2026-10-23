package vn.viettechtrans.app.mt

import kotlinx.coroutines.flow.StateFlow

enum class TranslatorKind {
    /** Main-Core MT package (Phase 03 Student, packaged in Phase 04). */
    CORE_MT,
}

data class TranslatorInfo(
    /** e.g. "core-mt:<package_id>"; written into history and evidence. */
    val id: String,
    val kind: TranslatorKind,
    val version: String,
    val directions: Set<Direction>,
    val offlineEvidenceEligible: Boolean,
    /** Artifact path -> sha256, copied into evidence. */
    val artifacts: Map<String, String> = emptyMap(),
)

sealed interface TranslatorStatus {
    data object NotPrepared : TranslatorStatus
    data class Preparing(val progress: Float?) : TranslatorStatus
    data object Ready : TranslatorStatus

    data class Unavailable(val reasonVi: String) : TranslatorStatus
}

/** [details]: optional engine timings/counters such as encoder_ms or decode_steps. */
data class TranslationResult(
    val text: String,
    val details: Map<String, Long> = emptyMap(),
    /** The decoded output before the app's repeat cleanup (policy R11); null when the engine does not report it. */
    val rawText: String? = null,
    /** R11 changed the output (policy flag repeat_cleanup_applied). */
    val repeatCleanupApplied: Boolean = false,
    /** Token ids of every source segment (policy §8 evidence: source_ids). */
    val sourceIds: List<IntArray> = emptyList(),
)

sealed class MtException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotReady(message: String) : MtException(message)
    class InputTooLong(val tokens: Int, val max: Int) : MtException("Câu quá dài ($tokens/$max token)")
    class RuntimeFailure(cause: Throwable) : MtException("Lỗi bộ dịch", cause)
}

/**
 * Boundary between the app and any MT engine. The pipeline normalizes input with
 * [MtInputNormalizer], measures `translate()` and runs the watchdog; the translator
 * owns prefixes, tokenization and over-length handling
 * (docs/contracts/mt_package_v2.md).
 */
interface Translator : AutoCloseable {
    val info: TranslatorInfo
    val status: StateFlow<TranslatorStatus>

    /** Idempotent: load sessions (or download models in the dev flavor) for [directions]. */
    suspend fun prepare(directions: Set<Direction>)

    /** [text] is already normalized. Implementations should stay cancellable between decode steps. */
    suspend fun translate(direction: Direction, text: String): TranslationResult

    /** Free native memory for [directions] (RAM policy). */
    fun release(directions: Set<Direction>)
}
