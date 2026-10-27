package tv.cleanyour.core.planner

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.RunToken
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.planner.PlannerFixtures.artifact
import tv.cleanyour.core.planner.PlannerFixtures.change
import tv.cleanyour.core.planner.PlannerFixtures.installedLauncher
import tv.cleanyour.core.planner.PlannerFixtures.launcher
import tv.cleanyour.core.planner.PlannerFixtures.plan
import tv.cleanyour.core.planner.PlannerFixtures.snapshot
import tv.cleanyour.core.planner.PlannerFixtures.stock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// QA 0.4.5-beta1: after Undo everything with "Remove it from the TV", /data/local/tmp/cleanyourtv
// kept the two empty files the home switch made (home-ok-<token>, watchdog-<token>.log). The undo
// that removes the new home screen now removes them too, one `rm -f` of one file each, through the
// renderer's RemoveFile and nothing broader. Pinned as a "Show commands" golden.
class UndoTidyTest {
    private val token = RunToken("0e2ffd0489942e55")
    private val config = BlobRef(Sha256("c".repeat(64)), 5, "cfg")
    private fun tmp(name: String) = RemotePath(RemoteDir.TMP, listOf(name))
    private fun ops(p: Plan) = p.steps.map { it.op }

    // A Setup's records in the planner's order: the launcher in, the guarded switch with its
    // watchdog, the look pushed.
    private fun setup(t: RunToken = token, from: Int = 0) = listOf(
        change(from + 0, Op.InstallStream(artifact().blob, artifact().sha256, launcher.pkg, 1, artifact().signer), Prior(present = false)),
        change(from + 1, Op.Mkdir(RemoteDir.TMP), null),
        change(from + 2, Op.ArmHomeWatchdog(stock, 150, t), null),
        change(from + 3, Op.Disable(stock.pkg), Prior(packageState = EnabledState.ENABLED)),
        change(from + 4, Op.SetHome(launcher), Prior(home = stock)),
        change(from + 5, Op.ConfirmHome(t), null),
        change(from + 6, Op.KillWatchdog(t), null),
        change(from + 7, Op.PushAsset(config, RemotePath(RemoteDir.OVERLAY, listOf("config.json")), config.sha256), Prior(present = false)),
    )
    private val ours = snapshot(disabled = setOf(stock.pkg.value), home = launcher, claimants = listOf(launcher, stock), extra = mapOf(installedLauncher()))

    @Test fun removingTheLauncherTidiesTheWatchdogFiles() {
        val changes = setup()
        val p = plan(PlanRequest.UndoEverything(changes, removeLauncher = true), ours, changes)
        val tidy = listOf(Op.RemoveFile(tmp("home-ok-0e2ffd0489942e55")), Op.RemoveFile(tmp("watchdog-0e2ffd0489942e55.log")))
        assertEquals(listOf(Op.Uninstall(launcher.pkg)) + tidy, ops(p).takeLast(3))
        assertEquals(listOf("'rm' '-f' '/data/local/tmp/cleanyourtv/home-ok-0e2ffd0489942e55'", "'rm' '-f' '/data/local/tmp/cleanyourtv/watchdog-0e2ffd0489942e55.log'"),
            p.steps.takeLast(2).flatMap { s -> s.rendered.lines.map { it.text } })
        // Our launcher's folder goes with the uninstall, so no file there is a step.
        assertTrue(ops(p).none { it is Op.RemoveFile && it.path.dir != RemoteDir.TMP }, ops(p).toString())
        GoldenPlans.check("undo-everything-remove-launcher", p)
    }

    @Test fun keepingTheLauncherKeepsThemAndAnEarlierTidyIsNotRepeated() {
        val changes = setup()
        val kept = plan(PlanRequest.UndoEverything(changes, removeLauncher = false), ours, changes)
        assertTrue(ops(kept).none { it is Op.RemoveFile && it.path.dir == RemoteDir.TMP }, ops(kept).toString())
        // Set up again after an undo that tidied the first run's files: only the new run's go.
        val first = setup() + listOf(
            change(8, Op.Uninstall(launcher.pkg), Prior(present = true)),
            change(9, Op.RemoveFile(tmp("home-ok-0e2ffd0489942e55")), null),
            change(10, Op.RemoveFile(tmp("watchdog-0e2ffd0489942e55.log")), null),
        )
        val again = first + setup(RunToken("00000000000000bb"), from = 11)
        val p = plan(PlanRequest.UndoEverything(again, removeLauncher = true), ours, again)
        val removed = p.steps.filter { (it.op as? Op.RemoveFile)?.path?.dir == RemoteDir.TMP }
        assertEquals(listOf(tmp("home-ok-00000000000000bb"), tmp("watchdog-00000000000000bb.log")), removed.map { (it.op as Op.RemoveFile).path })
        assertEquals(listOf("Tidying up", ""), removed.map { it.narration }, "one line on the progress screen")
    }
}
