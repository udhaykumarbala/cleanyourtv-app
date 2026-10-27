package tv.cleanyour.core.flows

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.Choices
import tv.cleanyour.core.planner.WizardWifi
import tv.cleanyour.core.planner.HomeSwitchRisk
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.core.planner.disabledPackages
import tv.cleanyour.core.planner.switchOffCount
import tv.cleanyour.core.planner.switchOffPackages
import tv.cleanyour.core.engine.FileOverlayTransport
import tv.cleanyour.core.probe.AdbProber
import tv.cleanyour.core.spec.AppNames
import tv.cleanyour.core.spec.BundledFactsTable
import tv.cleanyour.core.spec.BundledProfileSource
import tv.cleanyour.core.spec.DecodeResult
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.DockRanking
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.trust.PinnedCurtain
import tv.cleanyour.testing.fixtures.FakeClock
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.FakeProber
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.MemoryBlobStore
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.testing.fixtures.sha256Of
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Emulator run, round 3: on the emulator TV (an unknown model, Simple) S3 said nothing was found to
// switch off while the plan log said "9 steps, 1 to switch off": the home screen switch switched the
// TV's own home screen off and no screen named it. Here S3's numbers (the numeral, the group rows,
// "n of m off" in the list), the list's Off rows, "Show commands" and the plan "Make it clean" runs
// must agree exactly, with the bundled profiles and facts the app ships, for the reference Mi TV,
// an unknown model and the emulator capture.
class WhatWeFoundMatchesThePlanTest {
    private val facts = BundledFactsTable()
    private val profiles = BundledProfileSource()
    // The transport the app wires (D15), so the Setup plan's config.json steps are the real ones.
    private val planner = DefaultPlanner(FakeClock(), FakeIdGen(), ShellRenderer(), FileOverlayTransport(FakeProber()), "0.1.0")
    private val launcher = Samples.launcherArtifact()
    private val blobs = MemoryBlobStore()
    // RFC-CURTAIN-1: replan hands in the bundled TV curtain in Simple, so S3's plan carries it.
    private val curtain = tv.cleanyour.core.model.LauncherArtifact(PackageName(PinnedCurtain.PACKAGE), PinnedCurtain.VERSION_CODE, PinnedCurtain.VERSION_NAME,
        tv.cleanyour.core.model.Sha256(PinnedCurtain.SHA256), tv.cleanyour.core.model.Sha256(PinnedCurtain.SIGNER_SHA256),
        BlobRef(tv.cleanyour.core.model.Sha256(PinnedCurtain.SHA256), PinnedCurtain.SIZE, "bundled:${PinnedCurtain.ASSET_NAME}"))
    private fun body(plan: Plan) = plan.steps.filter { it.group != DefaultPlanner.GROUP_CURTAIN }

    // The Setup request SetupMachine.replan sends for a first visit in Simple: nothing kept, nothing
    // extra, the auto-picked dock (or the one given), the launcher and the switch when the TV allows
    // them, and (RFC-DOCK-1) the dock and look encoded by the same helper replan uses.
    // RF-SAFETY-250: like replan, no install, look or switch where HomeSwitchRisk says so (the Wi-Fi
    // answer as a TV that could not tell gives it).
    private fun plan(snapshot: Snapshot, chosenDock: List<PackageName>? = null): Pair<Plan, ProfileResolution> {
        val res = profiles.resolveNow(snapshot.facts)
        val profile = (res as? ProfileResolution.Exact)?.profile
        val possible = HomeSwitchRisk.of(snapshot.facts, profile, snapshot, WizardWifi.UNKNOWN) == null
        val dock = chosenDock ?: DockRanking.pick(profile, snapshot.packages.keys, snapshot.hdmi.isNotEmpty(), profile?.liveTv, "en")
        val choices = Choices(emptySet(), emptySet(), dock, null, installLauncher = possible, takeover = possible, replaceLauncherSigner = false, keepDebuggingOn = false)
        val look = runBlocking { Look.encode(ConfigEncoder.Codec, blobs, null, dock, cards = false) }   // RFC-CARDS-1: a first visit
        val r = planner.plan(PlanRequest.Setup(res, choices, launcher, Level.SIMPLE, look.config.takeIf { possible }, look.wallpaper.takeIf { possible }, curtain), snapshot, facts, emptyList())
        return assertIs<PlanResult.Ok>(r, r.toString()).plan to res
    }

    // RFC-DOCK-1: the Setup plan pushes exactly the config.json DockLayers makes for the dock and its
    // rows (encoded here on its own, not through the helper), in group "theme", after the launcher
    // install and before the guarded home screen switch, and "Show commands" names the push.
    // RFC-CARDS-1: with module_cards false, so the new home screen asks about no card on its first start.
    // 0.4.0: and fill_dock false, so a free dock space stays empty.
    private fun assertTheLookIsPushed(plan: Plan, dock: List<PackageName>, rowsBelowDock: Int) {
        val expected = LauncherConfigCodec.encode(DockLayers.applyTo(LauncherConfigDoc(), dock).copy(moduleCards = false, fillDock = false))
        val pushes = plan.steps.filter { (it.op as? Op.PushAsset)?.dest == FileOverlayTransport.CONFIG }
        val push = pushes.single().op as Op.PushAsset
        assertEquals(sha256Of(expected), push.sha256)
        assertEquals(sha256Of(expected), push.src.sha256)
        assertTrue(expected.contentEquals(blobs.bytes(push.src)), "the blob holds exactly DockLayers' bytes")
        val back = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(expected)).doc
        // An empty dock leaves dock_apps (and the rows) out, so the launcher keeps its own dock.
        val trimmed = DockLayers.trim(dock, DockLayers.DEFAULT_COLUMNS)
        assertEquals(trimmed.ifEmpty { null }, back.dockApps)
        assertEquals(if (trimmed.isEmpty()) null else rowsBelowDock, back.rowsBelowDock)
        assertEquals(false, back.moduleCards, "a first Setup never turns module cards on")
        assertEquals(false, LauncherConfigCodec.effective(back, LauncherConfigCodec.builtInDefaults).moduleCards)
        val theme = plan.steps.filter { it.group == DefaultPlanner.GROUP_THEME }
        assertEquals(listOf("StartActivity", "Mkdir", "PushAsset", "RemoveFile", "RestartHome"), theme.map { it.op::class.simpleName })
        assertEquals((theme.first().index..theme.last().index).toList(), theme.map { it.index }, "one run of steps")
        val install = plan.steps.indexOfFirst { it.op is Op.InstallStream || it.op is Op.Install }
        assertTrue(install in 0 until theme.first().index, "the launcher's first start comes after its install")
        val section = plan.sections.single()
        assertTrue(theme.last().index < section.from, "the look is sent before the watchdog is armed")
        assertTrue(theme.all { it.onFailure == FailurePolicy.CONTINUE }, "a failed push never stops the setup")
        assertEquals(Prior(present = false), pushes.single().prior, "a fresh launcher has no config.json before us")
        val commands = SetupMachine.commandsOf(plan)
        assertTrue("sync:${FileOverlayTransport.CONFIG.absolute} ${push.sha256.hex}" in commands, "Show commands names the push: $commands")
        assertEquals(plan.hash, ShellRenderer().planHash(plan.steps.map { it.rendered }), "the plan hash covers the push")
    }

    // Every rule the screen and the plan share, in one place.
    private fun assertS3AgreesWithThePlan(plan: Plan) {
        val summary = plan.summary
        val disabled = plan.disabledPackages
        // S3's numeral and headline.
        assertEquals(disabled.size, summary.switchOffCount, "S3 counts every switch-off of the plan")
        // The summary card's group rows add up to the same number.
        assertEquals(disabled.size, summary.groups.filter { it.off > 0 }.sumOf { it.off })
        // "See the list": the Off rows are exactly the packages the plan switches off, once each.
        assertEquals(disabled.sortedBy { it.value }, summary.switchOffPackages.sortedBy { it.value })
        assertEquals(disabled.size, disabled.toSet().size)
        // Each group's count is its own Off rows.
        for (g in summary.groups) {
            assertEquals(g.rows.count { it.off }, g.off, g.id)
            assertEquals(g.rows.size, g.total, g.id)
        }
        // A row the list shows as kept is never switched off; nothing protected and kept is either.
        val keptRows = summary.groups.flatMap { it.rows }.filter { !it.off }.map { it.pkg }
        assertTrue(keptRows.none { it in disabled }, "a kept row is switched off: ${keptRows.filter { it in disabled }}")
        // "Show commands": every step of the plan, in order, nothing else, so every write is shown.
        val commands = SetupMachine.commandsOf(plan)
        val expected = plan.steps.flatMap { s -> s.rendered.lines.map { it.text } + listOfNotNull(s.rendered.transfer?.let { "" }) }
        assertEquals(expected.size, commands.size, "one line per rendered line and transfer")
        for (step in plan.steps) for (line in step.rendered.lines) assertTrue(line.text in commands, line.text)
        for (pkg in disabled) assertTrue(commands.any { pkg.value in it && "disable" in it }, "$pkg missing from Show commands")
    }

    @Test fun referenceMiTv() {
        val snapshot = Samples.snapshot(packages = Samples.miTvPackages(emptySet()))
        val (plan, res) = plan(snapshot)
        assertIs<ProfileResolution.Exact>(res)
        assertS3AgreesWithThePlan(plan)
        val dock = DockRanking.pick(res.profile, snapshot.packages.keys, snapshot.hdmi.isNotEmpty(), res.profile.liveTv, "en")
        assertTrue(dock.isNotEmpty())
        assertTheLookIsPushed(plan, dock, DockLayers.rowsBelowDock(dock.size, DockLayers.DEFAULT_COLUMNS))
        assertEquals(plan.summary.dock.size, dock.size, "S3 and the check screen name the dock the plan pushes")
        // The debloat and the TV's own home screen, inside the switch.
        val home = plan.summary.groups.single { it.id == DefaultPlanner.GROUP_HOME }
        assertTrue(home.rows.any { it.pkg == Samples.stockLauncher.pkg && it.off && it.protected })
        assertTrue(plan.summary.switchOffCount > 1)
    }

    // The dock sheet's rows in the wizard (S3b): six apps are the dock and one row under it, and the
    // Setup plan pushes them as one config.json, the step order as on the reference TV.
    @Test fun referenceMiTvWithADockAndARowUnderIt() {
        val apps = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar",
            "com.spotify.tv.android", "com.plexapp.android").map(::PackageName)
        val (plan, _) = plan(Samples.snapshot(packages = Samples.miTvPackages(emptySet())), apps)
        assertS3AgreesWithThePlan(plan)
        assertTheLookIsPushed(plan, apps, rowsBelowDock = 1)
        val steps = body(plan)
        val tail = steps.drop(steps.indexOfFirst { it.op is Op.Settle })
        assertEquals(
            listOf("Settle", "InstallStream", "StartActivity", "Mkdir", "PushAsset", "RemoveFile", "RestartHome",
                "StartActivity", "Mkdir", "ArmHomeWatchdog", "Disable", "SetHome", "VerifyHome", "ConfirmHome", "KillWatchdog"),
            tail.map { it.op::class.simpleName },
        )
        assertTrue(steps.take(steps.size - tail.size).all { it.op is Op.Disable }, "the debloat comes first")
        // RFC-CURTAIN-1: the curtain's start and cues around that body; the final order for this TV.
        val curtainOrder = plan.steps.map { s -> (s.op as? Op.CurtainCue)?.stage?.name ?: s.op::class.simpleName }
        assertEquals(listOf("InstallStream", "StartActivity", "SCAN", "RESTORE_SAVED"), curtainOrder.take(4))
        val cleaning = curtainOrder.subList(4, curtainOrder.indexOf("Settle")).filter { it != "GROUP_DONE" }
        assertEquals(cleaning.indices.map { if (it % 2 == 0) "Disable" else "OFF" }, cleaning, "one OFF cue after each switch-off")
        assertEquals(plan.summary.groups.count { g -> g.id != DefaultPlanner.GROUP_HOME && g.off > 0 }, curtainOrder.count { it == "GROUP_DONE" })
        val end = curtainOrder.drop(curtainOrder.indexOf("Settle"))
        assertEquals(listOf("Settle", "INSTALL", "InstallStream", "INSTALLED", "FINALE", "Settle", "StartActivity", "Mkdir", "PushAsset", "RemoveFile", "RestartHome",
            "StartActivity", "Mkdir", "ArmHomeWatchdog", "Disable", "SetHome", "VerifyHome", "ConfirmHome", "KillWatchdog", "Uninstall"), end)
    }

    @Test fun referenceMiTvAfterItsHandDebloat() {
        // The capture's own state: most of the list already off. Only what is still on is counted.
        val (plan, _) = plan(Samples.snapshot())
        assertS3AgreesWithThePlan(plan)
        val alreadyOff = Fixtures.miTv().disabledAfter.map(::PackageName).toSet()
        assertTrue(plan.summary.switchOffPackages.none { it in alreadyOff })
    }

    // RF-SAFETY-250: a TV no profile knows keeps its switch only without a setup wizard that is on.
    private val wizard = PackageName("com.google.android.tungsten.setupwraith")
    private fun Snapshot.withoutWizard(): Snapshot = copy(packages = packages - wizard).let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
    private fun assertTheCleanUpAlone(snapshot: Snapshot) {
        assertTrue(wizard in snapshot.packages)
        val (plan, _) = plan(snapshot)
        assertS3AgreesWithThePlan(plan)
        assertTrue(plan.sections.isEmpty(), "no home screen switch while the wizard stays on")
        assertTrue(plan.steps.none { it.op is Op.SetHome || it.op == Op.Disable(Samples.stockLauncher.pkg) })
    }

    @Test fun unknownModel() = releaseRule {
        val facts = Samples.miTvFacts().copy(brand = "Acme", manufacturer = "Acme", model = "ACME-55", device = "acme55", fingerprint = "acme/acme55:9/x")
        val withWizard = Samples.snapshot(facts = facts, packages = Samples.miTvPackages(emptySet()))
        assertTheCleanUpAlone(withWizard)
        val snapshot = withWizard.withoutWizard()
        val (plan, res) = plan(snapshot)
        assertIs<ProfileResolution.None>(res)
        assertS3AgreesWithThePlan(plan)
        // Nothing is sure on an unknown model: the one switch-off is the TV's own home screen.
        assertEquals(listOf(Samples.stockLauncher.pkg), plan.disabledPackages)
        assertEquals(1, plan.summary.switchOffCount)
    }

    // 0.4.6 (R0): an unknown Xiaomi whose only claimant is Google's Android TV home gets the switch
    // with its setup wizard on (before, the clean-up alone): the wizard is left as it is.
    @Test fun unknownXiaomiModelWithTheBrandSureSet() {
        val facts = Samples.miTvFacts().copy(model = "MiTV-UNKNOWN", device = "unknown")
        val withWizard = Samples.snapshot(facts = facts, packages = Samples.miTvPackages(emptySet()))
        assertTrue(wizard in withWizard.packages)
        val (switched, sure) = plan(withWizard)
        assertIs<ProfileResolution.BrandSureSet>(sure)
        assertS3AgreesWithThePlan(switched)
        assertEquals(1, switched.sections.size)
        assertTrue(switched.steps.none { (it.op as? Op.Disable)?.pkg == wizard || (it.op as? Op.Enable)?.pkg == wizard })
        val (plan, res) = plan(withWizard.withoutWizard())
        assertIs<ProfileResolution.BrandSureSet>(res)
        assertS3AgreesWithThePlan(plan)
    }

    @Test fun emulatorCapture() = runTest { releaseRule {
        val tv = Fixtures.emulatorTv()
        val snapshot = AdbProber(FakeClock()).snapshot(tv.session(), SnapshotReason.BASELINE) {}
        // What the probe read: Android TV home is HOME, FallbackHome the other claimant, nothing off.
        assertEquals(Component(PackageName("com.google.android.tvlauncher"), ".HomeScreen"), snapshot.home.resolved)
        assertTrue(snapshot.packages.values.none { it.enabled == EnabledState.DISABLED_USER })
        // The image's setup wizard is on: no profile, so the clean-up alone. With it off (as S05 and
        // S09 switch it off on the emulator) the switch as before.
        assertTheCleanUpAlone(snapshot)
        val (plan, res) = plan(snapshot.withoutWizard())
        assertTrue(res !is ProfileResolution.Exact, "the emulator is an unknown model")
        assertS3AgreesWithThePlan(plan)

        // The plan log's "9 steps, 1 to switch off" of round 3, now 14 (RFC-DOCK-1): the streamed
        // launcher install, the five steps that send the dock and look, then the eight steps of the
        // home screen switch, whose one switch-off is the TV's own home screen. RFC-CURTAIN-1 adds
        // nine around them: the curtain's install and start, SCAN and RESTORE_SAVED (nothing to
        // switch off outside the switch), INSTALL and INSTALLED, FINALE and its settle before the
        // launcher's first start, and the curtain's removal last.
        assertEquals(14, body(plan).size)
        assertEquals(
            listOf("InstallStream", "StartActivity", "Mkdir", "PushAsset", "RemoveFile", "RestartHome",
                "StartActivity", "Mkdir", "ArmHomeWatchdog", "Disable", "SetHome", "VerifyHome", "ConfirmHome", "KillWatchdog"),
            body(plan).map { it.op::class.simpleName },
        )
        assertEquals(23, plan.steps.size)
        assertEquals(
            listOf("InstallStream", "StartActivity", "SCAN 0/0", "RESTORE_SAVED 0/0", "INSTALL 0/0", "InstallStream", "INSTALLED 0/0", "FINALE 0/0", "Settle",
                "StartActivity", "Mkdir", "PushAsset", "RemoveFile", "RestartHome",
                "StartActivity", "Mkdir", "ArmHomeWatchdog", "Disable", "SetHome", "VerifyHome", "ConfirmHome", "KillWatchdog", "Uninstall"),
            plan.steps.map { s -> (s.op as? Op.CurtainCue)?.let { "${it.stage} ${it.count}/${it.total}" } ?: s.op::class.simpleName },
        )
        val dock = DockRanking.pick(null, snapshot.packages.keys, snapshot.hdmi.isNotEmpty(), null, "en")
        assertTheLookIsPushed(plan, dock, DockLayers.rowsBelowDock(dock.size, DockLayers.DEFAULT_COLUMNS))
        assertEquals(listOf(PackageName("com.google.android.tvlauncher")), plan.disabledPackages)
        assertTrue(body(plan).none { it.op is Op.Settle }, "no debloat, so no settle")
        // S3 now says one thing to switch off, under "Needed for the new home screen", by its name.
        assertEquals(1, plan.summary.switchOffCount)
        val row = plan.summary.groups.single { it.off > 0 }.let { g ->
            assertEquals(DefaultPlanner.TITLE_HOME, g.title)
            g.rows.single()
        }
        assertEquals("Android TV home", row.friendlyName)
        assertEquals(DefaultPlanner.DESCRIPTION_OLD_HOME, row.description)
        // The rollback puts it back, and the section guards the whole switch.
        assertTrue(Op.Enable(row.pkg) in plan.sections.single().rollback)
    }
    }

    @Test fun emulatorCaptureProbeStaysUnderTenShellCallsAndNamesTheDockApps() = runTest {
        val session = Fixtures.emulatorTv().session()
        val prober = AdbProber(FakeClock())
        val snapshot = prober.snapshot(session, SnapshotReason.BASELINE) {}
        val launchable = prober.launchable(session)
        // The names come without a new shell call: the probe and the launchable line, under ten.
        assertTrue(session.execLines.size < 10, "${session.execLines.size} shell calls: ${session.execLines}")
        // The dock sheet's candidates on the emulator: readable names, no home screen among them.
        val apps = DockCandidates.of(snapshot, launchable, null, facts)
        assertEquals(setOf("com.android.traceur", "com.lego.android.tvleanback"), apps.map { it.value }.toSet())
        assertEquals(setOf("Traceur", "Lego"), apps.map { AppNames.of(it, facts, snapshot.packages[it]?.label) }.toSet())
        assertTrue(snapshot.home.claimants.none { it.component.pkg in apps })
    }

    // The dock sheet's rows (M2): the plan that sends them, and "Show commands", push exactly the
    // config.json the sheet stands for: dock_apps the dock then Row 2 in order, rows_below_dock the
    // one row under the dock, and the launcher reads those bytes back as the same rows.
    @Test fun dockRowsArePushedAsTheSheetShowsThem() {
        val planner = DefaultPlanner(FakeClock(), FakeIdGen(), ShellRenderer(), FileOverlayTransport(FakeProber()), "0.1.0")
        val apps = listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar",
            "com.spotify.tv.android", "com.plexapp.android").map(::PackageName)
        val doc = DockLayers.applyTo(LauncherConfigDoc(), apps)
        val bytes = LauncherConfigCodec.encode(doc)
        val config = BlobRef(sha256Of(bytes), bytes.size.toLong(), "blobs/config")
        val ours = tv.cleanyour.core.model.PackageState(EnabledState.ENABLED, false, 1, null, false, false, null, true)
        val snapshot = Samples.snapshot(packages = Samples.miTvPackages() + (launcher.pkg to ours))
        val r = planner.plan(PlanRequest.ApplyTheme(doc, config, null), snapshot, facts, emptyList())
        val plan = assertIs<PlanResult.Ok>(r, r.toString()).plan
        val push = plan.steps.map { it.op }.filterIsInstance<Op.PushAsset>().single { it.dest.dir == RemoteDir.OVERLAY && it.dest.segments == listOf("config.json") }
        assertEquals(config, push.src)
        assertEquals(sha256Of(bytes), push.sha256)
        val commands = SetupMachine.commandsOf(plan)
        assertTrue(commands.any { "config.json" in it && config.sha256.hex in it }, "Show commands names the pushed config.json: $commands")
        val back = assertIs<DecodeResult.Ok>(LauncherConfigCodec.decode(bytes)).doc
        assertEquals(LauncherConfigDoc(dockApps = apps, rowsBelowDock = 1), back)
        assertEquals(listOf(apps.take(4), apps.drop(4)), DockLayers.rows(back.dockApps.orEmpty(), DockLayers.columnsOf(back)))
    }
}
