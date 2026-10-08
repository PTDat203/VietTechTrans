package vn.viettechtrans.app.mt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * `assets/mt/manifest.json` of the MT package (docs/contracts/mt_package_v2.md), written by
 * tools/mt/package_mt.py. Unknown optional fields are ignored; a missing required field fails [parse].
 */
@Serializable
data class MtPackageManifest(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("package_id") val packageId: String,
    @SerialName("package_version") val packageVersion: String,
    @SerialName("created_at_utc") val createdAtUtc: String,
    @SerialName("model_role") val modelRole: String,
    val architecture: String,
    val runtime: Runtime,
    val quantization: JsonElement,
    @SerialName("input_normalization") val inputNormalization: String,
    val directions: Map<String, DirectionSpec>,
    val files: List<FileEntry>,
    @SerialName("core_refs") val coreRefs: List<CoreRef> = emptyList(),
    @SerialName("license_notes") val licenseNotes: String,
) {
    @Serializable
    data class Runtime(
        val engine: String,
        @SerialName("engine_version") val engineVersion: String,
        @SerialName("execution_provider") val executionProvider: String,
        val abis: List<String>,
    )

    @Serializable
    data class TokenizerSpec(
        val type: String,
        @SerialName("source_spm") val sourceSpm: String,
        val vocab: String,
        @SerialName("vocab_size") val vocabSize: Int,
        @SerialName("eos_id") val eosId: Int,
        @SerialName("unk_id") val unkId: Int,
        @SerialName("pad_id") val padId: Int,
    )

    @Serializable
    data class DirectionSpec(
        val model: String,
        val tokenizer: TokenizerSpec,
        @SerialName("input_prefix") val inputPrefix: String,
        val generation: Generation,
        @SerialName("input_length_policy") val inputLengthPolicy: InputLengthPolicy,
        @SerialName("source_checkpoint") val sourceCheckpoint: SourceCheckpoint,
        @SerialName("golden_file") val goldenFile: String,
        val quality: JsonElement? = null,
    )

    @Serializable
    data class Generation(
        val strategy: String,
        @SerialName("num_beams") val numBeams: Int,
        @SerialName("max_length") val maxLength: Int,
        @SerialName("length_penalty") val lengthPenalty: Float,
        @SerialName("early_stopping") val earlyStopping: Boolean,
        @SerialName("decoder_start_token_id") val decoderStartTokenId: Int,
        @SerialName("eos_token_id") val eosTokenId: Int,
        @SerialName("forced_eos_token_id") val forcedEosTokenId: Int,
        @SerialName("bad_words_ids") val badWordsIds: List<List<Int>>,
        @SerialName("renormalize_logits") val renormalizeLogits: Boolean,
    )

    @Serializable
    data class InputLengthPolicy(
        @SerialName("max_input_tokens") val maxInputTokens: Int,
        @SerialName("on_exceed") val onExceed: String,
    )

    @Serializable
    data class SourceCheckpoint(
        @SerialName("run_id") val runId: String,
        val path: String,
        @SerialName("sha256_tree") val sha256Tree: String,
    )

    @Serializable
    data class FileEntry(
        val path: String,
        val bytes: Long,
        val sha256: String,
    )

    @Serializable
    data class CoreRef(
        val path: String,
        val sha256: String,
        val source: String? = null,
    )

    companion object {
        const val ASSET_PATH = "mt/manifest.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Throws [kotlinx.serialization.SerializationException] or [IllegalArgumentException]. */
        fun parse(text: String): MtPackageManifest = json.decodeFromString(serializer(), text)
    }
}

/**
 * Structural checks of docs/contracts/mt_package_v2.md: everything the app's decoder assumes
 * (beam search of transformers 4.57, pad banned, renormalized) must be what the package declares.
 * File hashes are checked by tools/check_apk.py; exact sizes when a file is loaded
 * (MarianTranslator.read), so opening the app never inflates the 180 MB of models.
 */
object MtPackageValidator {
    const val SUPPORTED_MAJOR = 2

    private val ID_RE = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val SEMVER_RE = Regex("""\d+\.\d+\.\d+([-+][0-9A-Za-z.-]+)?""")
    private val SCHEMA_RE = Regex("""(\d+)\.(\d+)""")
    private val SHA256_RE = Regex("[0-9a-f]{64}")
    private val UTC_RE = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?\+00:00""")
    private val ABIS = setOf("arm64-v8a", "x86_64")
    private val PREFIX = mapOf("en_to_vi" to ">>vie<<", "vi_to_en" to "")

    /**
     * Returns problems in Vietnamese; empty means valid.
     * [exists] tells whether `assets/mt/<path>` is in the APK.
     */
    fun validate(m: MtPackageManifest, exists: (String) -> Boolean): List<String> {
        val problems = mutableListOf<String>()
        fun check(ok: Boolean, message: () -> String) {
            if (!ok) problems += message()
        }

        val schema = SCHEMA_RE.matchEntire(m.schemaVersion)
        check(schema != null && schema.groupValues[1].toInt() == SUPPORTED_MAJOR) {
            "schema_version ${m.schemaVersion}: app chỉ nhận major $SUPPORTED_MAJOR"
        }
        check(ID_RE.matches(m.packageId)) { "package_id không hợp lệ: ${m.packageId}" }
        check(SEMVER_RE.matches(m.packageVersion)) { "package_version không phải semver: ${m.packageVersion}" }
        check(UTC_RE.matches(m.createdAtUtc)) { "created_at_utc phải là ISO-8601 +00:00: ${m.createdAtUtc}" }
        check(m.modelRole == "main_core") { "model_role phải là main_core: ${m.modelRole}" }
        check(m.architecture == "MarianMT") { "architecture phải là MarianMT: ${m.architecture}" }
        check(m.runtime.engine == "onnxruntime") { "runtime.engine phải là onnxruntime: ${m.runtime.engine}" }
        check(m.runtime.executionProvider == "cpu") { "runtime.execution_provider phải là cpu" }
        check(m.runtime.abis.isNotEmpty() && ABIS.containsAll(m.runtime.abis)) {
            "runtime.abis phải là tập con khác rỗng của $ABIS: ${m.runtime.abis}"
        }
        check(m.inputNormalization == MtInputNormalizer.ID) {
            "input_normalization phải là ${MtInputNormalizer.ID}: ${m.inputNormalization}"
        }

        check(m.directions.isNotEmpty()) { "directions rỗng" }
        val referenced = mutableListOf(m.licenseNotes)
        for ((id, d) in m.directions) {
            check(id in PREFIX) { "directions có chiều lạ: $id" }
            check(PREFIX[id] == null || d.inputPrefix == PREFIX[id]) { "$id: input_prefix phải là \"${PREFIX[id]}\"" }
            val t = d.tokenizer
            check(t.type == "marian_sentencepiece_unigram") { "$id: tokenizer.type không hỗ trợ: ${t.type}" }
            check(listOf(t.eosId, t.unkId, t.padId).all { it in 0 until t.vocabSize }) { "$id: id đặc biệt nằm ngoài vocab" }
            val g = d.generation
            check(g.strategy == "beam_search" && g.numBeams >= 1) { "$id: generation phải là beam_search" }
            check(g.maxLength > 1) { "$id: generation.max_length phải > 1" }
            check(!g.earlyStopping) { "$id: app chỉ hỗ trợ early_stopping=false" }
            check(g.forcedEosTokenId == g.eosTokenId) { "$id: forced_eos_token_id phải bằng eos_token_id" }
            check(g.badWordsIds == listOf(listOf(t.padId)) && g.renormalizeLogits) {
                "$id: graph chỉ cấm pad và chuẩn hoá lại log-prob; bad_words_ids/renormalize_logits khác"
            }
            check(d.inputLengthPolicy.maxInputTokens > 0) { "$id: max_input_tokens phải > 0" }
            check(d.inputLengthPolicy.onExceed == "split_sentences") { "$id: on_exceed không hỗ trợ: ${d.inputLengthPolicy.onExceed}" }
            check(SHA256_RE.matches(d.sourceCheckpoint.sha256Tree)) { "$id: source_checkpoint.sha256_tree không hợp lệ" }
            referenced += listOf(d.model, t.sourceSpm, t.vocab, d.goldenFile)
        }

        val listed = m.files.associateBy { it.path }
        check(listed.size == m.files.size) { "files[] có path trùng" }
        for (entry in m.files) {
            check(isSafeRelativePath(entry.path)) { "path không an toàn: ${entry.path}" }
            check(entry.bytes > 0) { "${entry.path}: bytes phải > 0" }
            check(SHA256_RE.matches(entry.sha256)) { "${entry.path}: sha256 phải là 64 ký tự hex thường" }
            check(exists(entry.path)) { "thiếu file trong assets/mt: ${entry.path}" }
        }
        for (path in referenced.distinct()) {
            check(path in listed) { "file được tham chiếu nhưng không có trong files[]: $path" }
        }
        for (ref in m.coreRefs) {
            check(SHA256_RE.matches(ref.sha256)) { "core_refs ${ref.path}: sha256 không hợp lệ" }
        }
        return problems
    }

    /** POSIX relative path without "..", leading "/", backslashes or empty segments. */
    fun isSafeRelativePath(path: String): Boolean =
        path.isNotEmpty() &&
            !path.startsWith("/") &&
            '\\' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}
