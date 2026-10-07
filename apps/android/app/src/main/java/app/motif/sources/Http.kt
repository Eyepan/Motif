package app.motif.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Plain HttpURLConnection: three calls don't justify a networking library. */
object Http {
    private const val USER_AGENT = "Motif/0.1 (Android; https://github.com/Eyepan/Motif)"

    fun url(base: String, params: List<Pair<String, String>>): String =
        base + "?" + params.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        open(url).useStream { it.bufferedReader().readText() }
    }

    /** Small bodies only (images); null when larger than [maxBytes]. */
    suspend fun bytes(url: String, maxBytes: Int = 10 * 1024 * 1024): ByteArray? = withContext(Dispatchers.IO) {
        open(url).useStream { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > maxBytes) return@useStream null
            }
            out.toByteArray()
        }
    }

    /** Streams [url] into [dest], reporting bytes done and the total (-1 when unknown). Cancellable. */
    suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val conn = open(url)
        val total = conn.contentLengthLong
        conn.useStream { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var reported = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - reported >= 512 * 1024) {
                        reported = done
                        onProgress(done, total)
                    }
                }
                onProgress(done, total)
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        // HttpURLConnection won't follow a redirect that changes host scheme; archive.org
        // redirects downloads to a storage node, so follow a few by hand.
        repeat(5) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw SourceException("The catalog sent a broken redirect.")
                conn.disconnect()
                current = URL(current, location)
                return@repeat
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw SourceException("The catalog returned HTTP $code.")
            }
            return conn
        }
        throw SourceException("Too many redirects.")
    }

    private inline fun <T> HttpURLConnection.useStream(block: (java.io.InputStream) -> T): T =
        try {
            inputStream.use(block)
        } finally {
            disconnect()
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
