package vn.viettechtrans.app.mt

import kotlinx.coroutines.flow.StateFlow

enum class TranslatorKind {
    /** Core/Student MT package (Phase 04); the only kind that can be offline evidence. */
    CORE_MT,

    /** Google ML Kit Translate, `dev` flavor only; never evidence. */
    MLKIT_DEV,
}

data class TranslatorInfo(
    /** e.g. "core-mt:<package_id>" or "mlkit-dev:17.0.3"; written into history and evidence. */
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

    /** ML Kit model not downloaded yet (dev flavor). */
    data object NeedsDownload : TranslatorStatus
    data class Unavailable(val reasonVi: String) : TranslatorStatus
}

/** [details]: optional engine timings/counters such as encoder_ms or decode_steps. */
data class TranslationResult(
    val text: String,
    val details: Map<String, Long> = emptyMap(),
)

sealed class MtException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotReady(message: String) : MtException(message)
    class NetworkRequired : MtException("Bản dev cần mạng một lần để tải gói ML Kit")
    class InputTooLong(val tokens: Int, val max: Int) : MtException("Câu quá dài ($tokens/$max token)")
    class RuntimeFailure(cause: Throwable) : MtException("Lỗi bộ dịch", cause)
}

/**
 * Boundary between the app and any MT engine. The pipeline normalizes input with
 * [MtInputNormalizer], measures `translate()` and runs the watchdog; the translator
 * owns prefixes, tokenization and over-length handling
 * (docs/contracts/mt_package_v1.md §5.2).
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
