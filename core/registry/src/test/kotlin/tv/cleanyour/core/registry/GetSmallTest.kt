package tv.cleanyour.core.registry

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.Executors
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 0.4.8 (issues #818, #2486): a small answer's body is read on the IO dispatcher with the request, never on the
// caller's thread. A body read on the phone's main thread throws NetworkOnMainThreadException; here a body that
// throws when read on the "main" thread stands in for it.
class GetSmallTest {
    private val main = Executors.newSingleThreadExecutor { Thread(it, "main") }.asCoroutineDispatcher()

    private class OnlyOffMain(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check(Thread.currentThread().name != "main") { "body read on the main thread" }
            return super.read(b, off, len)
        }
    }

    private class Answer(val code: Int, val body: () -> InputStream?, val length: Long? = null) : HttpFetcher {
        override suspend fun get(url: String, etag: String?, rangeFrom: Long?) = HttpResponse(code, null, body(), length)
    }

    @Test fun theBodyIsReadOffTheCallersThread() = runBlocking {
        val http = Answer(200, { OnlyOffMain("""{"tag_name":"v0.4.8"}""".encodeToByteArray()) })
        val r = withContext(main) { http.getSmall("https://api.github.com/x", 1024) }
        assertEquals(200, r.code)
        assertEquals("""{"tag_name":"v0.4.8"}""", r.bytes?.decodeToString())
        assertFalse(r.cut)
    }

    @After fun closeMain() = main.close()

    @Test fun aLongBodyIsCutAtMaxAndAnErrorHasNone() = runBlocking {
        val long = Answer(200, { ByteArrayInputStream(ByteArray(100) { it.toByte() }) })
        val r = long.getSmall("https://api.github.com/x", 40)
        assertTrue(r.cut)
        assertContentEquals(ByteArray(40) { it.toByte() }, r.bytes)
        val declared = Answer(200, { ByteArrayInputStream(ByteArray(100)) }, 100L).getSmall("https://api.github.com/x", 40)
        assertTrue(declared.cut)
        assertNull(declared.bytes)
        val error = Answer(403, { ByteArrayInputStream("""{"message":"API rate limit exceeded"}""".encodeToByteArray()) }).getSmall("https://api.github.com/x", 1024)
        assertEquals(403, error.code)
        assertNull(error.bytes)
        assertNull(Answer(304, { null }).getSmall("https://api.github.com/x", 1024).bytes)
    }
}
