package tv.cleanyour.core.planner

import tv.cleanyour.core.model.ArtifactRef
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Module
import tv.cleanyour.core.model.ModuleVersion
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Tier
import tv.cleanyour.core.ops.Denylist
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.core.ops.Privilege
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.planner.PlannerFixtures.artifact
import tv.cleanyour.core.planner.PlannerFixtures.change
import tv.cleanyour.core.planner.PlannerFixtures.choices
import tv.cleanyour.core.planner.PlannerFixtures.debloatList
import tv.cleanyour.core.planner.PlannerFixtures.installedLauncher
import tv.cleanyour.core.planner.PlannerFixtures.launcher
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.profile
import tv.cleanyour.core.planner.PlannerFixtures.refused
import tv.cleanyour.core.planner.PlannerFixtures.setup
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.stock
import tv.cleanyour.core.planner.PlannerFixtures.wizard
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.testing.fixtures.FakeConfigTransport
import tv.cleanyour.testing.fixtures.FakeProfileSource
import tv.cleanyour.testing.fixtures.Fixtures
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerTest {
    private val renderer = ShellRenderer()

    private fun ops(p: Plan) = p.steps.map { it.op }
    private inline fun <reified T : Op> Plan.find(): T = steps.map { it.op }.filterIsInstance<T>().single()

    @Test fun setupOnTheMiTvIsDebloatThenLauncherThenTheGuardedSection() {
        val p = plan(setup())
        val disables = p.steps.filter { it.op is Op.Disable && it.group == "mitv" }
        val installed = debloatList.filter { it in snapshot().packages }
        assertEquals(18, installed.size, "six of the 24 packages of the hand list are not on the capture")
        assertEquals(installed, disables.map { (it.op as Op.Disable).pkg }, "one Disable per installed package of the hand list, in list order")
        assertTrue(disables.all { it.onFailure == FailurePolicy.SKIP_IF_REFUSED && it.prior == Prior(packageState = EnabledState.ENABLED) })
        val tail = ops(p).drop(disables.size)
        assertEquals(
            listOf("Settle", "InstallStream", "StartActivity", "Mkdir", "ArmHomeWatchdog", "Disable", "SetHome", "VerifyHome", "ConfirmHome", "KillWatchdog"),
            tail.map { it::class.simpleName },
        )
        val arm = p.find<Op.ArmHomeWatchdog>()
        assertEquals(stock, arm.stock); assertEquals(150, arm.seconds)   // D-W3-1: the 120 s window plus 30 s
        val section = p.sections.single()
        assertEquals(DefaultPlanner.SECTION_HOME, section.name)
        assertEquals(p.steps.indexOfFirst { it.op is Op.ArmHomeWatchdog }, section.from)
        assertEquals(p.steps.indexOfFirst { it.op is Op.VerifyHome }, section.to)
        assertEquals(arm.token, section.token)
        assertEquals(90_000L, section.confirmWindowMs)
        assertEquals(launcher, section.expectedHome)
        assertEquals(listOf(Op.ConfirmHome(arm.token), Op.KillWatchdog(arm.token), Op.Enable(stock.pkg), Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome), section.rollback)
        assertTrue(stock.pkg in section.rollbackIfFocus && PackageName("com.android.settings") in section.rollbackIfFocus && wizard in section.rollbackIfFocus)
        assertTrue(PackageName("android") in section.rollbackIfFocus, "the resolver's package (R1 R-8)")
        assertTrue(launcher.pkg !in section.rollbackIfFocus)
        assertEquals(Prior(home = stock), p.steps.first { it.op is Op.SetHome }.prior)
        assertEquals(renderer.planHash(p.steps.map { it.rendered }), p.hash)
        assertEquals(snapshot().contentHash, p.baseSnapshot)
        assertEquals(PlanKind.SETUP, p.kind)
        assertTrue(p.summary.homeScreenPossible)
        // The debloat group, then the home screen switch's own switch-off (round 3): S3 counts what
        // the plan does, the TV's home screen included.
        assertEquals(listOf("mitv", DefaultPlanner.GROUP_HOME), p.summary.groups.map { it.id })
        assertEquals(installed.size, p.summary.groups.first().off)
        assertEquals(installed.size, p.summary.groups.first().total)
        val home = p.summary.groups.last()
        assertEquals(DefaultPlanner.TITLE_HOME, home.title)
        assertEquals(listOf(stock.pkg), home.rows.map { it.pkg })
        assertTrue(home.rows.single().let { it.off && it.protected && it.description == DefaultPlanner.DESCRIPTION_OLD_HOME })
        assertEquals(installed.size + 1, p.summary.switchOffCount)
        assertEquals(p.disabledPackages, p.summary.switchOffPackages)
        assertTrue(p.steps.mapIndexed { i, s -> i == s.index }.all { it })
        assertEquals(p.steps.indices.toList(), p.steps.map { it.index })
        assertTrue(p.steps.any { it.rendered.privilege == Privilege.TAKEOVER })
    }

    @Test fun keptAndExtraOffChangeExactlyThoseOps() {
        val videos = PackageName("com.google.android.videos")
        val netflix = PackageName("com.netflix.ninja")
        val p = plan(setup(choices = choices(kept = setOf(videos), extraOff = setOf(netflix))))
        assertTrue(ops(p).none { it == Op.Disable(videos) })
        assertTrue(ops(p).contains(Op.Disable(netflix)))
        assertEquals(17, p.summary.groups.first().off)
        assertEquals(listOf("Netflix").map { "com.netflix.ninja" }, p.summary.groups.single { it.id == DefaultPlanner.GROUP_CUSTOM }.rows.map { it.pkg.value })
        assertEquals(p.disabledPackages.toSet(), p.summary.switchOffPackages.toSet())
        // No facts in the fixture and no label from the probe: the name is read from the package
        // (round 3), never the raw package.
        assertEquals(listOf("Videos"), p.summary.keepNames)
    }

    @Test fun protectedRowsAreShownNotPlanned() {
        val extra = tv.cleanyour.core.model.PackageGroup("sys", "System", tv.cleanyour.core.model.GroupDefault.DISABLE, listOf(
            tv.cleanyour.core.model.PackageEntry(PackageName("com.android.systemui"), null),
            tv.cleanyour.core.model.PackageEntry(PackageName("com.google.android.backdrop"), null),
            tv.cleanyour.core.model.PackageEntry(stock.pkg, null),
            tv.cleanyour.core.model.PackageEntry(wizard, null),
            tv.cleanyour.core.model.PackageEntry(PackageName("com.droidlogic.tvinput"), null),
        ))
        val snap = snapshot().let { s -> s.copy(screensaver = listOf(Component(PackageName("com.google.android.backdrop"), ".Backdrop"))).let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) } }
        val p = plan(setup(profile = profile(extraGroups = listOf(extra))), snapshot = snap)
        val rows = p.summary.groups.first { it.id == "sys" }.rows
        // The stock launcher left the protected rows: the switch turns it off, so it is listed
        // under "Needed for the new home screen" as off (round 3), never under "We keep these".
        assertEquals(4, rows.size)
        assertTrue(rows.none { it.pkg == stock.pkg })
        assertTrue(rows.all { it.protected && !it.off && it.whyProtected != null }, rows.toString())
        assertEquals(0, p.summary.groups.first { it.id == "sys" }.off)
        assertTrue(ops(p).filterIsInstance<Op.Disable>().none { it.pkg.value == "com.android.systemui" || it.pkg == wizard })
        assertEquals(1, ops(p).filterIsInstance<Op.Disable>().count { it.pkg == stock.pkg }, "the stock launcher is switched off once, inside the section")
        assertEquals(listOf(stock.pkg), p.summary.groups.single { it.id == DefaultPlanner.GROUP_HOME }.rows.map { it.pkg })
        assertEquals(p.disabledPackages.toSet(), p.summary.switchOffPackages.toSet())
    }

    @Test fun theWizardIsOnlyInTheSectionAndOnlyWithTheQuirk() {
        val p = plan(setup(profile = profile(quirks = setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), setupWraith = wizard)))
        val s = p.sections.single()
        val sectionOps = p.steps.subList(s.from, s.to + 1).map { it.op }
        assertTrue(Op.Disable(wizard) in sectionOps)
        assertEquals(DefaultPlanner.NARRATION_WIZARD, p.steps.first { it.op == Op.Disable(wizard) }.narration)
        assertTrue(Op.Enable(wizard) in s.rollback)
        // UX 2: the wizard is one line of the preview sheet, with its note, counted like any switch-off.
        val wizardRow = p.summary.groups.single { it.id == DefaultPlanner.GROUP_HOME }.rows.single { it.pkg == wizard }
        assertEquals(DefaultPlanner.NARRATION_WIZARD, wizardRow.friendlyName)
        assertEquals(DefaultPlanner.DESCRIPTION_WIZARD, wizardRow.description)
        assertEquals(p.disabledPackages.toSet(), p.summary.switchOffPackages.toSet())
        val without = plan(setup(profile = profile(quirks = emptySet(), setupWraith = wizard)))
        assertTrue(ops(without).none { it == Op.Disable(wizard) })
        assertTrue(refused(setup(profile = profile(), choices = choices(extraOff = setOf(wizard)))).single().contains("setup wizard"))
    }

    @Test fun idempotentReplan() {
        // Everything already done: nothing to switch off, the launcher installed and in charge.
        val snap = snapshot(disabled = debloatList.map { it.value }.toSet(), home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher()))
        val p = plan(setup(), snapshot = snap)
        assertEquals(emptyList<Step>(), p.steps)
        assertTrue(p.sections.isEmpty())
        assertTrue(p.summary.homeScreenPossible)
        // Nothing is counted that the plan does not do: the already-off packages are not rows.
        assertEquals(0, p.summary.switchOffCount)
        assertTrue(p.summary.groups.flatMap { it.rows }.none { it.pkg in debloatList })
        assertEquals(renderer.planHash(emptyList()), p.hash)
    }

    @Test fun launcherLadderWithoutCmdPushesToTmp() {
        val facts = Samples.miTvFacts().copy(adbFeatures = setOf("shell_v2"))
        val snap = Samples.snapshot(facts = facts, packages = Samples.miTvPackages(emptySet())).withSettings(emptyMap())
        val p = plan(setup(choices = choices(takeover = false)), snapshot = snap)
        val tail = ops(p).dropWhile { it is Op.Disable }
        assertEquals(listOf("Settle", "Mkdir", "PushAsset", "Install", "RemoveFile"), tail.map { it::class.simpleName })
        val push = p.find<Op.PushAsset>(); val install = p.find<Op.Install>()
        assertEquals(RemoteDir.TMP, push.dest.dir); assertEquals(push.dest, install.apk)
        assertEquals(artifact().sha256, install.sha256); assertTrue(install.flags.isEmpty())
        assertEquals(Prior(present = false), p.steps.first { it.op is Op.Install }.prior)
        assertTrue(p.sections.isEmpty())
    }

    @Test fun guardedReinstallNeedsConsentAndTheStockHome() {
        val other = Sha256("f".repeat(64))
        val snap = snapshot(extra = mapOf(installedLauncher(signer = other)))
        assertTrue(refused(setup(), snapshot = snap).single().contains("different key"))
        val p = plan(setup(choices = choices(replaceSigner = true)), snapshot = snap)
        val tail = ops(p).dropWhile { it is Op.Disable }
        assertEquals(listOf("Settle", "Uninstall", "InstallStream"), tail.take(3).map { it::class.simpleName })
        assertTrue(p.summary.replacesLauncher)
        // Launcher v2.4.0 plan, finding 4: the uninstall erases the other launcher's look, order, app list and settings too.
        assertEquals("Replace the home screen app (its look, order, app list, settings and card answers on the TV are reset)", p.steps.first { it.op is Op.Uninstall }.narration)
        // Not while our launcher is HOME.
        val ours = snapshot(home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher(signer = other)))
        assertTrue(refused(setup(choices = choices(replaceSigner = true)), snapshot = ours).any { it.contains("D36") })
    }

    @Test fun stockLaunchersMatchByPackage() {
        // The profile lists the package with a class the TV does not report; the TV's own claimant wins.
        val listed = Component(stock.pkg, ".SomeOtherActivity")
        val prof = profile().copy(stockLaunchers = listOf(listed))
        val p = plan(setup(profile = prof))
        val s = p.sections.single()
        assertEquals(stock, p.find<Op.ArmHomeWatchdog>().stock, "the claimant as the TV reported it")
        assertTrue(Op.SetHome(stock) in s.rollback && Op.Enable(stock.pkg) in s.rollback)
    }

    @Test fun takeoverRefusals() {
        val unknownHome = Component(PackageName("com.projectivy.launcher"), ".Main")
        assertTrue(refused(setup(), snapshot = snapshot(home = unknownHome, claimants = listOf(unknownHome))).any { it.contains("#85") })
        assertTrue(refused(setup(choices = choices(installLauncher = false))).any { it.contains("not installed") })
        val refusing = Samples.snapshot(facts = Samples.miTvFacts().copy(installRefusal = true), packages = Samples.miTvPackages(emptySet())).withSettings(emptyMap())
        assertTrue(refused(setup(), snapshot = refusing).any { it.contains("refuses") })
        // A sure set has no profile but has stock launchers: the section still forms, on a TV without
        // a setup wizard that is on (RF-SAFETY-250: with one, no switch).
        val noWizard = snapshot().let { it.copy(packages = it.packages - PlannerFixtures.wizard) }.let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
        val sure = PlanRequest.Setup(tv.cleanyour.core.spec.ProfileResolution.BrandSureSet("Xiaomi", profile().groups, listOf(stock)), choices(), artifact(), tv.cleanyour.core.model.Level.SIMPLE)
        assertEquals(1, plan(sure, snapshot = noWizard).sections.size)
        assertEquals(listOf(HomeSwitchRisk.Risk.WIZARD_STAYS_ON.reason), refused(sure))
        // An unknown model: launcher path only.
        val none = PlanRequest.Setup(tv.cleanyour.core.spec.ProfileResolution.None(listOf(stock)), choices(), artifact(), tv.cleanyour.core.model.Level.SIMPLE)
        val p = plan(none, snapshot = noWizard)
        assertTrue(ops(p).none { it is Op.Disable && it.pkg != stock.pkg })
        assertEquals(1, p.sections.size)
    }

    @Test fun putBackUsesTheJournalsPrior() {
        val pkg = PackageName("com.google.android.videos")
        val snap = snapshot(disabled = setOf(pkg.value))
        assertEquals(listOf(Op.Enable(pkg)), ops(plan(PlanRequest.PutBack(pkg), snap)))
        val j = listOf(change(3, Op.Disable(pkg), Prior(packageState = EnabledState.DEFAULT)))
        assertEquals(listOf(Op.DefaultState(pkg)), ops(plan(PlanRequest.PutBack(pkg), snap, j)))
        assertEquals(emptyList<Op>(), ops(plan(PlanRequest.PutBack(pkg), snapshot())))
        assertTrue(refused(PlanRequest.PutBack(PackageName("com.not.here"))).single().contains("not on this TV"))
        assertEquals(PlanKind.PUT_BACK, plan(PlanRequest.PutBack(pkg), snap).kind)
    }

    @Test fun switchOffAndLeftovers() {
        val a = PackageName("com.google.android.videos"); val b = PackageName("com.netflix.ninja")
        val p = plan(PlanRequest.SwitchOff(setOf(a, b)))
        assertEquals(listOf(Op.Disable(a), Op.Disable(b)), ops(p))
        assertEquals(PlanKind.SWITCH_OFF, p.kind)
        assertTrue(refused(PlanRequest.SwitchOff(setOf(a, PackageName("com.android.systemui")))).single().contains("system UI"))
        assertTrue(refused(PlanRequest.SwitchOff(setOf(stock.pkg))).single().contains("home screen"))
        val left = plan(PlanRequest.Leftovers(setOf(a, b)), snapshot(disabled = setOf(a.value)))
        assertEquals(listOf(Op.Disable(b)), ops(left))
        assertEquals(PlanKind.LEFTOVERS, left.kind)
        // R1: a leftover that is HOME again after a TV update (the stock launcher re-enabled and
        // resolving HOME) is refused; only the home screen switch may switch a home screen off.
        assertTrue(refused(PlanRequest.Leftovers(setOf(stock.pkg, b))).single().contains("home screen"))
        // The current HOME is protected even when the claimant list does not carry it (R5-W4 O5).
        val patchwall = Component(PackageName("com.mitv.tvhome.atv"), ".MainActivity")
        val pw = snapshot(home = patchwall, claimants = emptyList(), extra = mapOf(patchwall.pkg to PackageState(EnabledState.ENABLED, true, 1, null, false, false, "PatchWall", false)))
        assertTrue(refused(PlanRequest.SwitchOff(setOf(patchwall.pkg)), pw).single().contains("home screen"))
        assertTrue(refused(PlanRequest.Leftovers(setOf(patchwall.pkg)), pw).single().contains("home screen"))
        val extra = tv.cleanyour.core.model.PackageGroup("ads", "Adverts", tv.cleanyour.core.model.GroupDefault.DISABLE, listOf(tv.cleanyour.core.model.PackageEntry(patchwall.pkg, null)))
        val setupPlan = plan(setup(profile = profile(extraGroups = listOf(extra)), choices = choices(takeover = false, installLauncher = false)), pw)
        assertTrue(setupPlan.steps.none { DefaultPlanner.packageOf(it.op) == patchwall.pkg })
        // The Mi TV debloat list names PatchWall itself, so the row lands in whichever group lists it first.
        val row = setupPlan.summary.groups.flatMap { it.rows }.single { it.pkg == patchwall.pkg }
        assertTrue(row.protected && !row.off && row.whyProtected!!.contains("home screen"))
    }

    @Test fun applyThemeUsesTheTransportAndNeedsTheLauncher() {
        val config = BlobRef(Sha256("c".repeat(64)), 100, "cfg")
        val transport = FakeConfigTransport()
        val req = PlanRequest.ApplyTheme(tv.cleanyour.core.spec.LauncherConfigDoc(), config, null)
        assertTrue(refused(req).single().contains("not installed"))
        val snap = snapshot(extra = mapOf(installedLauncher())).let { s -> s.copy(launcherOverlay = byteArrayOf(1)).let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) } }
        val p = plan(req, snap, planner = PlannerFixtures.planner(transport))
        assertEquals(transport.writePlanSteps(config, null), ops(p))
        assertEquals(PlanKind.THEME, p.kind)
        assertEquals(1, transport.writes.size - 1)
        assertTrue(p.steps.all { it.group == DefaultPlanner.GROUP_THEME })
    }

    private fun module(tier: Tier = Tier.COMMUNITY, sha: Sha256 = Sha256("d".repeat(64)), size: Long = 5000, minSdk: Int = 21): Pair<Module, ModuleVersion> {
        val v = ModuleVersion(3, "1.2", "2026-01-01", ArtifactRef("https://example/retro.apk", size, sha, Sha256("e".repeat(64))), emptyList(), "none", minSdk, emptyList(), emptyList())
        val m = Module("retro", "Retro", "one", "desc", PackageName("tv.retro.app"), "card", tier, "pub", "src", "MIT", null, null, listOf(v))
        return m to v
    }

    @Test fun addAndRemoveModule() {
        val (m, v) = module()
        val apk = BlobRef(v.artifact.sha256, v.artifact.size, "cache/retro")
        val p = plan(PlanRequest.AddModule(m, v, apk))
        assertEquals(listOf("InstallStream", "PressHome"), ops(p).map { it::class.simpleName })
        assertEquals(PlanKind.ADD_MODULE, p.kind)
        assertTrue(p.steps.first().rendered.privilege == Privilege.COMMUNITY)
        assertTrue(refused(PlanRequest.AddModule(m, v, apk.copy(size = 1))).single().contains("does not match"))
        val (q, qv) = module(tier = Tier.REVOKED)
        assertTrue(refused(PlanRequest.AddModule(q, qv, apk)).single().contains("revoked"))
        val (n, nv) = module(minSdk = 30)
        assertTrue(refused(PlanRequest.AddModule(n, nv, apk)).single().contains("Android 30"))
        // Remove: system refused, not ours refused, ours removed, the launcher only when not HOME.
        val installed = m.pkg to PackageState(EnabledState.ENABLED, false, 3, v.artifact.signer, false, false, null, false)
        assertTrue(refused(PlanRequest.RemoveModule(m.pkg), snapshot(extra = mapOf(installed))).single().contains("not installed by this app"))
        val j = listOf(change(0, Op.InstallStream(apk, apk.sha256, m.pkg, 3, v.artifact.signer), Prior(present = false)))
        assertEquals(listOf(Op.Uninstall(m.pkg)), ops(plan(PlanRequest.RemoveModule(m.pkg), snapshot(extra = mapOf(installed)), j)))
        assertEquals(emptyList<Op>(), ops(plan(PlanRequest.RemoveModule(m.pkg))))
        assertTrue(refused(PlanRequest.RemoveModule(PackageName("mitv.service"))).single().contains("system"))
        assertTrue(refused(PlanRequest.RemoveModule(launcher.pkg), snapshot(home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher()))).single().contains("own home screen first"))
        assertEquals(listOf(Op.Uninstall(launcher.pkg)), ops(plan(PlanRequest.RemoveModule(launcher.pkg), snapshot(extra = mapOf(installedLauncher())))))
    }

    // RFC-CARDS-1: an add that turns the TV's module cards on carries the look in the same plan:
    // the install, then the transport's steps as ApplyTheme sends them (so Show commands and the
    // hash include the push), then Home. Without the new home screen on the TV the look is left out.
    @Test fun addModuleWithTheLookTurnsCardsOnInTheSamePlan() {
        val (m, v) = module()
        val apk = BlobRef(v.artifact.sha256, v.artifact.size, "cache/retro")
        val config = BlobRef(Sha256("c".repeat(64)), 100, "cfg")
        val transport = OverlayTransport()
        val withLauncher = snapshot(extra = mapOf(installedLauncher()))
        val p = plan(PlanRequest.AddModule(m, v, apk, config, null), withLauncher, planner = PlannerFixtures.planner(transport))
        assertIs<Op.InstallStream>(ops(p).first())
        assertEquals(transport.writePlanSteps(config, null) + Op.PressHome, ops(p).drop(1))
        assertEquals(PlanKind.ADD_MODULE, p.kind)
        val look = p.steps.filter { it.group == DefaultPlanner.GROUP_THEME }
        assertEquals(transport.writePlanSteps(config, null), look.map { it.op })
        val push = look.single { it.op is Op.PushAsset && (it.op as Op.PushAsset).src == config }
        assertEquals(FailurePolicy.STOP_AND_ROLLBACK, push.onFailure, "as ApplyTheme")
        assertEquals(listOf("InstallStream", "PressHome"), ops(plan(PlanRequest.AddModule(m, v, apk, config, null))).map { it::class.simpleName })
    }

    @Test fun useStockHomeAndBeforeFactoryReset() {
        val after = snapshot(disabled = setOf(stock.pkg.value, wizard.value), home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher()))
        val p = plan(PlanRequest.UseStockHome(stock), after)
        assertEquals(listOf(Op.Enable(stock.pkg), Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome), ops(p))
        assertEquals(PlanKind.USE_STOCK_HOME, p.kind)
        assertEquals(Prior(home = launcher), p.steps.first { it.op is Op.SetHome }.prior)
        assertTrue(refused(PlanRequest.UseStockHome(launcher), after).single().contains("not the TV's own"))
        assertTrue(refused(PlanRequest.UseStockHome(Component(PackageName("com.x.y"), ".Z")), after).single().contains("not a home screen"))
        val journals = listOf(change(5, Op.Disable(wizard), Prior(packageState = EnabledState.ENABLED)), change(6, Op.SetHome(launcher), Prior(home = stock)))
        val reset = plan(PlanRequest.BeforeFactoryReset, after, journals)
        assertEquals(listOf(Op.Enable(stock.pkg), Op.Enable(wizard), Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome), ops(reset))
        assertEquals(PlanKind.BEFORE_FACTORY_RESET, reset.kind)
        assertEquals(emptyList<Op>(), ops(plan(PlanRequest.BeforeFactoryReset, snapshot())))
    }

    @Test fun undoEverythingIsComputedFromTheChangesInOrder() {
        val a = PackageName("com.miui.tv.analytics"); val b = PackageName("com.xiaomi.statistic")
        val changes = listOf(
            change(0, Op.Disable(a), Prior(packageState = EnabledState.ENABLED)),
            change(1, Op.Disable(b), Prior(packageState = EnabledState.DEFAULT)),
            change(2, Op.InstallStream(artifact().blob, artifact().sha256, launcher.pkg, 1, artifact().signer), Prior(present = false)),
            change(3, Op.Disable(stock.pkg), Prior(packageState = EnabledState.ENABLED)),
            change(4, Op.SetHome(launcher), Prior(home = stock)),
            change(5, Op.SettingsPut(SettingKey.SCREENSAVER_ENABLED, "0"), Prior(settingValue = "1")),
            change(6, Op.PushAsset(BlobRef(Sha256("c".repeat(64)), 5, "cfg"), RemotePath(RemoteDir.OVERLAY, listOf("config.json")), Sha256("c".repeat(64))), Prior(present = false)),
            change(7, Op.Enable(a), Prior(packageState = EnabledState.DISABLED_USER)),   // put back later; the original state is what counts
            change(8, Op.Disable(a), Prior(packageState = EnabledState.ENABLED)),
        )
        val now = snapshot(disabled = setOf(a.value, b.value, stock.pkg.value), home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher()))
            .withSettings(mapOf(SettingKey.SCREENSAVER_ENABLED to "0"))
        val p = plan(PlanRequest.UndoEverything(changes, removeLauncher = false), now)
        assertEquals(listOf(
            Op.Enable(stock.pkg), Op.DefaultState(b), Op.Enable(a),
            Op.SettingsPut(SettingKey.SCREENSAVER_ENABLED, "1"),
            Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome,
            Op.RemoveFile(RemotePath(RemoteDir.OVERLAY, listOf("config.json"))), Op.RestartHome(launcher),
        ), ops(p))
        assertEquals(PlanKind.UNDO_EVERYTHING, p.kind)
        val gone = plan(PlanRequest.UndoEverything(changes, removeLauncher = true), now)
        assertTrue(Op.Uninstall(launcher.pkg) in ops(gone))
        assertTrue(ops(gone).none { it is Op.RemoveFile || it is Op.RestartHome })
        assertTrue(ops(gone).indexOf(Op.SetHome(stock)) < ops(gone).indexOf(Op.Uninstall(launcher.pkg)))
        // Already undone: nothing to do.
        assertEquals(emptyList<Op>(), ops(plan(PlanRequest.UndoEverything(changes.take(1), false), snapshot())))
    }

    // QA 0.4.5-beta1: the undo's progress said "Removing Gathin Home from the TV". The probe reads
    // no labels, so the launcher's name came from its package; the app calls it the new home screen.
    @Test fun removingOurLauncherSaysTheNewHomeScreen() {
        val unlabelled = installedLauncher().let { (pkg, st) -> pkg to st.copy(label = null) }
        val changes = listOf(
            change(0, Op.InstallStream(artifact().blob, artifact().sha256, launcher.pkg, 1, artifact().signer), Prior(present = false)),
            change(1, Op.Disable(stock.pkg), Prior(packageState = EnabledState.ENABLED)),
            change(2, Op.SetHome(launcher), Prior(home = stock)),
        )
        val now = snapshot(disabled = setOf(stock.pkg.value), home = launcher, claimants = listOf(launcher, stock), extra = mapOf(unlabelled))
        val undo = plan(PlanRequest.UndoEverything(changes, removeLauncher = true), now, changes)
        assertEquals("Removing the new home screen from the TV", undo.steps.single { it.op == Op.Uninstall(launcher.pkg) }.narration)
        assertTrue(undo.steps.none { "Gathin" in it.narration }, undo.steps.map { it.narration }.toString())
        // Removing it from Manage (the TV's own home screen already back) says the same.
        val stockBack = snapshot(extra = mapOf(unlabelled))
        assertEquals("Removing the new home screen from the TV", plan(PlanRequest.RemoveModule(launcher.pkg), stockBack, changes).steps.single().narration)
        // Another app of ours keeps its own name.
        val (m, v) = module()
        val apk = BlobRef(v.artifact.sha256, v.artifact.size, "cache/retro")
        val installed = m.pkg to PackageState(EnabledState.ENABLED, false, 3, v.artifact.signer, false, false, "Retro Games", false)
        val j = listOf(change(0, Op.InstallStream(apk, apk.sha256, m.pkg, 3, v.artifact.signer), Prior(present = false)))
        assertEquals("Removing Retro Games from the TV", plan(PlanRequest.RemoveModule(m.pkg), snapshot(extra = mapOf(installed)), j).steps.single().narration)
    }

    @Test fun debugOffAndRestorePoint() {
        val p = plan(PlanRequest.DebugOff)
        assertEquals(listOf(Op.DebugOff), ops(p))
        assertEquals(PlanKind.DEBUG_OFF, p.kind)
        assertTrue(p.steps.all { it.rendered.privilege == Privilege.SESSION_END && it.onFailure == FailurePolicy.CONTINUE })
        val eleven = Samples.snapshot(facts = Samples.miTvFacts().copy(sdk = 30), packages = Samples.miTvPackages()).withSettings(emptyMap())
        assertEquals(listOf(Op.SettingsPut(SettingKey.ADB_WIFI_ENABLED, "0"), Op.DebugOff), ops(plan(PlanRequest.DebugOff, eleven)))
        assertTrue(plan(PlanRequest.DebugOff, eleven).steps.all { it.rendered.privilege == Privilege.SESSION_END })
        val json = BlobRef(Sha256("1".repeat(64)), 10, "j"); val txt = BlobRef(Sha256("2".repeat(64)), 10, "t")
        val w = plan(PlanRequest.WriteRestorePoint(json, txt))
        assertEquals(listOf(Op.Mkdir(RemoteDir.RESTORE), Op.PushAsset(json, RemotePath(RemoteDir.RESTORE, listOf("restore-point.json")), json.sha256),
            Op.PushAsset(txt, RemotePath(RemoteDir.RESTORE, listOf("restore.txt")), txt.sha256)), ops(w))
        assertEquals(PlanKind.WRITE_RESTORE_POINT, w.kind)
    }

    @Test fun plansHaveIdsTimesAndRenderedLinesFromTheOneRenderer() {
        val p = plan(setup())
        assertEquals("01FAKEPLAN0000000000000001", p.id.value)
        assertEquals(PlannerFixtures.clock.nowMs(), p.createdAtMs)
        assertEquals("0.1.0", p.appVersion)
        for (s in p.steps) assertEquals(renderer.render(s.op).lines.map { it.text }, s.rendered.lines.map { it.text })
        assertTrue(p.steps.none { it.rendered.lines.any { l -> l.text.startsWith("fake") } })
    }

    // RFC-W1-2 ruling: the planner never emits an op on `android`, and refuses a request naming it.
    @Test fun theFrameworkPackageIsRefusedByThePlanner() {
        val android = PackageName("android")
        assertEquals(Denylist.FRAMEWORK, android.value)
        val state = PackageState(EnabledState.ENABLED, true, 28, null, true, false, "Android System", false)
        val snap = snapshot(extra = mapOf(android to state))
        assertTrue(refused(PlanRequest.SwitchOff(setOf(android)), snap).single().contains("framework"))
        assertTrue(refused(PlanRequest.Leftovers(setOf(android)), snap).single().contains("framework"))
        assertTrue(refused(PlanRequest.RemoveModule(android), snap).isNotEmpty())
        val extra = tv.cleanyour.core.model.PackageGroup("bad", "Bad list", tv.cleanyour.core.model.GroupDefault.DISABLE, listOf(tv.cleanyour.core.model.PackageEntry(android, null)))
        val p = plan(setup(profile = profile(extraGroups = listOf(extra)), choices = choices(extraOff = emptySet())), snap)
        assertTrue(p.steps.none { s -> DefaultPlanner.packageOf(s.op) == android })
        val row = p.summary.groups.first { it.id == "bad" }.rows.single()
        assertTrue(row.protected && !row.off && row.whyProtected!!.contains("framework"))
        assertNull(ops(p).filterIsInstance<Op.Disable>().firstOrNull { it.pkg == android })
        // Named by the user as an extra: refused with the reason, never planned.
        assertTrue(refused(setup(choices = choices(extraOff = setOf(android))), snap).any { it.contains("framework") })
        // As a stock launcher in a bad profile: the claimant is never switched off.
        val bad = profile().copy(stockLaunchers = listOf(Component(android, ".ResolverActivity")))
        val r = PlannerFixtures.planner().plan(setup(profile = bad), snapshot(claimants = listOf(Component(android, ".ResolverActivity")), home = null, extra = mapOf(android to state)), PlannerFixtures.facts, emptyList())
        if (r is PlanResult.Ok) assertTrue(r.plan.steps.none { s -> DefaultPlanner.packageOf(s.op) == android })
    }

    // R1 R-3: the Manage requests see this TV's profile, so never_disable and the wizard quirk hold
    // at plan time, not only in the executor's live check.
    @Test fun manageRequestsApplyTheProfile() {
        val a = PackageName("com.google.android.videos")
        val quirky = profile(quirks = setOf(Quirk.HOME_TAKEOVER_SETUPWRAITH), setupWraith = wizard, neverDisable = setOf(a))
        val source = FakeProfileSource(listOf(quirky)).also { it.resolver = { _, all -> ProfileResolution.Exact(all.single()) } }
        val withProfile = PlannerFixtures.planner(profiles = source)
        fun refusedBy(req: PlanRequest, snap: Snapshot = snapshot()): List<String> = (withProfile.plan(req, snap, PlannerFixtures.facts, emptyList()) as PlanResult.Refused).reasons
        assertTrue(refusedBy(PlanRequest.SwitchOff(setOf(a))).single().contains(Denylist.REASON_PROFILE))
        assertTrue(refusedBy(PlanRequest.Leftovers(setOf(a))).single().contains(Denylist.REASON_PROFILE))
        // Without a source the planner cannot know (the executor's live check still refuses it).
        assertEquals(listOf<Op>(Op.Disable(a)), ops(plan(PlanRequest.SwitchOff(setOf(a)))))
        // The wizard with the quirk: only the home screen switch switches it off.
        assertTrue(refusedBy(PlanRequest.SwitchOff(setOf(wizard))).single().contains(DefaultPlanner.REASON_WIZARD_SECTION_ONLY))
        assertTrue(refusedBy(PlanRequest.Leftovers(setOf(wizard))).single().contains(DefaultPlanner.REASON_WIZARD_SECTION_ONLY))
        // And it comes back on only under the TV's own home screen (issue #49).
        val ours = snapshot(disabled = setOf(wizard.value, stock.pkg.value), home = launcher, claimants = listOf(launcher), extra = mapOf(installedLauncher()))
        assertTrue(refusedBy(PlanRequest.PutBack(wizard), ours).single().contains(DefaultPlanner.REASON_WIZARD_WHILE_OURS))
        assertEquals(listOf<Op>(Op.Enable(wizard)), ops(plan(PlanRequest.PutBack(wizard), snapshot(disabled = setOf(wizard.value)), planner = withProfile)))
        // The R0: 15 constructor still compiles and plans without a profile.
        assertEquals(listOf<Op>(Op.Enable(wizard)), ops(plan(PlanRequest.PutBack(wizard), ours)))
    }

    // R1 R-10: a stock launcher whose state before this app was DEFAULT ends DEFAULT, not ENABLED.
    @Test fun undoEverythingLeavesAnOriginallyDefaultStockLauncherDefault() {
        val changes = listOf(
            change(0, Op.Disable(stock.pkg), Prior(packageState = EnabledState.DEFAULT)),
            change(1, Op.SetHome(launcher), Prior(home = stock)),
        )
        val now = snapshot(disabled = setOf(stock.pkg.value), home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher()))
        val p = plan(PlanRequest.UndoEverything(changes, removeLauncher = false), now)
        assertEquals(listOf(Op.DefaultState(stock.pkg), Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome), ops(p))
    }

    // RFC-W8-5 and R1 R-1.
    @Test fun rebootIsItsOwnPlanAndTheWatchdogOutlastsThePhonesWindow() {
        val r = plan(PlanRequest.Reboot)
        assertEquals(listOf<Op>(Op.Reboot), ops(r))
        assertEquals(PlanKind.REBOOT, r.kind)
        assertEquals("Restarting the TV", r.steps.single().narration)
        val arm = plan(setup()).find<Op.ArmHomeWatchdog>()
        assertEquals(DefaultPlanner.WATCHDOG_SECONDS, arm.seconds)
        assertTrue(arm.seconds * 1000L >= 120_000L + 30_000L, "the 120 s window of D23 plus 30 s for the section's steps")
    }
    // Issue #2 (A1): Fire OS's own home screen is never the stock launcher of a section, and nothing
    // of the new home screen is installed for it: an unlisted Fire TV Edition set on the Amazon sure
    // set, with the TV's facts naming nothing (here the reference TV's), is caught by its packages.
    @Test fun aTakeoverRefusesFireOsHomeAsTheStockLauncher() {
        val amazon = Component(PackageName("com.amazon.tv.launcher"), ".ui.HomeActivity_vNext")
        val starter = PackageName("com.amazon.firehomestarter")
        val fire = mapOf(
            amazon.pkg to PackageState(EnabledState.ENABLED, true, 1, null, false, false, "Home", false),
            starter to PackageState(EnabledState.ENABLED, true, 1, null, false, false, null, false),
        )
        val snap = snapshot(home = amazon, claimants = listOf(amazon), extra = fire).let { it.copy(packages = it.packages - PlannerFixtures.wizard) }
            .let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
        val groups = listOf(tv.cleanyour.core.model.PackageGroup("ads", "Adverts", tv.cleanyour.core.model.GroupDefault.DISABLE,
            listOf(debloatList.first(), amazon.pkg, starter).map { tv.cleanyour.core.model.PackageEntry(it, null) }))
        fun sure(c: Choices) = PlanRequest.Setup(tv.cleanyour.core.spec.ProfileResolution.BrandSureSet("Amazon", groups, listOf(amazon)), c, artifact(), tv.cleanyour.core.model.Level.SIMPLE)
        assertEquals(listOf(DefaultPlanner.REASON_TV_REFUSES_HOME), refused(sure(choices()), snapshot = snap), "no install")
        val installed = snap.copy(packages = snap.packages + installedLauncher()).let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
        assertEquals(listOf(DefaultPlanner.REASON_TV_REFUSES_HOME), refused(sure(choices(installLauncher = false)), snapshot = installed), "no switch")
        // The clean-up alone: no section, no install, and Fire OS's home screens are shown as kept.
        val p = plan(sure(choices(installLauncher = false, takeover = false)), snapshot = snap)
        assertTrue(p.sections.isEmpty())
        assertTrue(ops(p).none { it is Op.Install || it is Op.InstallStream || it is Op.SetHome })
        assertEquals(listOf<Op>(Op.Disable(debloatList.first())), ops(p))
        val rows = p.summary.groups.flatMap { it.rows }.filter { FireOs.isHome(it.pkg) }
        assertEquals(setOf(amazon.pkg, starter), rows.map { it.pkg }.toSet())
        assertTrue(rows.all { it.protected && !it.off && it.whyProtected == DefaultPlanner.REASON_FIRE_OS_HOME })
        // Nor can Manage switch one off.
        assertTrue(refused(PlanRequest.SwitchOff(setOf(amazon.pkg)), snapshot = snap).single().contains(DefaultPlanner.REASON_FIRE_OS_HOME))
    }

    // S4: the planner's backstop follows the same rule as SetupMachine.homeBlock. A TCL without Google
    // services that an exact TCL Android TV profile knows keeps its home screen switch; with no exact
    // profile, or a TCL_CN one, nothing of the new home screen is planned.
    @Test fun theChinaTclBackstopKeepsAnExactAndroidTvProfilesSwitch() {
        val snap = snapshot().let { it.copy(facts = it.facts.copy(brand = "TCL", manufacturer = "TCL", hasGms = false)) }
            .let { it.copy(contentHash = tv.cleanyour.core.store.SnapshotCodec.hash(it)) }
        val tcl = PlannerFixtures.profile().copy(id = "tcl-example", platform = tv.cleanyour.core.model.Platform.ANDROID_TV)
        val p = plan(setup(tcl), snapshot = snap)
        assertTrue(p.sections.isNotEmpty(), "the switch is planned")
        assertEquals(listOf(DefaultPlanner.REASON_TV_REFUSES_HOME), refused(setup(null), snapshot = snap))
        assertEquals(listOf(DefaultPlanner.REASON_TV_REFUSES_HOME), refused(setup(tcl.copy(platform = tv.cleanyour.core.model.Platform.TCL_CN)), snapshot = snap))
    }
}
