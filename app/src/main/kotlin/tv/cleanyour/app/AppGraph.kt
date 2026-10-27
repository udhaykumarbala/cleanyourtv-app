package tv.cleanyour.app

import android.content.Context
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tv.cleanyour.adb.session.AdbSessionManager
import tv.cleanyour.android.platform.AndroidCredentialGate
import tv.cleanyour.android.platform.BundledAssets
import tv.cleanyour.android.platform.FileBlobStore
import tv.cleanyour.android.platform.FileStore
import tv.cleanyour.android.platform.KeystoreAdbSigner
import tv.cleanyour.android.platform.NsdDiscovery
import tv.cleanyour.android.platform.PackageManagerApkInspector
import tv.cleanyour.android.platform.PlainSocketFactory
import tv.cleanyour.core.backup.DefaultTvRestoreFiles
import tv.cleanyour.core.backup.DocumentSnapshotStore
import tv.cleanyour.core.backup.PlainRestoreNote
import tv.cleanyour.core.backup.ZipRestoreBundleWriter
import tv.cleanyour.core.engine.DefaultEngine
import tv.cleanyour.core.engine.DocumentPlanStore
import tv.cleanyour.core.engine.Engine
import tv.cleanyour.core.engine.FileJournal
import tv.cleanyour.core.engine.FileOverlayTransport
import tv.cleanyour.core.flows.AppForeground
import tv.cleanyour.core.flows.AppAbout
import tv.cleanyour.core.flows.AppPrefs
import tv.cleanyour.core.flows.AppSettingsMachine
import tv.cleanyour.core.flows.CardAnswerDetector
import tv.cleanyour.core.flows.ConfigEncoder
import tv.cleanyour.core.flows.DockPicker
import tv.cleanyour.core.flows.DocumentTvRepository
import tv.cleanyour.core.flows.GateKeeper
import tv.cleanyour.core.flows.HomeDecisions
import tv.cleanyour.core.flows.HomePrefs
import tv.cleanyour.core.flows.HomeScreenMachine
import tv.cleanyour.core.flows.LeftoversMachine
import tv.cleanyour.core.flows.ManageDeps
import tv.cleanyour.core.flows.ManageMachine
import tv.cleanyour.core.flows.ModulesMachine
import tv.cleanyour.core.flows.OpenWrites
import tv.cleanyour.core.flows.PlanRunner
import tv.cleanyour.core.flows.ReportKind
import tv.cleanyour.core.flows.ReportMachine
import tv.cleanyour.core.flows.RestorePointMachine
import tv.cleanyour.core.flows.RestorePointWriter
import tv.cleanyour.core.flows.RunLedger
import tv.cleanyour.core.flows.ScreenLock
import tv.cleanyour.core.flows.SecureIdGen
import tv.cleanyour.core.flows.SessionEnd
import tv.cleanyour.core.flows.FlowLog
import tv.cleanyour.core.flows.SetupDeps
import tv.cleanyour.core.flows.SetupMachine
import tv.cleanyour.core.flows.SetupStart
import tv.cleanyour.core.flows.SwitchedOffMachine
import tv.cleanyour.core.flows.TvAccess
import tv.cleanyour.core.flows.TvSettingsMachine
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.ops.ConfigTransport
import tv.cleanyour.core.ops.OpRenderer
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.probe.AdbProber
import tv.cleanyour.core.probe.Prober
import tv.cleanyour.core.registry.IndexBundle
import tv.cleanyour.core.registry.IndexRegistry
import tv.cleanyour.core.registry.ResumableArtifactFetcher
import tv.cleanyour.core.registry.UrlConnectionFetcher
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.BundledThemeSource
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.trust.BundledCurtainPin
import tv.cleanyour.core.trust.BundledLauncherPin
import tv.cleanyour.core.trust.PinnedCurtain
import tv.cleanyour.core.trust.PinnedLauncher
import tv.cleanyour.core.trust.DownloadHosts
import tv.cleanyour.core.trust.TrustRoots

// Manual dependency graph (ARCHITECTURE D8): one instance per process, built in CytApplication.
// Every section 5 seam is the real implementation (T7), built in the integration plan's order with
// `profiles` before the planner and the engine. `seams` keeps the other streams' pieces together
// for the app code that reads them (CytApplication, MainActivity).
//
// `store` is this flavour's ports (STORES-PLAN 1, src/full or src/play Store.kt): the hosts the
// HTTP client may reach, the update check, the add-on fetcher, whether software updates may go
// off, and the rules the flows follow. Everything else is the same in both builds.
class AppGraph(context: Context, val appVersion: String = versionOf(context), val store: StorePorts = Store) {
    private val app = context.applicationContext

    // UI-facing watchers run on the main thread. Sessions, the engine's runs and key creation run
    // on Default: AdbSessionManager runs its handshakes and pumps on its scope's dispatcher.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val clock: Clock = AppClock
    val zone: ZoneId = ZoneId.systemDefault()
    val idGen = SecureIdGen(clock)                       // the planner's only id source (R0: 15)
    val dataDir = File(app.filesDir, "v1")               // one root: tv.json and drafts, journal, plans, snapshots (ARCHITECTURE 5.1)
    val documents = FileStore(dataDir)
    val tvs = DocumentTvRepository(documents)
    val discovery = NsdDiscovery(app)
    val credentialGate = AndroidCredentialGate(app, clock)
    val copy = ResourceFlowCopy(app)
    val foreground = AppForeground(false)

    // W2: the phone's ADB key and the sessions (RFC-W2-3; plain TCP, no TLS in 0.1).
    val signer = DeferredAdbSigner(workScope) { KeystoreAdbSigner.create(app) }
    val keyInfo = SignerKeyInfo(signer)
    val sessions = AdbSessionManager(PlainSocketFactory(), signer, tls = null, clock = clock, scope = workScope, log = { CytLog.i(it) })

    // W4: the probe and the bundled tables.
    // An installed APK that hashes to a pinned one has that pin's signer, so it is not copied to read it
    // (RF-LAUNCHER-240 F11).
    val prober: Prober = AdbProber(clock, known = mapOf(
        tv.cleanyour.core.model.Sha256(PinnedLauncher.SHA256) to tv.cleanyour.core.model.Sha256(PinnedLauncher.SIGNER_SHA256),
        tv.cleanyour.core.model.Sha256(PinnedCurtain.SHA256) to tv.cleanyour.core.model.Sha256(PinnedCurtain.SIGNER_SHA256),
    ))
    // Test build: the person's answers on Setup's candidate list card, which every resolve reads.
    val candidateChoices = tv.cleanyour.core.flows.CandidateChoices(documents)
    val profiles = BundledProfileSource(consent = candidateChoices)
    val facts = BundledFactsTable()

    // W3: renderer variant A (the nohup setsid watchdog) until S12 says otherwise.
    val renderer: OpRenderer = ShellRenderer()
    val configTransport: ConfigTransport = FileOverlayTransport(prober)
    // D23: the engine itself widens the confirm window to 120 s when there is no recent thumbnail,
    // so the planner keeps its 90 s default.
    val planner = DefaultPlanner(clock, idGen, renderer, configTransport, appVersion, profiles = profiles,
        curtainSigner = tv.cleanyour.core.model.Sha256(tv.cleanyour.core.trust.PinnedCurtain.SIGNER_SHA256),   // RFC-CURTAIN-1
        keepUpdatesOn = !store.updatesMayGoOff)                                                               // STORES-PLAN 1
    val snapshots = DocumentSnapshotStore(documents)       // W6
    val journal = FileJournal(dataDir)                     // tvs/<tvId>/journal/<planId>.jsonl
    val plans = DocumentPlanStore(documents)
    // Issue #629: what the home check saw in front when it rolled back, for Setup's stop card (taken once).
    private val wrongFocus = java.util.concurrent.ConcurrentHashMap<tv.cleanyour.core.model.TvId, tv.cleanyour.core.model.Component>()
    val engine: Engine = LiveSessionEngine(DefaultEngine(renderer, prober, journal, snapshots, plans, clock, workScope, profiles, log = { CytLog.i(it) },
        onWrongFocus = { tvId, focus -> wrongFocus[tvId] = focus }), sessions, tvs)

    // W6: the restore files on the TV, the note, the zip.
    val restoreFiles = DefaultTvRestoreFiles(clock, zone)
    val restoreNote = PlainRestoreNote()
    val bundleWriter = ZipRestoreBundleWriter(renderer, clock, appVersion, profiles, zone)

    // W5, the recipe of DECISIONS-W5 "Wiring notes"; sources = null until a published copy exists.
    val blobs = FileBlobStore(app)
    val themes = MaterializedThemeSource(BundledThemeSource(), blobs, onError = { m, t -> CytLog.w(m, t) })
    val inspector = PackageManagerApkInspector(app)
    val launcherPin = BundledLauncherPin(blobs)
    val curtainPin = BundledCurtainPin(blobs)   // RFC-CURTAIN-1
    // D41 with this build's hosts: every URL and every redirect hop must be on store.hosts (none in
    // the Play build, so it never sends a request).
    private val hostAllowed: (String) -> Boolean = { DownloadHosts.isAllowed(it, store.hosts) }
    val http = UrlConnectionFetcher(urlAllowed = hostAllowed)
    val seedIndex: IndexBundle? = runCatching { BundledAssets.seedIndex(app) }.onFailure { CytLog.w("seed index missing", it) }.getOrNull()
    val registry = IndexRegistry(http, documents, clock, seedIndex, sources = null, TrustRoots.indexVerifier())
    // F8: modules download only from installableIndex(), whatever the machine looked them up with.
    // The Play build has no add-ons: its Store hands back a fetcher that never downloads.
    val fetcher = store.addOnFetcher {
        InstallableOnlyFetcher(ResumableArtifactFetcher(http, blobs, blobs, inspector, urlAllowed = hostAllowed)) { registry.installableIndex() }
    }

    val seams = Seams(
        sessions = sessions, keyInfo = keyInfo, prober = prober, profiles = profiles, facts = facts, themes = themes,
        planner = planner, engine = engine, journal = journal, configTransport = configTransport, snapshots = snapshots,
        restoreFiles = restoreFiles, restoreNote = restoreNote, bundleWriter = bundleWriter, blobs = blobs,
        launcherPin = launcherPin, registry = registry, fetcher = fetcher, inspector = inspector, http = http, curtainPin = curtainPin,
    )

    val gate = GateKeeper(credentialGate, clock, copy)
    val ledger = RunLedger(documents, journal)
    val runner = PlanRunner(planner, engine, gate, ledger, facts, snapshots, prober)
    val restorePoint = RestorePointWriter(restoreFiles, blobs, ledger, runner)
    val access = TvAccess(sessions, discovery, tvs, clock)
    val sessionEnd = SessionEnd(sessions, discovery, tvs, runner, foreground)
    val homePrefs = HomePrefs(documents)
    val appPrefs = AppPrefs(documents)
    val openWrites = OpenWrites()                          // one per graph (R5-W8 O2), held by the one ManageDeps
    val diagnostics = LogDiagnostics { "Clean Your TV $appVersion, Android ${android.os.Build.VERSION.SDK_INT}" }
    // 0.3.2 B1, B2: what a report says about the app and the phone (no id of the phone: its maker,
    // model and Android version, as a person would type them into the issue form).
    val about = AppAbout(appVersion, store.flavour, phoneName(), "Android ${android.os.Build.VERSION.RELEASE}")
    val homeDecisions = HomeDecisions(documents)
    val screenLock = ScreenLock { credentialGate.hasScreenLock() }

    // D12: one GET on user action (Settings, "Check for updates"), in the full build only.
    val updates = store.updateChecker(http)

    val setupDeps = SetupDeps(
        discovery = discovery, sessions = sessions, prober = prober, profiles = profiles, facts = facts,
        runner = runner, engine = engine, gate = gate, tvs = tvs, snapshots = snapshots, ledger = ledger,
        launcherPin = launcherPin, themes = themes, restorePoint = restorePoint, restoreNote = restoreNote,
        sessionEnd = sessionEnd, access = access, clock = clock, copy = copy, blobs = blobs, encoder = ConfigEncoder.Codec,
        dock = DockPicker.Ranking, screenLock = screenLock,
        diagnostics = diagnostics, locale = { app.resources.configuration.locales[0].toLanguageTag() },
        log = FlowLog { CytLog.i(it) },   // redacted lines for "Share diagnostics": outcomes and counts only
        homePrefs = homePrefs,
        curtainPin = curtainPin,   // RFC-CURTAIN-1
        store = store.rules,       // STORES-PLAN 1
        homeDecisions = homeDecisions,   // 0.3.2 B1
        candidates = tv.cleanyour.core.flows.BundledCandidateLists(profiles, candidateChoices),   // test build
        about = about, connectDetail = { sessions.lastConnectFailure },   // 0.3.6: "Copy error"
        documents = documents,     // 0.4.0: the person's photo for the look step (OwnPicture)
        wrongFocus = { tvId -> wrongFocus.remove(tvId) },                 // issue #629
    )

    val manageDeps = ManageDeps(
        tvs = tvs, sessions = sessions, discovery = discovery, access = access, gate = gate, runner = runner, engine = engine,
        ledger = ledger, prober = prober, profiles = profiles, facts = facts, snapshots = snapshots,
        documents = documents, blobs = blobs, themes = themes, launcherPin = launcherPin,
        configTransport = configTransport, restorePoint = restorePoint, bundleWriter = bundleWriter,
        registry = registry, fetcher = fetcher, inspector = inspector, cardDetector = CardAnswerDetector.Unknown,
        sessionEnd = sessionEnd, homePrefs = homePrefs, appPrefs = appPrefs, keyInfo = keyInfo, updates = updates,
        diagnostics = diagnostics, clock = clock, copy = copy, encoder = ConfigEncoder.Codec, appVersion = appVersion,
        openWrites = openWrites,
        store = store.rules,       // STORES-PLAN 1
        about = about, homeDecisions = homeDecisions,   // 0.3.2 B1, B2
        redact = Redactor::redact,                      // 0.4.6: what a card puts into a problem report
    )

    init {
        // At start and off the main thread: the key (the first launch makes it), the stored or seed
        // module index (no network, DECISIONS-W5), and the theme wallpapers as plain files.
        signer.warmUp()
        // Without add-ons (the Play build) nothing reads the module list, so it is not loaded.
        if (store.rules.addOns) workScope.launch { runCatching { registry.restore() }.onFailure { CytLog.w("module index restore failed", it) } }
        workScope.launch { runCatching { themes.prepare() }.onFailure { CytLog.w("theme wallpapers not stored", it) } }
    }

    fun setup(scope: CoroutineScope, start: SetupStart) = SetupMachine(setupDeps, scope, start)
    fun manage(scope: CoroutineScope, tvId: TvId?) = ManageMachine(manageDeps, scope, tvId)
    fun switchedOff(scope: CoroutineScope, tvId: TvId) = SwitchedOffMachine(manageDeps, scope, tvId)
    fun leftovers(scope: CoroutineScope, tvId: TvId) = LeftoversMachine(manageDeps, scope, tvId)
    fun homeScreen(scope: CoroutineScope, tvId: TvId) = HomeScreenMachine(manageDeps, scope, tvId)
    fun modules(scope: CoroutineScope, tvId: TvId) = ModulesMachine(manageDeps, scope, tvId)
    fun restorePoint(scope: CoroutineScope, tvId: TvId) = RestorePointMachine(manageDeps, scope, tvId)
    fun tvSettings(scope: CoroutineScope, tvId: TvId) = TvSettingsMachine(manageDeps, scope, tvId)
    fun appSettings(scope: CoroutineScope) = AppSettingsMachine(manageDeps, scope)
    fun report(scope: CoroutineScope, kind: ReportKind, tvId: TvId?) = ReportMachine(manageDeps, scope, kind, tvId)

    // Tests only; the process owns the graph otherwise.
    fun close() {
        scope.cancel()
        workScope.cancel()
    }

    companion object {
        // The app's own versionName (app/build.gradle.kts sets it), so the diagnostics, the plans and
        // the update check never name an older release than the APK that runs.
        // "Samsung SM-S921B": the maker (capitalised) and the model, without the maker twice.
        fun phoneName(manufacturer: String = android.os.Build.MANUFACTURER.orEmpty(), model: String = android.os.Build.MODEL.orEmpty()): String {
            val maker = manufacturer.trim().replaceFirstChar { it.uppercase() }
            val m = model.trim()
            return if (maker.isEmpty() || m.startsWith(maker, ignoreCase = true)) m else "$maker $m"
        }

        fun versionOf(context: Context): String =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
    }
}
