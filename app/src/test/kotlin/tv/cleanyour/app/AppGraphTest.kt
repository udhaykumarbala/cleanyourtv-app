package tv.cleanyour.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.cleanyour.adb.session.AdbSessionManager
import tv.cleanyour.core.backup.DocumentSnapshotStore
import tv.cleanyour.core.backup.ZipRestoreBundleWriter
import tv.cleanyour.core.engine.FileJournal
import tv.cleanyour.core.engine.JournalRecord
import tv.cleanyour.core.flows.AppSettingsIntent
import tv.cleanyour.core.flows.TvSettingsIntent
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.probe.AdbProber
import tv.cleanyour.core.registry.IndexRegistry
import tv.cleanyour.core.trust.DownloadHosts
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// T7 definition of done: the real graph starts on the API 28 and 35 Robolectric SDKs, every field
// is set, and item 6 (one data root; "Forget this TV" and "Delete everything" leave nothing under
// tvs/<tvId>) on the graph's own stores.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class AppGraphTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val graphs = mutableListOf<AppGraph>()

    private fun graph(): AppGraph = AppGraph(context).also { graphs += it }

    @After fun closeGraphs() { graphs.forEach { it.close() } }

    // Runs the main looper (Dispatchers.Main posts to it under Robolectric) until `done` holds.
    private fun until(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    private fun nonStaticFields(o: Any) = o.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible = true }

    @Test fun theRealGraphHasEveryFieldSet() {
        val g = graph()
        for (f in nonStaticFields(g)) assertNotNull(f.get(g), "AppGraph.${f.name}")
        for (f in nonStaticFields(g.seams)) assertNotNull(f.get(g.seams), "Seams.${f.name}")
        assertTrue(nonStaticFields(g.seams).size >= 20)

        // The real implementations, not stand-ins.
        assertIs<AdbSessionManager>(g.seams.sessions)
        assertIs<LiveSessionEngine>(g.seams.engine)
        assertIs<DefaultPlanner>(g.seams.planner)
        assertIs<ShellRenderer>(g.renderer)
        assertIs<AdbProber>(g.seams.prober)
        assertIs<FileJournal>(g.seams.journal)
        assertIs<DocumentSnapshotStore>(g.seams.snapshots)
        assertIs<ZipRestoreBundleWriter>(g.seams.bundleWriter)
        assertIs<IndexRegistry>(g.seams.registry)
        // The fetcher is the flavour's (FullStoreTest, PlayStoreTest); the graph uses this build's Store.
        assertSame(Store, g.store)
        assertIs<MaterializedThemeSource>(g.seams.themes)
        // 0.4.6: what a card puts into a problem report goes through the app's redactor.
        assertEquals("at x.x.x.20 code [code]", g.manageDeps.redact("at 192.168.1.20 code 482913"))
        assertSame(AppClock, g.clock)
        // One OpenWrites per graph, in the one ManageDeps.
        assertSame(g.openWrites, g.manageDeps.openWrites)
        assertTrue(g.themes.bundled().isNotEmpty())
        assertTrue(g.profiles.sureSets().isNotEmpty() || g.facts.size > 0)
    }

    @Config(application = CytApplication::class)
    @Test fun theAppStartsWithTheRealGraph() {
        val app = ApplicationProvider.getApplicationContext<CytApplication>()
        val g = app.graph
        graphs += g
        assertIs<LiveSessionEngine>(g.seams.engine)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun populate(g: AppGraph, tvId: TvId) {
        val tv = Samples.tv(id = tvId)
        g.tvs.upsert(tv)
        g.snapshots.save(Samples.snapshot(tvId = tvId))
        val plan = Samples.plan(listOf(Op.Disable(PackageName("com.xiaomi.floatingframe"))), tvId = tvId, renderer = g.renderer)
        g.plans.save(plan)
        g.journal.append(JournalRecord.PlanStart(plan.id, tvId, plan.baseSnapshot, plan.steps.size, plan.hash, plan.appVersion, Samples.NOW_MS))
        g.ledger.record(plan)
        g.documents.write("tvs/${tvId.value}/home.txt", "dock=com.netflix.ninja\n".encodeToByteArray())
    }

    private fun tree(dir: File): List<String> = dir.walkTopDown().filter { it != dir }.map { it.relativeTo(dir).path }.sorted().toList()

    @Test fun forgetThisTvLeavesNothingUnderItsFolder() {
        val g = graph()
        val tvId = Samples.miTvId()
        val other = TvId("0123456789abcdef")
        g.workScope.launch { populate(g, tvId); populate(g, other) }
        val folder = File(g.dataDir, "tvs/${tvId.value}")
        until("the stores to write") { File(folder, "home.txt").isFile && File(g.dataDir, "tvs/${other.value}/home.txt").isFile }
        // Item 6: the record and draft, journal, plans, snapshots and runs share filesDir/v1.
        val written = tree(folder)
        for (p in listOf("tv.json", "runs.txt", "home.txt", "journal", "plans", "snapshots")) assertTrue(written.any { it == p }, "$p in $written")
        assertTrue(written.any { it.startsWith("journal/") && it.endsWith(".jsonl") }, written.toString())

        val m = g.tvSettings(g.scope, tvId)
        until("the TV settings to load") { m.state.value.name.isNotEmpty() }
        m.dispatch(TvSettingsIntent.Forget)
        until("forget to finish") { !folder.exists() && g.tvs.tvs.value.none { it.id == tvId } && !m.state.value.forgetting }
        assertEquals(emptyList(), if (folder.exists()) tree(folder) else emptyList())
        assertTrue(File(g.dataDir, "tvs/${other.value}/tv.json").isFile, "the other TV is untouched")
    }

    @Test fun deleteEverythingLeavesNoTvFolder() {
        val g = graph()
        val tvId = Samples.miTvId()
        val other = TvId("0123456789abcdef")
        g.workScope.launch { populate(g, tvId); populate(g, other); g.documents.write("settings.txt", "haptics=true\n".encodeToByteArray()) }
        until("the stores to write") { File(g.dataDir, "settings.txt").isFile && File(g.dataDir, "tvs/${other.value}/home.txt").isFile }

        val m = g.appSettings(g.scope)
        m.dispatch(AppSettingsIntent.DeleteEverything)
        val tvs = File(g.dataDir, "tvs")
        until("delete everything to finish") { (!tvs.exists() || tvs.list().isNullOrEmpty()) && !File(g.dataDir, "settings.txt").exists() && !m.state.value.working }
        assertEquals(emptyList(), if (tvs.exists()) tree(tvs) else emptyList())
        assertTrue(g.tvs.tvs.value.isEmpty())
    }

    // W5 wiring note, per flavour (STORES-PLAN 1): this build's network security config names exactly
    // its Store.hosts (DownloadHosts.ALL in the full build, none in the Play build), with system
    // anchors only and no cleartext. Read from the merged resources of the variant under test.
    @Test fun theNetworkConfigNamesExactlyTheStoreHosts() {
        val domains = mutableSetOf<String>()
        val attrs = mutableListOf<String>()
        val xml = context.resources.getXml(R.xml.network_security_config)
        var inDomain = false
        while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (xml.eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    inDomain = xml.name == "domain"
                    for (i in 0 until xml.attributeCount) attrs += "${xml.getAttributeName(i)}=${xml.getAttributeValue(i)}"
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> if (inDomain) domains += xml.text.trim()
                org.xmlpull.v1.XmlPullParser.END_TAG -> inDomain = false
            }
        }
        assertEquals(Store.hosts, domains)
        assertTrue(DownloadHosts.ALL.containsAll(Store.hosts))
        assertTrue("cleartextTrafficPermitted=true" !in attrs && "src=user" !in attrs, attrs.toString())
        assertTrue("src=system" in attrs, attrs.toString())
    }
}
