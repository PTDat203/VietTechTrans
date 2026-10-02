package vn.viettechtrans.app.mt

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.mlkit.nl.translate.Translator as MlKitClient

/**
 * Temporary translator for the `dev` flavor only: ML Kit on-device translation.
 * Needs the network once to download each language model (~30 MB), then works
 * offline. Never counted as offline evidence (the offline build has no ML Kit).
 */
class MlKitTranslator : Translator {
    override val info = TranslatorInfo(
        id = "mlkit-dev:17.0.3",
        kind = TranslatorKind.MLKIT_DEV,
        version = "17.0.3",
        directions = Direction.entries.toSet(),
        offlineEvidenceEligible = false,
    )

    private val _status = MutableStateFlow<TranslatorStatus>(TranslatorStatus.NotPrepared)
    override val status: StateFlow<TranslatorStatus> = _status

    private val clients = mutableMapOf<Direction, MlKitClient>()

    private fun client(direction: Direction): MlKitClient = synchronized(clients) {
        clients.getOrPut(direction) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(code(direction.source))
                    .setTargetLanguage(code(direction.target))
                    .build(),
            )
        }
    }

    override suspend fun prepare(directions: Set<Direction>) {
        _status.value = TranslatorStatus.Preparing(null)
        try {
            for (d in directions) client(d).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            _status.value = TranslatorStatus.Ready
        } catch (e: Exception) {
            _status.value = TranslatorStatus.Unavailable("Chưa tải được gói ML Kit (cần mạng một lần)")
            throw MtException.NetworkRequired()
        }
    }

    override suspend fun translate(direction: Direction, text: String): TranslationResult {
        val out = try {
            client(direction).translate(text).await()
        } catch (e: Exception) {
            throw MtException.RuntimeFailure(e)
        }
        return TranslationResult(out)
    }

    override fun release(directions: Set<Direction>) {
        synchronized(clients) { directions.forEach { clients.remove(it)?.close() } }
    }

    override fun close() = release(Direction.entries.toSet())

    private fun code(lang: Lang): String = when (lang) {
        Lang.VI -> TranslateLanguage.VIETNAMESE
        Lang.EN -> TranslateLanguage.ENGLISH
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
