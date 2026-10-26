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

    /** Bounded HTML for a website provider; redirects may not leave its verified host. */
    suspend fun html(url: String, host: String): String = withContext(Dispatchers.IO) {
        val conn = open(url, setOf(host))
        conn.useStream { input ->
            if (conn.contentType?.substringBefore(';')?.trim() != "text/html") {
                throw SourceException("The catalog did not return HTML.")
            }
            val body = input.readBytesLimited(2 * 1024 * 1024)
            String(body, Charsets.UTF_8)
        }
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
    suspend fun download(url: String, dest: File, allowedHosts: Set<String>? = null,
                         onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val conn = open(url, allowedHosts)
        val total = conn.contentLengthLong
        conn.useStream { input ->
            if (allowedHosts != null && conn.contentType?.substringBefore(';')?.trim() != "audio/mpeg") {
                throw SourceException("MassTamilan did not return MP3 audio. The link may have expired or been challenged.")
            }
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

    private fun open(url: String, allowedHosts: Set<String>? = null): HttpURLConnection {
        var current = URL(url)
        // HttpURLConnection won't follow a redirect that changes host scheme; archive.org
        // redirects downloads to a storage node, so follow a few by hand.
        repeat(5) {
            if (allowedHosts != null && (current.protocol != "https" || current.host !in allowedHosts ||
                    current.userInfo != null || current.port !in listOf(-1, 443))) {
                throw SourceException("The catalog redirected to an unsupported address.")
            }
            val conn = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                if (allowedHosts != null) useCaches = false
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
                if (allowedHosts != null && code == 403) {
                    throw SourceException("MassTamilan refused this request (HTTP 403). It may require a browser challenge; Motif does not bypass protection.")
                }
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

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw SourceException("The catalog page is too large.")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
