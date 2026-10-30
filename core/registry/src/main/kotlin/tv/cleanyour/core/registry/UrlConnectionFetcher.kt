package tv.cleanyour.core.registry

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.core.trust.DownloadHosts
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory

// HttpFetcher over HttpURLConnection (ARCHITECTURE D12, D41). Pure java.net, so it lives in this
// JVM module and runs unchanged on the phone, where the platform's TLS stack and the app's network
// security config (system trust anchors only, cleartext off) apply to it.
//
// - Every URL, the first and each redirect target, must pass urlAllowed (https, an allowed host,
//   port 443) or the request fails with HOST_NOT_ALLOWED before a connection is opened.
// - Redirects are followed by hand, at most maxRedirects, never automatically.
// - etag without rangeFrom sends If-None-Match (a 304 comes back as code 304, no body);
//   rangeFrom sends "Range: bytes=N-" plus If-Range with the etag, so a changed file answers 200.
// - Accept-Encoding is identity so lengths and ranges are byte exact; the User-Agent is a fixed
//   generic string (no phone model, no app version).
// - DNS, connect, timeout and reset errors become FetchException(OFFLINE); a TLS failure (a
//   certificate that does not chain to the system anchors, as with a proxy's user CA) becomes
//   FetchException(HTTP_ERROR) with no code: the request never reached GitHub.
// - The returned body disconnects the connection when it is closed; the caller must close it.
// - The body is the live connection: only the request and the headers run on `io`, so reading the body
//   blocks on the network wherever the caller is. Read it on an IO dispatcher (getSmall does, for a small
//   answer); on the phone's main thread Android refuses it (issues #818, #2486).
class UrlConnectionFetcher(
    private val urlAllowed: (String) -> Boolean = { DownloadHosts.isAllowed(it) },
    private val sslSocketFactory: SSLSocketFactory? = null,   // null: the platform default
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxRedirects: Int = 5,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : HttpFetcher {

    override suspend fun get(url: String, etag: String?, rangeFrom: Long?): HttpResponse = withContext(io) {
        require(rangeFrom == null || rangeFrom >= 0)
        var current = url
        for (hop in 0..maxRedirects) {
            if (!urlAllowed(current)) throw FetchException(FetchFailure.HOST_NOT_ALLOWED, null, "host not allowed: ${hostOf(current)}")
            val conn = open(current)
            try {
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept-Encoding", "identity")
                if (rangeFrom != null) {
                    conn.setRequestProperty("Range", "bytes=$rangeFrom-")
                    if (etag != null) conn.setRequestProperty("If-Range", etag)
                } else if (etag != null) {
                    conn.setRequestProperty("If-None-Match", etag)
                }
                val code = conn.responseCode
                if (code in REDIRECTS) {
                    val location = conn.getHeaderField("Location")
                        ?: throw FetchException(FetchFailure.HTTP_ERROR, code, "redirect without Location")
                    conn.disconnect()
                    current = try { URL(URL(current), location).toString() } catch (e: Exception) {
                        throw FetchException(FetchFailure.HOST_NOT_ALLOWED, null, "bad redirect target")
                    }
                    continue
                }
                val length = conn.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0 }
                val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
                return@withContext HttpResponse(code, conn.getHeaderField("ETag"), stream?.let { DisconnectingStream(it, conn) }, length)
                    .also { if (stream == null) conn.disconnect() }
            } catch (e: FetchException) {
                conn.disconnect(); throw e
            } catch (e: SSLException) {
                // 0.4.7 (issue #818): the TLS stack's own words too (a Certificate Transparency or trust anchor
                // refusal reads differently), cut short and on one line, for the diagnostics log.
                conn.disconnect(); throw FetchException(FetchFailure.HTTP_ERROR, null, "TLS failed: ${e.javaClass.simpleName}${tlsWords(e)}", e)
            } catch (e: UnknownHostException) {
                conn.disconnect(); throw FetchException(FetchFailure.OFFLINE, null, "no DNS answer", e)
            } catch (e: ConnectException) {
                conn.disconnect(); throw FetchException(FetchFailure.OFFLINE, null, "cannot connect", e)
            } catch (e: NoRouteToHostException) {
                conn.disconnect(); throw FetchException(FetchFailure.OFFLINE, null, "no route", e)
            } catch (e: SocketTimeoutException) {
                conn.disconnect(); throw FetchException(FetchFailure.OFFLINE, null, "timed out", e)
            } catch (e: IOException) {
                conn.disconnect(); throw FetchException(FetchFailure.OFFLINE, null, "connection failed: ${e.javaClass.simpleName}", e)
            }
        }
        throw FetchException(FetchFailure.HTTP_ERROR, null, "more than $maxRedirects redirects")
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection()
        if (c !is HttpsURLConnection) throw FetchException(FetchFailure.HOST_NOT_ALLOWED, null, "not https")
        sslSocketFactory?.let { c.sslSocketFactory = it }
        return c
    }

    private class DisconnectingStream(input: InputStream, private val conn: HttpURLConnection) : FilterInputStream(input) {
        override fun close() { try { super.close() } finally { conn.disconnect() } }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        // The innermost cause's message, printable ASCII, at most 120 characters; empty when there is none.
        internal fun tlsWords(e: Throwable): String {
            var t: Throwable = e
            while (t.cause != null && t.cause !== t) t = t.cause!!
            val m = (t.message ?: e.message)?.map { if (it in ' '..'~') it else ' ' }?.joinToString("")?.trim()?.take(120)
            return if (m.isNullOrEmpty()) "" else ": $m"
        }
        private fun hostOf(url: String): String = try { URL(url).host } catch (e: Exception) { "?" }
    }
}
