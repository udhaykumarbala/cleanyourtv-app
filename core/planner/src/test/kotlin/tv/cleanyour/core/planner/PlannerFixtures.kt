package tv.cleanyour.core.planner

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.LauncherArtifact
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageEntry
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Platform
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.ProfileMatch
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.ops.ConfigTransport
import tv.cleanyour.core.ops.ConfigTransportKind
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.session.TvSession
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.store.SnapshotCodec
import tv.cleanyour.testing.fixtures.FakeClock
import tv.cleanyour.testing.fixtures.FakeConfigTransport
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.MapFactsTable
import tv.cleanyour.testing.fixtures.Samples

// The reference TV as the planner sees it: the capture's packages, a profile built from the hand
// debloat list (W4's parser is not needed for that), the stock launcher as HOME.
object PlannerFixtures {
    val launcher = Samples.launcherComponent
    val stock = Samples.stockLauncher
    val wizard = PackageName("com.google.android.tungsten.setupwraith")
    val facts = MapFactsTable()
    val clock = FakeClock()

    // Every package of the hand debloat list, in file order.
    val debloatList: List<PackageName> = Fixtures.miTv().debloatTxt.lines()
        .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.map { PackageName(it) }

    fun profile(quirks: Set<Quirk> = emptySet(), setupWraith: PackageName? = null, neverDisable: Set<PackageName> = emptySet(), extraGroups: List<PackageGroup> = emptyList()) = Profile(
        id = "xiaomi-mitv-axso0", match = ProfileMatch(listOf("MiTV-AXSO0"), listOf("dangal"), "Xiaomi", null, null, null),
        platform = Platform.ANDROID_TV, stockLaunchers = listOf(stock), liveTv = PackageName("com.android.tv"), setupWraith = setupWraith,
        quirks = quirks, groups = listOf(PackageGroup("mitv", "Adverts, telemetry and preloads", GroupDefault.DISABLE, debloatList.map { PackageEntry(it, null) })) + extraGroups,
        neverDisable = neverDisable, suggestedDock = emptyList(), tested = null, sourceSha256 = Sha256("1".repeat(64)),
    )

    // Pre-debloat TV: nothing disabled, the stock launcher resolves HOME, cmd advertised.
    fun snapshot(
        disabled: Set<String> = emptySet(),
        home: Component? = stock,
        claimants: List<Component> = listOf(stock),
        extra: Map<PackageName, PackageState> = emptyMap(),
        reason: SnapshotReason = SnapshotReason.BASELINE,
    ): Snapshot = Samples.snapshot(
        packages = Samples.miTvPackages(disabled) + extra,
        home = HomeResolution(home, claimants.mapIndexed { i, c -> HomeClaimant(c, if (c == home) 2 else 1 - i) }),
        reason = reason,
    ).withSettings(mapOf(SettingKey.SCREENSAVER_ENABLED to "1", SettingKey.ADB_ENABLED to "1"))

    fun installedLauncher(signer: Sha256 = artifact().signer, versionCode: Long = 1, enabled: EnabledState = EnabledState.ENABLED) =
        launcher.pkg to PackageState(enabled, false, versionCode, signer, false, false, "Clean Your TV Home", true)

    fun artifact(): LauncherArtifact = Samples.launcherArtifact()

    fun choices(
        kept: Set<PackageName> = emptySet(), extraOff: Set<PackageName> = emptySet(), installLauncher: Boolean = true,
        takeover: Boolean = true, replaceSigner: Boolean = false, dock: List<PackageName>? = null,
        wizardWifi: WizardWifi = WizardWifi.NOT_OWNER,   // D3: the TV's Wi-Fi was not saved by its setup wizard
    ) = Choices(kept, extraOff, dock, null, installLauncher, takeover, replaceSigner, keepDebuggingOn = false, wizardWifi = wizardWifi)

    fun setup(profile: Profile? = profile(), choices: Choices = choices(), artifact: LauncherArtifact = artifact()) =
        PlanRequest.Setup(profile?.let { ProfileResolution.Exact(it) } ?: ProfileResolution.None(listOf(stock)), choices, artifact, tv.cleanyour.core.model.Level.SIMPLE)

    fun planner(transport: ConfigTransport = FakeConfigTransport(), idGen: FakeIdGen = FakeIdGen(), profiles: tv.cleanyour.core.spec.ProfileSource? = null) =
        if (profiles == null) DefaultPlanner(clock, idGen, ShellRenderer(), transport, "0.1.0")   // the R0: 15 constructor
        else DefaultPlanner(clock, idGen, ShellRenderer(), transport, "0.1.0", profiles = profiles)

    fun plan(request: PlanRequest, snapshot: Snapshot = snapshot(), journals: List<ChangeRecord> = emptyList(), planner: DefaultPlanner = planner()): Plan =
        when (val r = planner.plan(request, snapshot, facts, journals)) {
            is PlanResult.Ok -> r.plan
            is PlanResult.Refused -> throw AssertionError("refused: ${r.reasons}")
        }

    fun refused(request: PlanRequest, snapshot: Snapshot = snapshot(), journals: List<ChangeRecord> = emptyList()): List<String> =
        when (val r = planner().plan(request, snapshot, facts, journals)) {
            is PlanResult.Ok -> throw AssertionError("expected a refusal, got ${r.plan.steps.map { it.op }}")
            is PlanResult.Refused -> r.reasons
        }

    // 0.4.6 (R0): the reference TV is a Xiaomi with Google's Android TV home and its wizard no Home
    // claimant, so with no profile its switch goes ahead with the wizard on. The rule for every other
    // TV no profile knows is tested on the same TV under another maker.
    fun otherMaker(s: Snapshot): Snapshot = s.copy(facts = s.facts.copy(brand = "Acme", manufacturer = "Acme")).let { it.copy(contentHash = SnapshotCodec.hash(it)) }

    // 0.4.6: a test written for the release rule (R0 on Xiaomi only) runs with it in a test build
    // too (-Pcyt.wizardStaysAnyBrand=true, which widens R0 to every brand).
    inline fun releaseRule(block: () -> Unit) {
        val before = HomeSwitchRisk.anyBrandForTests
        HomeSwitchRisk.anyBrandForTests = false
        try { block() } finally { HomeSwitchRisk.anyBrandForTests = before }
    }

    fun change(step: Int, op: Op, prior: Prior?, atMs: Long = Samples.NOW_MS + step) = ChangeRecord(tv.cleanyour.core.model.PlanId("01FAKEPLAN0000000000000001"), step, op, prior, atMs)
}

// The steps FileOverlayTransport (in :core:engine, a layer above the planner) returns, so the
// config.json tests see the real shape: the launcher's first start, the folder, config.json,
// wallpaper.jpg pushed or removed, and Home restarted.
class OverlayTransport : ConfigTransport {
    override val kind: ConfigTransportKind = ConfigTransportKind.FILE_OVERLAY
    override suspend fun readDigest(session: TvSession): Sha256? = null
    override fun writePlanSteps(config: BlobRef, wallpaper: BlobRef?): List<Op> {
        require(config.size in 1..64L * 1024) { "config.json must be 1 to 65536 bytes" }
        return listOf(
            Op.StartActivity(PlannerFixtures.launcher, wait = true),
            Op.Mkdir(RemoteDir.OVERLAY),
            Op.PushAsset(config, CONFIG, config.sha256),
            if (wallpaper != null) Op.PushAsset(wallpaper, WALLPAPER, wallpaper.sha256) else Op.RemoveFile(WALLPAPER),
            Op.RestartHome(PlannerFixtures.launcher),
        )
    }
    override fun resetPlanSteps(): List<Op> = listOf(Op.RemoveFile(CONFIG), Op.RemoveFile(WALLPAPER), Op.RestartHome(PlannerFixtures.launcher))

    companion object {
        val CONFIG = RemotePath(RemoteDir.OVERLAY, listOf("config.json"))
        val WALLPAPER = RemotePath(RemoteDir.OVERLAY, listOf("wallpaper.jpg"))
    }
}

// Settings are not part of Samples.snapshot; the hash is recomputed so every store accepts it.
fun Snapshot.withSettings(settings: Map<SettingKey, String?>): Snapshot =
    copy(settings = settings).let { it.copy(contentHash = SnapshotCodec.hash(it)) }

// A TV as plain data that ops change, for the apply-then-inverse and idempotence properties.
class ModelTv(snapshot: Snapshot) {
    var packages: MutableMap<PackageName, PackageState> = snapshot.packages.toMutableMap()
    var home: Component? = snapshot.home.resolved
    var settings: MutableMap<SettingKey, String?> = snapshot.settings.toMutableMap()
    val claimants = snapshot.home.claimants.map { it.component }.toMutableList()
    val facts = snapshot.facts
    val tvId = snapshot.tvId
    private val ime = snapshot.ime
    private val screensaver = snapshot.screensaver
    val changes = mutableListOf<ChangeRecord>()
    private var at = 0L

    fun apply(plan: Plan) {
        for (s in plan.steps) {
            val before = prior(s.op)
            if (apply(s.op)) changes += ChangeRecord(plan.id, s.index, s.op, s.prior ?: before, Samples.NOW_MS + (at++))
        }
    }

    private fun prior(op: Op): Prior = when (op) {
        is Op.Disable -> Prior(packageState = packages[op.pkg]?.enabled)
        is Op.Enable -> Prior(packageState = packages[op.pkg]?.enabled)
        is Op.DefaultState -> Prior(packageState = packages[op.pkg]?.enabled)
        is Op.SetHome -> Prior(home = home)
        is Op.SettingsPut -> Prior(settingValue = settings[op.key])
        is Op.Install -> Prior(present = op.pkg in packages)
        is Op.InstallStream -> Prior(present = op.pkg in packages)
        else -> Prior()
    }

    // True when the op is a change (so a ChangeRecord is kept), as the engine's journal would.
    fun apply(op: Op): Boolean = when (op) {
        is Op.Disable -> { packages[op.pkg]?.let { packages[op.pkg] = it.copy(enabled = EnabledState.DISABLED_USER) }; true }
        is Op.Enable -> { packages[op.pkg]?.let { packages[op.pkg] = it.copy(enabled = EnabledState.ENABLED) }; true }
        is Op.DefaultState -> { packages[op.pkg]?.let { packages[op.pkg] = it.copy(enabled = EnabledState.DEFAULT) }; true }
        is Op.SetHome -> { home = op.component; if (op.component !in claimants) claimants += op.component; true }
        is Op.SettingsPut -> { settings[op.key] = op.value; true }
        is Op.Install -> { packages[op.pkg] = PackageState(EnabledState.ENABLED, false, op.versionCode, op.signer, false, false, null, true); true }
        is Op.InstallStream -> { packages[op.pkg] = PackageState(EnabledState.ENABLED, false, op.versionCode, op.signer, false, false, null, true); true }
        is Op.Uninstall -> { packages.remove(op.pkg); claimants.removeAll { it.pkg == op.pkg }; if (home?.pkg == op.pkg) home = claimants.firstOrNull(); true }
        else -> false
    }

    fun snapshot(reason: SnapshotReason = SnapshotReason.PRE_PLAN): Snapshot = Samples.snapshot(
        tvId = tvId, facts = facts, packages = packages.toMap(),
        home = HomeResolution(home, claimants.map { HomeClaimant(it, if (it == home) 2 else 1) }), reason = reason,
    ).copy(ime = ime, screensaver = screensaver).withSettings(settings.toMap())

    fun state(): Triple<Map<PackageName, EnabledState>, Component?, Map<SettingKey, String?>> =
        Triple(packages.mapValues { it.value.enabled }, home, settings.toMap())
}
