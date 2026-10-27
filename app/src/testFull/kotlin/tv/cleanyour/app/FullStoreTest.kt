package tv.cleanyour.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
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

    // Test build: 0.3.5-test1 is offered 0.3.5 and later, never the 0.3.4 it installs over.
    @Test fun aTestBuildIsOfferedOnlyANewerRelease() = runBlocking {
        fun latest(tag: String, current: String) = runBlocking { Store.updateChecker(OneAnswer(200, "{\"tag_name\":\"v$tag\"}")).latestNewerThan(current) }
        assertEquals("", latest("0.3.4", "0.3.5-test1"))
        assertEquals("0.3.5", latest("0.3.5", "0.3.5-test1"))
        assertEquals("0.4.0", latest("0.4.0", "0.3.5-test1"))
        assertEquals("0.3.4", latest("0.3.4", "0.3.3"))
        // 0.4.5-beta1 installs over 0.4.0: offered 0.4.5 and later, never 0.4.0 itself.
        assertEquals("", latest("0.4.0", "0.4.5-beta1"))
        assertEquals("0.4.5", latest("0.4.5", "0.4.5-beta1"))
        assertEquals("0.4.6", latest("0.4.6", "0.4.5-beta1"))
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
