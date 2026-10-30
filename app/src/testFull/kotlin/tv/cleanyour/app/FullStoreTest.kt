package tv.cleanyour.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.cleanyour.core.flows.StoreRules
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.registry.HttpFetcher
import tv.cleanyour.core.registry.HttpResponse
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.trust.DownloadHosts
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The GitHub and F-Droid build (STORES-PLAN 1): the update check, the add-ons, the GitHub hosts
// and the "updates" choice, exactly as before the store flavours.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FullStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val graphs = mutableListOf<AppGraph>()
    private fun graph(): AppGraph = AppGraph(context).also { graphs += it }

    @After fun closeGraphs() { graphs.forEach { it.close() } }

    private class OneAnswer(val code: Int, val body: String) : HttpFetcher {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, etag: String?, rangeFrom: Long?): HttpResponse {
            urls += url
            return HttpResponse(code, null, ByteArrayInputStream(body.encodeToByteArray()), body.length.toLong())
        }
    }

    @Test fun thisIsTheFullBuild() {
        assertEquals("full", Store.flavour)
        assertEquals(StoreRules.FULL, Store.rules)
        assertEquals(DownloadHosts.ALL, Store.hosts)
        assertTrue(Store.updatesMayGoOff)
        val g = graph()
        assertSame(Store, g.store)
        assertIs<InstallableOnlyFetcher>(g.seams.fetcher)
        assertEquals(StoreRules.FULL, g.manageDeps.store)
        assertEquals(StoreRules.FULL, g.setupDeps.store)
        for (h in DownloadHosts.ALL) assertTrue(DownloadHosts.isAllowed("https://$h/x", Store.hosts), h)
    }

    @Test fun theUpdateCheckAsksGithubForTheLatestRelease() = runBlocking {
        val newer = OneAnswer(200, """{"tag_name": "v0.4.0", "name": "x"}""")
        assertEquals("0.4.0", Store.updateChecker(newer).latestNewerThan("0.3.0"))
        assertEquals(listOf(Store.RELEASES_URL), newer.urls)
        assertEquals("", Store.updateChecker(OneAnswer(200, """{"tag_name":"v0.3.0"}""")).latestNewerThan("0.3.0"))
        assertEquals(null, Store.updateChecker(OneAnswer(404, "")).latestNewerThan("0.3.0"))
        assertEquals("https://github.com/udhaykumarbala/cleanyourtv-app/releases", Store.releasePage)
    }

    // 0.4.7 (issue #818): the API's 60 requests an hour per address run out behind a shared address (CGNAT, a VPN),
    // and it answers 403. The releases feed on github.com has no such limit; its newest tag without a hyphen is the
    // latest release. When neither answers, the diagnostics log says why for each.
    private class ByUrl(val answers: Map<String, Any>) : HttpFetcher {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, etag: String?, rangeFrom: Long?): HttpResponse {
            urls += url
            return when (val a = answers[url]) {
                is Exception -> throw a
                is Pair<*, *> -> (a.second as String).let { HttpResponse(a.first as Int, null, ByteArrayInputStream(it.encodeToByteArray()), it.length.toLong()) }
                else -> HttpResponse(404, null, null, null)
            }
        }
    }
    private val feed = """<feed><entry><link rel="alternate" type="text/html" href="https://github.com/udhaykumarbala/cleanyourtv-app/releases/tag/v0.4.7-beta1"/></entry>
        <entry><link rel="alternate" type="text/html" href="https://github.com/udhaykumarbala/cleanyourtv-app/releases/tag/v0.4.6"/></entry>
        <entry><link rel="alternate" type="text/html" href="https://github.com/udhaykumarbala/cleanyourtv-app/releases/tag/v0.4.5"/></entry></feed>"""

    @Test fun aRateLimitedApiFallsBackToTheReleasesFeed() = runBlocking {
        val lines = mutableListOf<String>()
        val http = ByUrl(mapOf(Store.RELEASES_URL to (403 to """{"message":"API rate limit exceeded"}"""), Store.FEED_URL to (200 to feed)))
        assertEquals("0.4.6", Store.updateChecker(http) { lines += it }.latestNewerThan("0.4.5"))
        assertEquals(listOf(Store.RELEASES_URL, Store.FEED_URL), http.urls)
        assertEquals(listOf("settings: update check used the releases feed (api HTTP 403)"), lines)
        assertEquals("", Store.updateChecker(http) {}.latestNewerThan("0.4.6"))
        // A test build is offered its own release from the feed too, never the one before it.
        assertEquals("0.4.6", Store.updateChecker(http) {}.latestNewerThan("0.4.6-beta3"))
        assertEquals(null, Store.latestInFeed("<feed></feed>"))
    }

    @Test fun whenNeitherAnswersTheLogSaysWhy() = runBlocking {
        val lines = mutableListOf<String>()
        val tls = tv.cleanyour.core.registry.FetchException(tv.cleanyour.core.registry.FetchFailure.HTTP_ERROR, null, "TLS failed: SSLHandshakeException: chain refused")
        val http = ByUrl(mapOf(Store.RELEASES_URL to tls, Store.FEED_URL to (429 to "")))
        assertEquals(null, Store.updateChecker(http) { lines += it }.latestNewerThan("0.4.5"))
        assertEquals(listOf("settings: update check failed: api http_error (TLS failed: SSLHandshakeException: chain refused), feed HTTP 429"), lines)
        // The API's answer is used first when it has a tag: one request.
        val ok = ByUrl(mapOf(Store.RELEASES_URL to (200 to """{"tag_name":"v0.4.6"}""")))
        assertEquals("0.4.6", Store.updateChecker(ok) {}.latestNewerThan("0.4.5"))
        assertEquals(listOf(Store.RELEASES_URL), ok.urls)
    }

    // 0.4.8 (issues #818, #2486): Settings asks from the screen's scope, on the main thread, and the body used to be
    // read there: Android refused it (NetworkOnMainThreadException), so the check failed every time, API and feed.
    // A body that throws when read on the "main" thread stands in for that.
    @Test fun theCheckReadsTheAnswerOffTheMainThread() = runBlocking {
        class OffMainOnly(bytes: ByteArray) : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (Thread.currentThread().name == "main-ui") throw IllegalStateException("NetworkOnMainThreadException")
                return super.read(b, off, len)
            }
        }
        val http = object : HttpFetcher {
            override suspend fun get(url: String, etag: String?, rangeFrom: Long?): HttpResponse =
                HttpResponse(200, null, OffMainOnly("""{"tag_name":"v0.4.8"}""".encodeToByteArray()), null)
        }
        val lines = mutableListOf<String>()
        val ui = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "main-ui") }.asCoroutineDispatcher()
        try {
            assertEquals("0.4.8", withContext(ui) { Store.updateChecker(http) { lines += it }.latestNewerThan("0.4.7") })
            assertEquals(emptyList<String>(), lines)
        } finally { ui.close() }
    }

    // Test build: 0.3.5-test1 is offered 0.3.5 and later, never the 0.3.4 it installs over.
    @Test fun aTestBuildIsOfferedOnlyANewerRelease() = runBlocking {
        fun latest(tag: String, current: String) = runBlocking { Store.updateChecker(OneAnswer(200, "{\"tag_name\":\"v$tag\"}")).latestNewerThan(current) }
        assertEquals("", latest("0.3.4", "0.3.5-test1"))
        assertEquals("0.3.5", latest("0.3.5", "0.3.5-test1"))
        assertEquals("0.4.0", latest("0.4.0", "0.3.5-test1"))
        assertEquals("0.3.4", latest("0.3.4", "0.3.3"))
    }

    // A four-part hotfix (0.4.6.1): 0.4.6 is offered it; the 0.4.7-beta1 test build, already past it, is not.
    @Test fun aFourPartHotfixIsOfferedToTheReleaseBeforeItOnly() = runBlocking {
        fun latest(tag: String, current: String) = runBlocking { Store.updateChecker(OneAnswer(200, "{\"tag_name\":\"v$tag\"}")).latestNewerThan(current) }
        assertTrue(Store.offered("0.4.6.1", "0.4.6"))
        assertFalse(Store.offered("0.4.6.1", "0.4.7-beta1"))
        assertFalse(Store.offered("0.4.6.1", "0.4.6.1"))
        assertTrue(Store.offered("0.4.7", "0.4.7-beta1"))
        assertEquals("0.4.6.1", latest("0.4.6.1", "0.4.6"))
        assertEquals("", latest("0.4.6.1", "0.4.7-beta1"))
    }

    @Test fun softwareUpdatesStayTheOwnersChoice() {
        val g = graph()
        val ota = PackageName("com.mitv.ota")
        val on = PackageState(EnabledState.ENABLED, true, 1, null, false, false, null, false)
        val snap = Samples.snapshot(packages = Samples.miTvPackages(emptySet()) + (ota to on))
        val r = g.planner.plan(PlanRequest.SwitchOff(setOf(ota)), snap, g.facts, emptyList())
        assertIs<PlanResult.Ok>(r)
        assertEquals(listOf<Op>(Op.Disable(ota)), r.plan.steps.map { it.op })
    }
}
