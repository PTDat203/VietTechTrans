package vn.viettechtrans.app.mt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * `assets/mt/manifest.json` of an MT package (docs/contracts/mt_package_v1.md §4).
 * Unknown optional fields are ignored; a missing required field fails [parse].
 */
@Serializable
data class MtPackageManifest(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("package_id") val packageId: String,
    @SerialName("package_version") val packageVersion: String,
    @SerialName("created_at_utc") val createdAtUtc: String,
    @SerialName("model_role") val modelRole: String,
    @SerialName("base_model") val baseModel: BaseModel,
    val runtime: Runtime,
    val quantization: Quantization,
    val tokenizer: TokenizerSpec,
    @SerialName("input_normalization") val inputNormalization: String,
    val directions: Map<String, DirectionSpec>,
    val files: List<FileEntry>,
    @SerialName("core_refs") val coreRefs: List<CoreRef> = emptyList(),
    @SerialName("license_notes") val licenseNotes: String,
) {
    @Serializable
    data class BaseModel(
        @SerialName("model_id") val modelId: String,
        val revision: String,
    )

    @Serializable
    data class Runtime(
        val engine: String,
        @SerialName("engine_version") val engineVersion: String,
        @SerialName("execution_provider") val executionProvider: String,
        val abis: List<String>,
    )

    @Serializable
    data class Quantization(
        val method: String,
        val tool: String? = null,
        @SerialName("per_channel") val perChannel: Boolean? = null,
    )

    @Serializable
    data class TokenizerSpec(
        val type: String,
        val file: String,
        @SerialName("sp_piece_count") val spPieceCount: Int,
        @SerialName("vocab_size") val vocabSize: Int,
        @SerialName("add_bos") val addBos: Boolean,
        @SerialName("add_eos") val addEos: Boolean,
        @SerialName("pad_id") val padId: Int,
        @SerialName("eos_id") val eosId: Int,
        @SerialName("unk_id") val unkId: Int,
        @SerialName("decode_skip_ids") val decodeSkipIds: List<Int>,
        @SerialName("decode_skip_id_ranges") val decodeSkipIdRanges: List<List<Int>>,
    )

    @Serializable
    data class DirectionSpec(
        @SerialName("input_prefix") val inputPrefix: String,
        @SerialName("output_control_prefix") val outputControlPrefix: String? = null,
        @SerialName("output_normalization") val outputNormalization: String,
        val files: Map<String, String>,
        val generation: Generation,
        @SerialName("input_length_policy") val inputLengthPolicy: InputLengthPolicy,
        @SerialName("source_checkpoint") val sourceCheckpoint: SourceCheckpoint,
        @SerialName("golden_file") val goldenFile: String,
        val quality: Quality,
    )

    @Serializable
    data class Generation(
        val strategy: String,
        @SerialName("num_beams") val numBeams: Int,
        @SerialName("max_length") val maxLength: Int,
        @SerialName("decoder_start_token_id") val decoderStartTokenId: Int,
        @SerialName("eos_token_id") val eosTokenId: Int,
    )

    @Serializable
    data class InputLengthPolicy(
        @SerialName("max_input_tokens") val maxInputTokens: Int? = null,
        @SerialName("on_exceed") val onExceed: String,
    )

    @Serializable
    data class SourceCheckpoint(
        val path: String,
        @SerialName("sha256_tree") val sha256Tree: String,
    )

    @Serializable
    data class Quality(
        @SerialName("checkpoint_it_test") val checkpointItTest: Scores? = null,
        /** Structure fixed at Phase 04 (quality of the export on IT Validation). */
        val exported: JsonElement? = null,
    )

    @Serializable
    data class Scores(
        @SerialName("chrF++") val chrfPlusPlus: Double,
        @SerialName("sacreBLEU") val sacreBleu: Double,
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
    )

    companion object {
        const val ASSET_PATH = "mt/manifest.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Throws [kotlinx.serialization.SerializationException] or [IllegalArgumentException]. */
        fun parse(text: String): MtPackageManifest = json.decodeFromString(serializer(), text)
    }
}

/** Structural checks of docs/contracts/mt_package_v1.md §7; hashes are checked by tools/check_apk.py. */
object MtPackageValidator {
    const val SUPPORTED_MAJOR = 1

    private val ID_RE = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val SEMVER_RE = Regex("""\d+\.\d+\.\d+([-+][0-9A-Za-z.-]+)?""")
    private val SCHEMA_RE = Regex("""(\d+)\.(\d+)""")
    private val SHA256_RE = Regex("[0-9a-f]{64}")
    private val UTC_RE = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?\+00:00""")
    private val ROLES = setOf("core_mt", "student_mt")
    private val ABIS = setOf("arm64-v8a", "x86_64")
    private val ON_EXCEED = setOf("segment_whitespace")

    /**
     * Returns problems in Vietnamese; empty means valid.
     * [assetSize] returns the size of `assets/mt/<path>` or null when the file is missing.
     */
    fun validate(m: MtPackageManifest, assetSize: (String) -> Long?): List<String> {
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
        check(m.modelRole in ROLES) { "model_role phải là core_mt hoặc student_mt: ${m.modelRole}" }
        check(m.baseModel.modelId.isNotBlank() && m.baseModel.revision.isNotBlank()) { "base_model thiếu model_id/revision" }

        check(m.runtime.engine == "onnxruntime") { "runtime.engine phải là onnxruntime: ${m.runtime.engine}" }
        check(m.runtime.executionProvider == "cpu") { "runtime.execution_provider phải là cpu" }
        check(m.runtime.abis.isNotEmpty() && ABIS.containsAll(m.runtime.abis)) {
            "runtime.abis phải là tập con khác rỗng của $ABIS: ${m.runtime.abis}"
        }

        val tok = m.tokenizer
        check(tok.type == "sentencepiece") { "tokenizer.type phải là sentencepiece: ${tok.type}" }
        check(tok.decodeSkipIdRanges.all { it.size == 2 && it[0] <= it[1] }) { "tokenizer.decode_skip_id_ranges sai dạng [đầu, cuối]" }
        check(m.inputNormalization == MtInputNormalizer.ID) {
            "input_normalization phải là ${MtInputNormalizer.ID}: ${m.inputNormalization}"
        }

        check(m.directions.isNotEmpty()) { "directions rỗng" }
        val referenced = mutableListOf(tok.file, m.licenseNotes)
        for ((id, d) in m.directions) {
            check(Direction.entries.any { it.id == id }) { "directions có chiều lạ: $id" }
            check(d.inputPrefix.isNotEmpty()) { "$id: input_prefix rỗng" }
            check(d.outputNormalization == OutputPrefixStripper.POLICY || d.outputNormalization == "none") {
                "$id: output_normalization không hỗ trợ: ${d.outputNormalization}"
            }
            check("encoder" in d.files && "decoder" in d.files) { "$id: files phải có encoder và decoder" }
            val g = d.generation
            check(g.strategy == "greedy" && g.numBeams == 1) { "$id: generation phải là greedy, num_beams 1" }
            check(g.maxLength > 1) { "$id: generation.max_length phải > 1" }
            check(d.inputLengthPolicy.maxInputTokens == null || d.inputLengthPolicy.maxInputTokens > 0) {
                "$id: max_input_tokens phải > 0 hoặc null"
            }
            check(d.inputLengthPolicy.onExceed in ON_EXCEED) { "$id: on_exceed không hỗ trợ: ${d.inputLengthPolicy.onExceed}" }
            check(SHA256_RE.matches(d.sourceCheckpoint.sha256Tree)) { "$id: source_checkpoint.sha256_tree không hợp lệ" }
            referenced += d.files.values
            referenced += d.goldenFile
        }

        val listed = m.files.associateBy { it.path }
        check(listed.size == m.files.size) { "files[] có path trùng" }
        for (entry in m.files) {
            check(isSafeRelativePath(entry.path)) { "path không an toàn: ${entry.path}" }
            check(entry.bytes > 0) { "${entry.path}: bytes phải > 0" }
            check(SHA256_RE.matches(entry.sha256)) { "${entry.path}: sha256 phải là 64 ký tự hex thường" }
            val actual = assetSize(entry.path)
            check(actual != null) { "thiếu file trong assets/mt: ${entry.path}" }
            check(actual == null || entry.bytes <= 0 || actual == entry.bytes) {
                "${entry.path}: kích thước $actual B khác manifest ${entry.bytes} B"
            }
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
