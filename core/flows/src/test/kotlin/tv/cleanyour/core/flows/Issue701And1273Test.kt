package tv.cleanyour.core.flows

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.HomeSwitchRisk
import tv.cleanyour.core.probe.SavedWifi
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.testing.fixtures.FakeConfigTransport
import tv.cleanyour.testing.fixtures.FakeIdGen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// Issues #701 (TCL Percee TV, tcl_eu, Android 8, 0.4.6-beta2) and #1273 (Xiaomi MIBOX4, oneday,
// Android 12, 0.4.5): each report said "Profile: exact" and "Home switch: blocked WIZARD_STAYS_ON",
// with only tvlauncher and Settings' FallbackHome claiming Home and no wizard in the list. Setup
// would have planned the switch on either TV: the block was the decision Setup kept from its first
// run, under the brand set (0.3.7 and 0.3.3, when no list knew these TVs), where the setup wizard
// does have to stay on. Manage's report puts that kept decision next to the list the TV resolves to
// now, and Manage's Home screen page said we did not know the TV, with "Help add this TV" and no
// "Run Setup again". Now the decision records the version and the list it was made with; one from
// an earlier Setup says so in the report, and the Home screen page offers Setup again.
@OptIn(ExperimentalCoroutinesApi::class)
class Issue701And1273Test {
    private val reports = mapOf("tv-701.txt" to "exact tcl-50dp660", "tv-1273.txt" to "exact xiaomi-mi-box-s-android12")
    private val source = BundledProfileSource()
    private val about = AppAbout("0.4.6-beta3", "full", "Phone", "Android 15")

    private fun harness(r: ReportText.Parsed): Harness {
        val h = Harness(facts = BundledFactsTable())
        h.sessions.allowPromptFor[HOST] = "AB:CD:7F:2A"
        h.prober.facts = r.facts
        h.prober.snapshotFor = { reason -> r.snapshot.copy(reason = reason) }
        h.profiles.homeResolver = { f, home -> source.resolveNow(f, home = home, packages = r.snapshot.packages) }
        val planner = tv.cleanyour.core.planner.DefaultPlanner(h.clock, FakeIdGen(), tv.cleanyour.core.ops.ShellRenderer(), FakeConfigTransport(), "0.4.6")
        h.planner.respond = { request, s -> planner.plan(request, s, h.facts, emptyList()) }
        return h
    }

    private fun TestScope.toFound(h: Harness, wifi: SavedWifi?): SetupMachine {
        val m = SetupMachine(h.setupDeps(savedWifi = { wifi ?: throw RuntimeException("no answer") }, about = about), backgroundScope, SetupStart.Fresh)
        h.discovery.emit(tv.cleanyour.core.discovery.DiscoveryEvent.Found(miTvFound))
        settle()
        m.dispatch(SetupIntent.Start)
        settle()
        h.discovery.open5555[HOST] = true
        settle(2_100)
        settle(700)
        return m
    }

    private val wifiAnswers = listOf(SavedWifi(true, 1, setOf("android.uid.system:1000")),
        SavedWifi(true, 1, setOf("com.google.android.tungsten.setupwraith:10010")), null)

    @Test fun theReportsAreReadAsTheAppMadeThem() {
        for ((name, profile) in reports) {
            val r = ReportText.parse(ReportText.fixture(name))
            assertEquals(profile, r.profileLine, name)
            assertEquals("blocked WIZARD_STAYS_ON", r.homeLine, name)
            assertEquals(listOf("com.google.android.tvlauncher", "com.android.tv.settings"), r.snapshot.home.claimants.map { it.component.pkg.value }, name)
            assertTrue(HomeSwitchRisk.homeWizards(null, r.snapshot).isEmpty(), name)
            assertEquals(profile, LogCodes.profile(source.resolveNow(r.facts, home = r.snapshot.home, packages = r.snapshot.packages)), name)
        }
    }

    // Setup on either TV today: the exact list, no block, the switch in the plan, whatever the TV
    // says of its Wi-Fi, in a release and in the widened test build. The decision kept says which
    // version and list made it.
    @Test fun setupPlansTheSwitchOnBothTvs() = runTest(timeout = 60.seconds) {
        for (anyBrand in listOf(false, true)) for ((name, profile) in reports) for (wifi in wifiAnswers) {
            val before = HomeSwitchRisk.anyBrandForTests
            HomeSwitchRisk.anyBrandForTests = anyBrand
            try {
                val r = ReportText.parse(ReportText.fixture(name))
                val h = harness(r)
                val m = toFound(h, wifi)
                assertTrue(m.state.value is SetupState.Found, "$name: ${m.state.value}")
                assertEquals(HomeBlock.NONE, m.extras.value.homeBlock, "$name ${wifi?.owners} anyBrand=$anyBrand")
                assertTrue(m.extras.value.homePossible)
                assertTrue("setup: home switch planned" in h.logLines, h.logLines.joinToString("\n"))
                assertEquals(HomeDecision(true, app = about.version, profile = profile), HomeDecisions(h.documents).load(r.snapshot.tvId))
            } finally { HomeSwitchRisk.anyBrandForTests = before }
        }
    }

    // Every safety condition stays: the same TVs with the setup wizard claiming Home are refused.
    @Test fun aWizardThatClaimsHomeStillBlocks() = runTest(timeout = 60.seconds) {
        for ((name, _) in reports) {
            val r = ReportText.parse(ReportText.fixture(name))
            val recovery = HomeClaimant(Component(PackageName("com.google.android.tungsten.setupwraith"), ".RecoveryActivity"), 1)
            val snap = r.snapshot.copy(home = r.snapshot.home.copy(claimants = r.snapshot.home.claimants + recovery))
            val h = harness(ReportText.Parsed(r.facts, snap, r.switchedOff, r.app, r.profileLine, r.homeLine, r.personHomes))
            h.prober.snapshotFor = { reason -> snap.copy(reason = reason) }
            val m = toFound(h, wifiAnswers.first())
            assertEquals(HomeBlock.WIZARD_STAYS_ON, m.extras.value.homeBlock, name)
        }
    }

    // What the person saw: Manage, after the update, with the decision the first Setup kept (no
    // version, no list in the file) and the TV record of that Setup (no exact list).
    @Test fun manageTellsAnEarlierSetupsBlockAndOffersSetupAgain() = runTest(timeout = 60.seconds) {
        for ((name, profile) in reports) {
            val r = ReportText.parse(ReportText.fixture(name))
            val h = harness(r)
            val tv = h.knownTv().copy(profileId = null, facts = r.facts).also { h.tvs.upsert(it) }
            h.snapshots.save(r.snapshot)
            h.documents.write("tvs/${tv.id.value}/decision.txt", "home=blocked:WIZARD_STAYS_ON\n".encodeToByteArray())
            val deps = h.manageDeps(about = about)
            val input = assertNotNull(ProblemReports.input(deps, tv.id))
            assertTrue(input.homeEarlier, name)
            val text = TvReport.compact(input)
            assertTrue("Profile: $profile\nHome switch: blocked WIZARD_STAYS_ON (from an earlier Setup)\n" in text, text)
            val n = assertNotNull(HomeScreenMachine(deps, backgroundScope, tv.id).also { settle() }.state.value.notOnTv, name)
            assertTrue(n.earlier && n.setupAgain, "$n")
            assertFalse(n.helpAddTv || n.unlisted, "$n")
            // A decision this version made with this list is its own word again.
            HomeDecisions(h.documents).save(tv.id, HomeDecision(false, "WIZARD_STAYS_ON", app = about.version, profile = profile))
            assertFalse(assertNotNull(ProblemReports.input(deps, tv.id)).homeEarlier)
            assertFalse(assertNotNull(HomeScreenMachine(deps, backgroundScope, tv.id).also { settle() }.state.value.notOnTv).earlier)
            // Another version's decision names its version and list.
            HomeDecisions(h.documents).save(tv.id, HomeDecision(false, "WIZARD_STAYS_ON", app = "0.4.5", profile = "brand set TCL"))
            val older = TvReport.compact(assertNotNull(ProblemReports.input(deps, tv.id)))
            assertTrue("Home switch: blocked WIZARD_STAYS_ON (from an earlier Setup: 0.4.5, brand set TCL)\n" in older, older)
        }
    }

    // Facts of the TV that no version changes keep their line and no Setup again.
    @Test fun aTvThatRefusesOtherHomeScreensKeepsItsLine() = runTest(timeout = 60.seconds) {
        val r = ReportText.parse(ReportText.fixture("tv-1273.txt"))
        val h = harness(r)
        val tv = h.knownTv().copy(profileId = null, facts = r.facts).also { h.tvs.upsert(it) }
        h.snapshots.save(r.snapshot)
        h.documents.write("tvs/${tv.id.value}/decision.txt", "home=blocked:TV_REFUSES\n".encodeToByteArray())
        val n = assertNotNull(HomeScreenMachine(h.manageDeps(about = about), backgroundScope, tv.id).also { settle() }.state.value.notOnTv)
        assertFalse(n.earlier || n.setupAgain, "$n")
        // Review of beta3: so do the families, the hardware and a home screen the person changed, after
        // a version bump too (the decision names 0.4.5, the app is 0.4.6-beta3).
        for (block in listOf(HomeBlock.TV_REFUSES, HomeBlock.OLD_ANDROID, HomeBlock.BOOT_LOOP, HomeBlock.CASTING, HomeBlock.REMOTE_BUTTONS, HomeBlock.HOME_CHANGED)) {
            HomeDecisions(h.documents).save(tv.id, HomeDecision(false, block.name, app = "0.4.5", profile = "brand set Xiaomi"))
            val kept = assertNotNull(HomeScreenMachine(h.manageDeps(about = about), backgroundScope, tv.id).also { settle() }.state.value.notOnTv, block.name)
            assertEquals(block, kept.reason)
            assertFalse(kept.earlier || kept.setupAgain, "$block: $kept")
        }
        // A rule block from 0.4.5 is an earlier Setup's.
        HomeDecisions(h.documents).save(tv.id, HomeDecision(false, "WIZARD_WIFI", app = "0.4.5", profile = "exact xiaomi-mi-box-s-android12"))
        assertTrue(assertNotNull(HomeScreenMachine(h.manageDeps(about = about), backgroundScope, tv.id).also { settle() }.state.value.notOnTv).earlier)
    }

    @Test fun theDecisionFileKeepsTheVersionAndTheList() = runTest {
        val h = Harness()
        val id = { Samples.miTvId() }
        val store = HomeDecisions(h.documents)
        store.save(id(), HomeDecision(false, "WIZARD_WIFI", app = "0.4.6-beta3", profile = "brand set Xiaomi"))
        assertEquals("home=blocked:WIZARD_WIFI\napp=0.4.6-beta3\nprofile=brand set Xiaomi\n", h.documents.read("tvs/${id().value}/decision.txt") { it.decodeToString() })
        assertEquals(HomeDecision(false, "WIZARD_WIFI", app = "0.4.6-beta3", profile = "brand set Xiaomi"), store.load(id()))
        store.save(id(), HomeDecision(true))
        assertEquals("home=planned\n", h.documents.read("tvs/${id().value}/decision.txt") { it.decodeToString() })
    }
}

private typealias Samples = tv.cleanyour.testing.fixtures.Samples
