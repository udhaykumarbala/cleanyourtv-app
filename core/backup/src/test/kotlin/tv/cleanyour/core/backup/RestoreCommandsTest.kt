package tv.cleanyour.core.backup

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PlanId
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.ops.OpRenderer
import tv.cleanyour.core.ops.ShellRenderer
import tv.cleanyour.core.ops.UndoOrder
import tv.cleanyour.core.planner.DefaultPlanner
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.PlanResult
import tv.cleanyour.testing.fixtures.FakeClock
import tv.cleanyour.testing.fixtures.FakeConfigTransport
import tv.cleanyour.testing.fixtures.FakeIdGen
import tv.cleanyour.testing.fixtures.MapFactsTable
import tv.cleanyour.testing.fixtures.Samples
import tv.cleanyour.testing.fixtures.sha256Of
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class RestoreCommandsTest {
    private val renderer = ShellRenderer()
    private val steps = RestoreCommands(renderer).steps(Scenario.changes())

    private fun phase(s: RestoreStep): UndoOrder.Phase = UndoOrder.target(s.change.op)!!.phase

    // The lines of the phone's Undo everything for the same records, planned with the real planner
    // and renderer from the TV the records leave behind, without the ops that leave nothing lasting
    // on the TV (verify, press HOME, restart; the laptop files never carry them).
    private fun phoneUndo(changes: List<ChangeRecord>, removeLauncher: Boolean): List<String> {
        val planner = DefaultPlanner(FakeClock(), FakeIdGen(), renderer, FakeConfigTransport(), "0.1.0")
        val r = planner.plan(PlanRequest.UndoEverything(changes, removeLauncher), Scenario.snapshotAfter(changes), MapFactsTable(), changes)
        val plan = (r as? PlanResult.Ok)?.plan ?: fail("refused: $r")
        // The watchdog's own files in tmp, which the phone tidies when it removes the launcher, are
        // left out like the watchdog itself: a script puts back lasting changes only.
        return plan.steps.filterNot { RestoreCommands.isTransient(it.op) || (it.op as? Op.RemoveFile)?.path?.dir == RemoteDir.TMP }
            .flatMap { s -> s.rendered.lines.map { it.text } }
    }

    private fun lines(s: List<RestoreStep>, vararg kinds: RestoreStepKind) = s.filter { it.kind in kinds }.flatMap { it.lines }

    @Test fun phaseByPhaseFromTheEarliestRecordAndOnlyLastingChanges() {
        val ops = steps.map { it.inverse }
        // package states first (the stock launcher, switched off last, comes first), our files last;
        // reads, the watchdog, restarts, mkdir, settle, debugging off and the restore point files leave no step
        assertEquals(Op.Enable(Scenario.stock.pkg), ops.first())
        assertEquals(UndoOrder.Phase.entries, steps.map(::phase).distinct())
        assertEquals(steps.map(::phase).sorted(), steps.map(::phase))
        assertTrue(steps.none { it.change.op is Op.Read || it.change.op is Op.Mkdir || it.change.op is Op.KillWatchdog || it.change.op is Op.DebugOff })
        assertTrue(steps.none { RestoreCommands.isRestorePointFile(it.change.op) })
        // the later put back is not undone: the app's earliest record (the debloat) says it was on
        assertTrue(Op.Enable(Scenario.putBackPkg) in ops && Op.Disable(Scenario.putBackPkg) !in ops, ops.toString())
        val stockOn = ops.indexOf(Op.Enable(Scenario.stock.pkg))
        val homeBack = ops.indexOf(Op.SetHome(Scenario.stock))
        val removeOurs = ops.indexOf(Op.Uninstall(Scenario.launcher.pkg))
        assertTrue(stockOn in 0 until homeBack && homeBack < removeOurs, "stock on, then home back, then our launcher removed: $ops")
    }

    @Test fun sameMillisecondFallsBackToThePlanStep() {
        // two steps journaled in the same millisecond, listed out of order: the later step is undone first
        val plan = PlanId("p")
        val a = PackageName("com.example.first")
        val b = PackageName("com.example.second")
        val changes = listOf(
            ChangeRecord(plan, 1, Op.Disable(b), Prior(packageState = EnabledState.ENABLED), 5),
            ChangeRecord(plan, 0, Op.Disable(a), Prior(packageState = EnabledState.ENABLED), 5),
        )
        assertEquals(listOf(Op.Enable(b), Op.Enable(a)), RestoreCommands(renderer).steps(changes).map { it.inverse })
    }

    @Test fun kindsAndUndoRules() {
        assertEquals(RestoreStepKind.REMOVE_APP, steps.single { it.inverse is Op.Uninstall }.kind)
        assertEquals(Op.DefaultState(Scenario.defaultStatePkg), steps.single { it.change.op == Op.Disable(Scenario.defaultStatePkg) }.inverse)
        assertEquals(Op.SettingsPut(SettingKey.SCREENSAVER_COMPONENTS, null), steps.single { (it.change.op as? Op.SettingsPut)?.key == SettingKey.SCREENSAVER_COMPONENTS }.inverse)
        assertTrue(steps.none { it.kind == RestoreStepKind.NOT_FROM_LAPTOP }, steps.toString())
        assertTrue(steps.all { it.lines.isNotEmpty() && it.note == null })
        assertEquals(listOf("'pm' 'enable' '--user' '0' 'com.xiaomi.statistic'"), steps.single { it.inverse == Op.Enable(PackageName("com.xiaomi.statistic")) }.lines)
    }

    // R5-W6 M1: setup, then "TV's own home screen", then "remove the new one". The steps come in the
    // planner's phases; the stock launcher is switched on before HOME moves; nothing is aimed at the
    // launcher the user removed (no uninstall, no set-home-activity to it, no file in its folder,
    // and no "cannot be reinstalled" entry), nor at the card app's install file the plan deleted.
    @Test fun setupThenTheTvsOwnHomeScreenThenRemoveTheNewOne() {
        val s = RestoreCommands(renderer).steps(Scenario.m1Changes())
        val ops = s.map { it.inverse }
        val debloat = Scenario.disabledAfter.reversed().map { if (it == Scenario.defaultStatePkg) Op.DefaultState(it) else Op.Enable(it) }
        assertEquals(listOf(Op.Enable(Scenario.stock.pkg)) + debloat +
            listOf(Op.SettingsPut(SettingKey.SCREENSAVER_ENABLED, "1"), Op.SetHome(Scenario.stock), Op.Uninstall(Scenario.module)), ops)
        assertEquals(listOf(UndoOrder.Phase.PACKAGES, UndoOrder.Phase.SETTINGS, UndoOrder.Phase.HOME, UndoOrder.Phase.APPS), s.map(::phase).distinct())
        assertTrue(ops.indexOf(Op.Enable(Scenario.stock.pkg)) < ops.indexOf(Op.SetHome(Scenario.stock)))
        assertTrue(s.none { it.kind == RestoreStepKind.NOT_FROM_LAPTOP }, s.toString())
        assertTrue(s.none { st -> st.lines.any { Scenario.launcher.pkg.value in it } }, s.toString())
        assertEquals(RestoreStepKind.REMOVE_APP, s.last().kind)
    }

    // One ordering for both undos: on a TV that still shows every change, the laptop steps are the
    // phone's Undo everything for the same records, rendered by the same renderer.
    @Test fun theStepsAreThePhonesUndoEverything() {
        val changes = Scenario.changes(putBack = false)
        val s = RestoreCommands(renderer).steps(changes)
        // the default script (no --remove-apps) runs exactly what the phone runs when it keeps the launcher
        assertEquals(phoneUndo(changes, removeLauncher = false), lines(s, RestoreStepKind.PUT_BACK))
        // with --remove-apps the script also runs the phone's uninstall, in the same place; the phone
        // then leaves out the file in the launcher's folder, which goes with the app
        val inLauncherFolder = s.filter { (it.inverse as? Op.RemoveFile)?.path?.dir == RemoteDir.OVERLAY }
        assertEquals(1, inLauncherFolder.size)
        assertEquals(phoneUndo(changes, removeLauncher = true), lines(s - inLauncherFolder.toSet(), RestoreStepKind.PUT_BACK, RestoreStepKind.REMOVE_APP))
    }

    // The phone reads the TV and skips an item already back in its first state; the script cannot
    // read it and keeps that line, which changes nothing. Apart from those lines the two are the
    // same lines in the same order, also after a put back and in the M1 flow.
    @Test fun whereThePhoneSkipsAnItemAlreadyBackTheScriptRepeatsIt() {
        for (changes in listOf(Scenario.changes(), Scenario.m1Changes())) {
            val after = Scenario.snapshotAfter(changes)
            val laptop = RestoreCommands(renderer).steps(changes).filter { it.inverse != Op.Uninstall(Scenario.launcher.pkg) }
            val phone = phoneUndo(changes, removeLauncher = false)
            var j = 0
            for (st in laptop) for (line in st.lines) {
                if (j < phone.size && line == phone[j]) { j++; continue }
                assertTrue(alreadyBack(st.inverse, after), "the script has $line, which the phone does not run, and the TV is not back there")
            }
            assertEquals(phone.size, j, "every phone line is in the script, in order")
        }
    }

    private fun alreadyBack(op: Op?, after: Snapshot): Boolean = when (op) {
        is Op.Enable -> after.packages[op.pkg]?.enabled == EnabledState.ENABLED
        is Op.SetHome -> after.home.resolved == op.component
        else -> false
    }

    @Test fun whatCannotBeDoneFromALaptopSaysWhy() {
        val plan = PlanId("p")
        val changes = listOf(
            ChangeRecord(plan, 0, Op.Disable(PackageName("com.example.unknown")), null, 1),                              // no prior
            ChangeRecord(plan, 1, Op.Uninstall(PackageName("com.example.app")), Prior(present = true), 2),                 // no inverse
            ChangeRecord(plan, 2, Op.SettingsPut(SettingKey.SCREENSAVER_ENABLED, "0"), Prior(settingValue = "yes"), 3),    // refused: not 0 or 1
        )
        val s = RestoreCommands(renderer).steps(changes)
        assertEquals(3, s.size)
        assertTrue(s.all { it.kind == RestoreStepKind.NOT_FROM_LAPTOP && it.lines.isEmpty() && !it.note.isNullOrEmpty() }, s.toString())
        // phases: the package, the setting, the app
        assertTrue(s[0].note!!.contains("no record"), s[0].note)
        assertTrue(s[1].note!!.contains("refused"), s[1].note)
        assertTrue(s[2].note!!.contains("cannot be reinstalled"), s[2].note)
    }

    // W3 has no inverse for a change that left an item as it was (R5-W6 M2): that is no step, not a
    // "cannot be put back" entry. Debugging is never switched back on from a script either.
    @Test fun aChangeThatLeftItAsItWasHasNoStep() {
        val plan = PlanId("p")
        val changes = listOf(
            ChangeRecord(plan, 0, Op.Disable(PackageName("com.example.off")), Prior(packageState = EnabledState.DISABLED_USER), 1),
            ChangeRecord(plan, 1, Op.Enable(PackageName("com.example.on")), Prior(packageState = EnabledState.ENABLED), 2),
            ChangeRecord(plan, 2, Op.DefaultState(PackageName("com.example.default")), Prior(packageState = EnabledState.DEFAULT), 3),
            ChangeRecord(plan, 3, Op.SettingsPut(SettingKey.ADB_WIFI_ENABLED, "0"), Prior(settingValue = "1"), 4),
            ChangeRecord(plan, 4, Op.SettingsPut(SettingKey.ADB_ENABLED, "0"), Prior(settingValue = "1"), 5),
        )
        assertEquals(emptyList(), RestoreCommands(renderer).steps(changes))
    }

    // An app we installed and removed again, and a file we wrote and deleted again, leave nothing to
    // put back; an app that was on the TV before us and is gone now says so.
    @Test fun whatTheRecordsAlreadyTookBackHasNoStep() {
        val plan = PlanId("p")
        val ours = PackageName("com.example.ours")
        val theirs = PackageName("com.example.theirs")
        val apk = tv.cleanyour.core.model.RemotePath(RemoteDir.TMP, listOf("a.apk"))
        val blob = BlobRef(sha256Of("a"), 1, "mem:a")
        val changes = listOf(
            ChangeRecord(plan, 0, Op.PushAsset(blob, apk, blob.sha256), Prior(present = false), 1),
            ChangeRecord(plan, 1, Op.Install(apk, blob.sha256, ours, 1, Samples.zeroHash, emptySet()), Prior(present = false), 2),
            ChangeRecord(plan, 2, Op.RemoveFile(apk), null, 3),
            ChangeRecord(plan, 3, Op.Disable(ours), Prior(packageState = EnabledState.ENABLED), 4),
            ChangeRecord(plan, 4, Op.Uninstall(ours), Prior(present = true), 5),
            ChangeRecord(plan, 5, Op.Install(apk, blob.sha256, theirs, 2, Samples.zeroHash, emptySet()), Prior(present = true), 6),
            ChangeRecord(plan, 6, Op.Uninstall(theirs), Prior(present = true), 7),
        )
        val s = RestoreCommands(renderer).steps(changes)
        assertEquals(Op.Uninstall(theirs), s.single().change.op)
        assertTrue(s.single().kind == RestoreStepKind.NOT_FROM_LAPTOP && s.single().note!!.contains("cannot be reinstalled"), s.toString())
    }

    // RFC-DOCK-1: the Setup plan sends the look to a fresh launcher: config.json pushed and
    // wallpaper.jpg removed, both recorded as absent before us. A wallpaper only ever removed leaves
    // no step; one a later look from Manage pushed (its prior unknown) is deleted again, by the
    // script and by the phone's Undo everything alike, as config.json is.
    @Test fun theSetupLookComesOffAgain() {
        val wallpaper = tv.cleanyour.core.model.RemotePath(RemoteDir.OVERLAY, listOf("wallpaper.jpg"))
        val config = tv.cleanyour.core.model.RemotePath(RemoteDir.OVERLAY, listOf("config.json"))
        val base = Scenario.changes(putBack = false)
        val t = base.maxOf { it.atMs }
        val removed = ChangeRecord(base.first().planId, 99, Op.RemoveFile(wallpaper), Prior(present = false), t + 1)
        fun deletes(s: List<RestoreStep>, path: tv.cleanyour.core.model.RemotePath) = s.filter { (it.inverse as? Op.RemoveFile)?.path == path }

        val onlyRemoved = RestoreCommands(renderer).steps(base + removed)
        assertTrue(onlyRemoved.none { it.change.op == Op.RemoveFile(wallpaper) || (it.inverse as? Op.RemoveFile)?.path == wallpaper }, onlyRemoved.toString())
        assertEquals(RestoreStepKind.PUT_BACK, deletes(onlyRemoved, config).single().kind)
        assertEquals(phoneUndo(base + removed, removeLauncher = false), lines(onlyRemoved, RestoreStepKind.PUT_BACK))

        val jpg = BlobRef(sha256Of("jpg"), 3, "mem:wallpaper")
        val pushed = ChangeRecord(PlanId("01THEMEPLAN000000000000007"), 0, Op.PushAsset(jpg, wallpaper, jpg.sha256), Prior(present = null), t + 2)
        val changes = base + removed + pushed
        val s = RestoreCommands(renderer).steps(changes)
        assertEquals(RestoreStepKind.PUT_BACK, deletes(s, wallpaper).single().kind)
        assertEquals(RestoreStepKind.PUT_BACK, deletes(s, config).single().kind)
        assertTrue(s.none { it.kind == RestoreStepKind.NOT_FROM_LAPTOP }, s.toString())
        assertEquals(phoneUndo(changes, removeLauncher = false), lines(s, RestoreStepKind.PUT_BACK))
        // With the launcher removed, both go with its folder.
        assertTrue(phoneUndo(changes, removeLauncher = true).none { "wallpaper.jpg" in it || "config.json" in it })
    }

    // RFC-CURTAIN-1: the curtain is installed after the restore point and removed as the plan's last
    // step. A finished run leaves no step; an interrupted one (no uninstall record) leaves its removal;
    // the cues themselves never make a step.
    @Test fun theCurtainLeavesNoStepWhenFinishedAndItsRemovalWhenInterrupted() {
        val plan = PlanId("p")
        val curtain = PackageName("tv.cleanyour.curtain")
        val receiver = tv.cleanyour.core.model.Component(curtain, ".CueReceiver")
        val token = tv.cleanyour.core.model.RunToken("0123456789abcdef")
        val blob = BlobRef(sha256Of("curtain"), 1, "bundled:cleanyourtv-curtain.apk")
        val run = listOf(
            ChangeRecord(plan, 0, Op.InstallStream(blob, blob.sha256, curtain, 1, Samples.zeroHash), Prior(present = false), 1),
            ChangeRecord(plan, 1, Op.StartActivity(tv.cleanyour.core.model.Component(curtain, ".CurtainActivity"), false), null, 2),
            ChangeRecord(plan, 2, Op.CurtainCue(receiver, token, tv.cleanyour.core.model.CurtainStage.SCAN, 0, 1), null, 3),
            ChangeRecord(plan, 3, Op.Disable(PackageName("com.miui.tv.analytics")), Prior(packageState = EnabledState.ENABLED), 4),
            ChangeRecord(plan, 4, Op.CurtainCue(receiver, token, tv.cleanyour.core.model.CurtainStage.OFF, 1, 1, tv.cleanyour.core.model.CurtainGroup.TRACKING, 1, 1), null, 5),
        )
        val interrupted = RestoreCommands(renderer).steps(run)
        assertTrue(interrupted.none { it.change.op is Op.CurtainCue || it.change.op is Op.StartActivity }, interrupted.toString())
        val removal = interrupted.single { it.inverse == Op.Uninstall(curtain) }
        assertEquals(RestoreStepKind.REMOVE_APP, removal.kind)
        assertEquals(listOf("'pm' 'uninstall' '--user' '0' 'tv.cleanyour.curtain'"), removal.lines)

        val finished = RestoreCommands(renderer).steps(run + ChangeRecord(plan, 5, Op.Uninstall(curtain), Prior(present = true), 6))
        assertTrue(finished.none { it.change.op.let { op -> op is Op.CurtainCue || DefaultPlanner.packageOf(op) == curtain } }, finished.toString())
        assertEquals(1, finished.size, finished.toString())   // only the switch-off goes back
    }

    @Test fun anUndoThatNeedsAFileFromThePhoneIsNotALaptopLine() {
        // a renderer whose undo of an uninstall is a reinstall from the phone's cache (a transfer)
        val blob = BlobRef(sha256Of("apk"), 3, "mem:apk")
        val pkg = PackageName("com.example.app")
        val reinstalling = object : OpRenderer by ShellRenderer() {
            override fun inverse(op: Op, prior: Prior): Op? =
                if (op is Op.Uninstall) Op.InstallStream(blob, blob.sha256, op.pkg, 1, Samples.zeroHash) else null
        }
        val s = RestoreCommands(reinstalling).steps(listOf(ChangeRecord(PlanId("p"), 0, Op.Uninstall(pkg), Prior(present = true), 1)))
        assertEquals(RestoreStepKind.NOT_FROM_LAPTOP, s.single().kind)
        assertTrue(s.single().note!!.contains("file from the phone"), s.single().note)
    }
}
