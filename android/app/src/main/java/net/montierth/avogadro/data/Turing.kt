package net.montierth.avogadro.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job

/** Turing, the app that runs Gemma 4 on the phone's NPU. When it's installed, answers are written on
 *  the phone instead of by the instance's model (README, "Answers written on the phone"). Turing loads
 *  its model only when asked and lets it go a minute after the last request. */
object Turing {
    private const val AUTHORITY = "net.montierth.gemmanpu.api"
    private const val PERMISSION = "net.montierth.gemmanpu.permission.RUN_INFERENCE"
    private val URI: Uri = Uri.parse("content://$AUTHORITY")

    /** Installed, and Avogadro was granted its API (Turing has to be installed first for that). */
    fun available(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED &&
            context.packageManager.resolveContentProvider(AUTHORITY, 0) != null

    /** Starts loading Turing's model; returns at once. */
    fun warm(context: Context) {
        runCatching { context.contentResolver.call(URI, "warm", null, null) }
    }

    /** The model's reply to [user] under [system], as the whole text so far each time more arrives. */
    fun write(context: Context, system: String, user: String): Flow<String> = flow {
        val uri = URI.buildUpon().appendPath("generate")
            .appendQueryParameter("prompt", user)
            .appendQueryParameter("system", system)
            // The instance's own settings for its model.
            .appendQueryParameter("max_tokens", "200")
            .appendQueryParameter("temperature", "0.1")
            .build()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Turing returned no stream")
        // A new search cancels this collector; closing the stream ends Turing's run.
        val stop = currentCoroutineContext().job.invokeOnCompletion { runCatching { input.close() } }
        try {
            input.use {
                val bytes = ByteArrayOutputStream()
                val chunk = ByteArray(1024)
                while (true) {
                    val n = it.read(chunk)
                    if (n < 0) break
                    bytes.write(chunk, 0, n)
                    // A character split across reads decodes as U+FFFD until the rest of it comes.
                    emit(bytes.toString(Charsets.UTF_8.name()).trimEnd('�'))
                }
                emit(bytes.toString(Charsets.UTF_8.name()))
            }
        } finally {
            stop.dispose()
        }
    }.flowOn(Dispatchers.IO)
}
