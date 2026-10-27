package tv.cleanyour.core.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import tv.cleanyour.core.discovery.DiscoverySource
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.ProfileMatch
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.TestedInfo
import tv.cleanyour.core.planner.GuardedSection
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.spec.Theme
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.testing.fixtures.FakeCredentialGate
import tv.cleanyour.testing.fixtures.FakeDiscovery
import tv.cleanyour.testing.fixtures.FakeEngine
import tv.cleanyour.testing.fixtures.FakeLauncherPin
import tv.cleanyour.testing.fixtures.FakePlanner
import tv.cleanyour.testing.fixtures.FakeProber
import tv.cleanyour.testing.fixtures.FakeProfileSource
import tv.cleanyour.testing.fixtures.FakeRestoreNote
import tv.cleanyour.testing.fixtures.FakeSessionManager
import tv.cleanyour.testing.fixtures.FakeThemeSource
import tv.cleanyour.testing.fixtures.FakeTvRestoreFiles
import tv.cleanyour.testing.fixtures.FakeTvSession
import tv.cleanyour.testing.fixtures.FakeClock
import tv.cleanyour.testing.fixtures.MapFactsTable
import tv.cleanyour.testing.fixtures.MemoryBlobStore
import tv.cleanyour.testing.fixtures.MemoryDocumentStore
import tv.cleanyour.testing.fixtures.MemoryJournal
import tv.cleanyour.testing.fixtures.MemorySnapshotStore
import tv.cleanyour.testing.fixtures.MemoryTvRepository
import tv.cleanyour.testing.fixtures.Samples

// Copy that names the key and its arguments, so tests assert on which sentence a machine chose.
object KeyCopy : FlowCopy {
    override fun text(key: CopyKey, args: Map<String, Any>): String =
        if (args.isEmpty()) key.name else key.name + args.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${it.key}=${it.value}" }
    override fun date(epochMs: Long): String = "date:$epochMs"
    override fun dateTime(epochMs: Long): String = "datetime:$epochMs"
}

const val HOST = "192.0.2.10"
const val TYPED = "192.168.1.50"   // a home-network address the person types (192.0.2.x is not one)

val miTvFound = FoundTv(HOST, "Mi TV 4A", "MiTV-AXSO0", setOf(DiscoverySource.GOOGLE_CAST), port5555Open = false,
    pairingService = null, connectService = null, lastSeenMs = Samples.NOW_MS)

val adPkg = PackageName("com.miui.tv.analytics")
val promoPkg = PackageName("com.xiaomi.mitv.promo")

val miProfile = Profile(
    id = "xiaomi-mitv-axso0", match = ProfileMatch(listOf(Samples.miTvFacts().model), emptyList(), "Xiaomi", null, null, null),
    platform = Platform.ANDROID_TV, stockLaunchers = listOf(Samples.stockLauncher), liveTv = null, setupWraith = null,
    quirks = emptySet(), groups = emptyList(), neverDisable = emptySet(), suggestedDock = emptyList(),
    tested = TestedInfo("user", null, 7, rebootTested = false), sourceSha256 = Samples.zeroHash,
)

// Every fake wired the way the app wires the real things.
class Harness(val clock: FakeClock = FakeClock(Samples.NOW_MS), val facts: tv.cleanyour.core.spec.FactsTable = MapFactsTable()) {
    val discovery = FakeDiscovery()
    val session = FakeTvSession()
    val sessions = FakeSessionManager().apply { register(HOST, session) }
    val prober = FakeProber()
    val profiles = FakeProfileSource(listOf(miProfile))
    val planner = FakePlanner()
    val engine = FakeEngine(clock)
    val credential = FakeCredentialGate(clock)
    val gate = GateKeeper(credential, clock, KeyCopy)
    val tvs = MemoryTvRepository()
    val snapshots = MemorySnapshotStore()
    val documents = MemoryDocumentStore()
    val journal = MemoryJournal()
    val ledger = RunLedger(documents, journal)
    val blobs = MemoryBlobStore()
    val runner = PlanRunner(planner, engine, gate, ledger, facts, snapshots, prober)
    val restorePoint = RestorePointWriter(FakeTvRestoreFiles(), blobs, ledger, runner)
    val foreground = AppForeground(true)
    val sessionEnd = SessionEnd(sessions, discovery, tvs, runner, foreground)
    val access = TvAccess(sessions, discovery, tvs, clock)
    val themes = FakeThemeSource(listOf(Theme("aurora", "Aurora", "Navy with a wallpaper", LauncherConfigDoc(), null)))

    var setupPlan: Plan = plan(listOf(Op.Disable(adPkg), Op.Disable(promoPkg)))

    init {
        planner.respond = { request, _ ->
            when (request) {
                is tv.cleanyour.core.planner.PlanRequest.Setup -> PlanResult.Ok(setupPlan)
                else -> kindOf(request).let { k -> PlanResult.Ok(plan(emptyList(), kind = k, id = "01FAKEKIND" + k.ordinal.toString().padStart(16, '0'))) }
            }
        }
    }

    fun plan(ops: List<Op>, kind: PlanKind = PlanKind.SETUP, takeover: Boolean = false, id: String = "01FAKEPLAN0000000000000001"): Plan {
        val token = RunToken("00000000000000aa")
        val all = if (takeover) ops + listOf(Op.ArmHomeWatchdog(Samples.stockLauncher, 120, token), Op.SetHome(Launcher.home), Op.VerifyHome(Launcher.home)) else ops
        val sections = if (takeover) listOf(GuardedSection("home_takeover", ops.size, all.size - 1, token, 90_000, Launcher.home, setOf(Samples.stockLauncher.pkg), emptyList())) else emptyList()
        return Samples.plan(all, kind = kind, id = tv.cleanyour.core.model.PlanId(id), sections = sections)
    }

    // What the machines wrote to the diagnostics log, in order.
    val logLines = java.util.concurrent.CopyOnWriteArrayList<String>()
    // The app's diagnostics log as Manage sees it; Delete everything clears it.
    var diagnosticsClears = 0
    val diagnostics = object : Diagnostics {
        override suspend fun redactedText() = "redacted"
        override suspend fun clear() { diagnosticsClears++ }
    }
    // The launchable apps the TV reports (null: the TV could not say).
    var launchable: Set<PackageName>? = null

    fun setupDeps(dock: DockPicker = DockPicker { _, _, _, _, _ -> listOf(PackageName("com.netflix.ninja")) }, curtainPin: tv.cleanyour.core.trust.CurtainPin? = null,
                  savedWifi: tv.cleanyour.core.probe.SavedWifiOwners? = null, store: StoreRules = StoreRules.FULL,
                  candidates: CandidateLists? = null, diagnostics: Diagnostics = Diagnostics { "diagnostics" },
                  about: AppAbout? = null, connectDetail: () -> String? = { null },
                  themes: tv.cleanyour.core.spec.ThemeSource = this.themes,
                  sessions: tv.cleanyour.core.session.SessionManager = this.sessions,
                  wrongFocus: (tv.cleanyour.core.model.TvId) -> tv.cleanyour.core.model.Component? = { null }) = SetupDeps(
        discovery, sessions, prober, profiles, facts, runner, engine, gate, tvs, snapshots, ledger,
        FakeLauncherPin(Samples.launcherArtifact()), themes, restorePoint, FakeRestoreNote(), sessionEnd, access, clock, KeyCopy,
        blobs = blobs, dock = dock, screenLock = { true }, diagnostics = diagnostics,
        launchable = tv.cleanyour.core.probe.LaunchableApps { launchable ?: throw tv.cleanyour.core.probe.ProbeException(tv.cleanyour.core.probe.ProbeStage.LAUNCHER, "no answer") },
        log = FlowLog { logLines += it },
        homePrefs = HomePrefs(documents),
        curtainPin = curtainPin,
        savedWifi = savedWifi,
        store = store,
        homeDecisions = HomeDecisions(documents),
        candidates = candidates,
        about = about,
        connectDetail = connectDetail,
        documents = documents,
        wrongFocus = wrongFocus,
    )

    fun setup(scope: CoroutineScope, start: SetupStart = SetupStart.Fresh, store: StoreRules = StoreRules.FULL) = SetupMachine(setupDeps(store = store), scope, start)

    val registry = tv.cleanyour.testing.fixtures.FakeRegistry()
    val fetcher = tv.cleanyour.testing.fixtures.FakeArtifactFetcher(blobs)
    var inspector: tv.cleanyour.core.trust.ApkInspector = tv.cleanyour.testing.fixtures.FakeApkInspector()
    val bundleWriter = tv.cleanyour.testing.fixtures.FakeRestoreBundleWriter()
    val configTransport = tv.cleanyour.testing.fixtures.FakeConfigTransport()
    var cardAnswer: CardAnswer? = null
    val keyInfo = FakeKeyInfo()
    class FakeKeyInfo : KeyInfo {
        var resets = 0
        override val fingerprint: String get() = "AB:CD:7F:2A:$resets"
        override val backing: String = "TEE"
        override suspend fun reset() { resets++ }
    }

    fun manageDeps(fetcherSources: tv.cleanyour.core.registry.ArtifactFetcher = fetcher, encoder: ConfigEncoder = ConfigEncoder { "{}".encodeToByteArray() },
                   launcherPin: tv.cleanyour.core.trust.LauncherPin = FakeLauncherPin(Samples.launcherArtifact()),
                   themes: tv.cleanyour.core.spec.ThemeSource = this.themes, store: StoreRules = StoreRules.FULL,
                   updates: UpdateChecker = UpdateChecker { "0.2.0" }, profiles: tv.cleanyour.core.spec.ProfileSource = this.profiles,
                   about: AppAbout = AppAbout("0.2.0", "full", "", "")) = ManageDeps(
        tvs = tvs, sessions = sessions, discovery = discovery, access = access, gate = gate, runner = runner, engine = engine,
        ledger = ledger, prober = prober, profiles = profiles, facts = facts, snapshots = snapshots, documents = documents,
        blobs = blobs, themes = themes, launcherPin = launcherPin, configTransport = configTransport,
        restorePoint = restorePoint, bundleWriter = bundleWriter, registry = registry, fetcher = fetcherSources, inspector = inspector,
        cardDetector = CardAnswerDetector { _, _ -> cardAnswer }, sessionEnd = sessionEnd, homePrefs = HomePrefs(documents),
        appPrefs = AppPrefs(documents), keyInfo = keyInfo, updates = updates, diagnostics = diagnostics,
        clock = clock, copy = KeyCopy, encoder = encoder, store = store, appVersion = about.version, about = about,
    )

    // A TV this phone already set up, connected, with a stored snapshot.
    suspend fun knownTv(): tv.cleanyour.core.model.Tv {
        val t = Samples.tv()
        tvs.upsert(t)
        snapshots.save(Samples.miTvSnapshot())
        sessions.open(tv.cleanyour.core.model.Endpoint.PlainTcp(HOST), t.id) {}
        return t
    }

    private fun kindOf(r: tv.cleanyour.core.planner.PlanRequest): PlanKind = when (r) {
        is tv.cleanyour.core.planner.PlanRequest.Setup -> PlanKind.SETUP
        is tv.cleanyour.core.planner.PlanRequest.PutBack -> PlanKind.PUT_BACK
        is tv.cleanyour.core.planner.PlanRequest.SwitchOff -> PlanKind.SWITCH_OFF
        is tv.cleanyour.core.planner.PlanRequest.Leftovers -> PlanKind.LEFTOVERS
        is tv.cleanyour.core.planner.PlanRequest.ApplyTheme -> PlanKind.THEME
        is tv.cleanyour.core.planner.PlanRequest.AddModule -> PlanKind.ADD_MODULE
        is tv.cleanyour.core.planner.PlanRequest.RemoveModule -> PlanKind.REMOVE_MODULE
        is tv.cleanyour.core.planner.PlanRequest.UseStockHome -> PlanKind.USE_STOCK_HOME
        is tv.cleanyour.core.planner.PlanRequest.UndoEverything -> PlanKind.UNDO_EVERYTHING
        tv.cleanyour.core.planner.PlanRequest.BeforeFactoryReset -> PlanKind.BEFORE_FACTORY_RESET
        tv.cleanyour.core.planner.PlanRequest.DebugOff -> PlanKind.DEBUG_OFF
        is tv.cleanyour.core.planner.PlanRequest.WriteRestorePoint -> PlanKind.WRITE_RESTORE_POINT
        tv.cleanyour.core.planner.PlanRequest.Reboot -> PlanKind.REBOOT
        is tv.cleanyour.core.planner.PlanRequest.UpdateLauncher -> PlanKind.UPDATE_LAUNCHER
    }
}

// Lets launched work and short delays run without draining the endless poll and tick loops.
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.settle(ms: Long = 50) { runCurrent(); advanceTimeBy(ms); runCurrent() }

val stockComponent: Component get() = Samples.stockLauncher

// Everything a Turbine collected so far, without waiting: the machines run endless poll and tick
// loops in the background scope, so a waiting awaitItem would spin the test scheduler.
suspend fun <T> app.cash.turbine.ReceiveTurbine<T>.drain(): List<T> =
    cancelAndConsumeRemainingEvents().filterIsInstance<app.cash.turbine.Event.Item<T>>().map { it.value }

// 0.4.6: a test written for the release rule (R0 on Xiaomi only) runs with it in a test build too
// (-Pcyt.wizardStaysAnyBrand=true, which widens R0 to every brand); PlannerFixtures.releaseRule.
inline fun releaseRule(block: () -> Unit) {
    val before = tv.cleanyour.core.planner.HomeSwitchRisk.anyBrandForTests
    tv.cleanyour.core.planner.HomeSwitchRisk.anyBrandForTests = false
    try { block() } finally { tv.cleanyour.core.planner.HomeSwitchRisk.anyBrandForTests = before }
}
