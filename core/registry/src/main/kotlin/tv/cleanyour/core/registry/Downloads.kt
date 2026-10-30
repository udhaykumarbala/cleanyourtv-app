package tv.cleanyour.core.registry

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Sha256
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// How an HttpFetcher reports a request it refused or could not make. Anything else it throws that
// is an IOException means the network is gone (OFFLINE).
class FetchException(val failure: FetchFailure, val httpCode: Int? = null, message: String, cause: Throwable? = null) : IOException(message, cause)

// A small answer read whole: the status code, at most `max` bytes of a 2xx body (null when there is none, or
// for any other code), and whether the body was longer than that (`cut`; a Content-Length over `max` is not
// read at all).
class SmallBody(val code: Int, val bytes: ByteArray?, val cut: Boolean)

// 0.4.8 (issues #818, #2486): a GET whose body is read on `io` too, in the same context as the request.
// HttpResponse.body is the live connection, so reading it waits on the network; UrlConnectionFetcher.get
// leaves its own IO context once the headers are in, and a caller on the main thread (a screen's scope, as
// Settings' "Check for updates") then read the body there, which Android refuses with
// NetworkOnMainThreadException, every time, on every phone. Anything that wants a whole small answer uses
// this instead of reading resp.body itself; a download that streams a large body (ResumableArtifactFetcher)
// runs in its own IO context.
suspend fun HttpFetcher.getSmall(url: String, max: Int, io: CoroutineDispatcher = Dispatchers.IO): SmallBody = withContext(io) {
    val r = get(url, null, null)
    val body = r.body
    when {
        body == null -> SmallBody(r.code, null, false)
        r.code !in 200..299 -> { body.close(); SmallBody(r.code, null, false) }
        r.length != null && r.length > max -> { body.close(); SmallBody(r.code, null, true) }
        else -> body.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var cut = false
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > max) { out.write(buf, 0, max - out.size()); cut = true; break }
                out.write(buf, 0, n)
            }
            SmallBody(r.code, out.toByteArray(), cut)
        }
    }
}

// Resumable partial downloads, keyed by the sha256 the index promises (ARCHITECTURE 5.1:
// cache/artifacts/<sha256>.part plus <sha256>.etag). DiskBlobStore implements it next to BlobStore,
// so a finished partial becomes a blob by a rename.
interface PartialStore {
    fun partialLength(sha256: Sha256): Long
    fun partialEtag(sha256: Sha256): String?
    fun startPartial(sha256: Sha256, etag: String?)          // empty the partial and remember the validator
    fun appendPartial(sha256: Sha256): OutputStream
    fun readPartial(sha256: Sha256): InputStream
    suspend fun commitPartial(sha256: Sha256, size: Long): BlobRef   // re-checks size and sha256, then moves it into the store
    fun dropPartial(sha256: Sha256)
}
