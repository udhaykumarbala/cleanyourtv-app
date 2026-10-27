package tv.cleanyour.core.backup

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.ops.HomePlaceholder
import tv.cleanyour.core.ops.OpRenderer
import tv.cleanyour.core.ops.RenderRefused
import tv.cleanyour.core.ops.UndoOrder

enum class RestoreStepKind {
    PUT_BACK,          // a TV command that puts one change back
    REMOVE_APP,        // uninstalls an app we installed; laptop scripts run it only when asked
    NOT_FROM_LAPTOP,   // cannot be put back with a shell line from a computer; the step says why
}

// One entry of a laptop restore script, in the order UndoOrder gives (see RestoreCommands).
data class RestoreStep(val kind: RestoreStepKind, val change: ChangeRecord, val inverse: Op?, val description: String,
    val lines: List<String>, val note: String?)

// Turns change records into the TV command lines that put the TV back. The order and the record
// per item come from UndoOrder (:core:ops), the helper the planner's UndoEverything uses too: each
// item (a package's state, a setting, the home screen, an app we installed, a file we wrote) goes
// back to the state of its EARLIEST record, phase by phase: package states, settings, the home
// screen, our apps, our files (R5-W6 M1). The inverse and the lines come from the app's own
// OpRenderer (inverse, then render), so commands.txt, restore.sh and restore.ps1 carry the lines
// the phone's Undo everything runs for the same records (ARCHITECTURE 5.4: "same lines"). Nothing
// here builds a shell command itself.
//
// A script cannot read the TV first, so unlike the phone it does not skip an item that is already
// back in its first state; that line changes nothing when it runs. What the records themselves show
// is used: an app we installed and later removed again has no step, nor does any other step aimed at
// it (its package state, a home screen in it, files in its folder), and a file we wrote and later
// deleted has none either. A null inverse of a package state or setting change means there is
// nothing to put back (it was already in that state, or it is debugging, which only the user turns
// on): no step either.
//
// Left out on purpose: ops that change nothing lasting on the TV (reads, settles, the HOME
// watchdog and its confirmation, restarts, debugging off, reboot, mkdir), and our own restore
// point files under /sdcard/cleanyourtv, which every plan rewrites. So the two small files the
// watchdog leaves in /data/local/tmp/cleanyourtv (its token file and its log) have no step here;
// the phone's Undo everything removes them with the launcher (DefaultPlanner.tidyWatchdogFiles).
class RestoreCommands(private val renderer: OpRenderer) {

    fun steps(changes: List<ChangeRecord>): List<RestoreStep> {
        val lasting = changes.filterNot { isTransient(it.op) || isRestorePointFile(it.op) }
        val latest = LinkedHashMap<UndoOrder.Target, ChangeRecord>()
        for (c in lasting.sortedWith(UndoOrder.CHRONOLOGICAL)) UndoOrder.target(c.op)?.let { latest[it] = c }
        // Packages the records leave uninstalled: their last app record is an uninstall.
        val gone: Set<PackageName> = latest.filter { (t, c) -> t is UndoOrder.Target.App && c.op is Op.Uninstall }
            .keys.map { (it as UndoOrder.Target.App).pkg }.toSet()
        // WP4 (G4): a placeholder SetHome prior (a journal from before 0.4.5) goes back to the stock
        // home the switch armed its watchdog with.
        val stockHome = HomePlaceholder.stockFromRecords(changes)
        return UndoOrder.undoOrder(lasting).mapNotNull { first ->
            when (val t = UndoOrder.target(first.op)) {
                is UndoOrder.Target.PackageEnabled -> if (t.pkg in gone) null else step(first, gone)
                is UndoOrder.Target.App -> when {
                    t.pkg !in gone -> step(first, gone)
                    first.prior?.present == false -> null                       // ours, and removed again since
                    else -> step(latest.getValue(t), gone)                     // it was there before us and is gone now
                }
                is UndoOrder.Target.File -> when {
                    ownerOf(t.path)?.let { it in gone } == true -> null          // its folder went with the app
                    latest.getValue(t).op is Op.RemoveFile && first.prior?.present == false -> null   // written and deleted again
                    // Absent before us (the Setup's wallpaper removal on a fresh launcher, RFC-DOCK-1) and
                    // written since: deleting it is the way back, as for a push of a new file.
                    first.op is Op.RemoveFile && first.prior?.present == false -> step(latest.getValue(t).copy(prior = first.prior), gone)
                    else -> step(first, gone)
                }
                UndoOrder.Target.Home -> {
                    val prior = first.prior
                    val recorded = prior?.home
                    if (prior != null && recorded != null && HomePlaceholder.isPlaceholder(recorded)) {
                        if (stockHome != null) step(first.copy(prior = prior.copy(home = stockHome)), gone)
                        else RestoreStep(RestoreStepKind.NOT_FROM_LAPTOP, first, null, TextSafety.ascii("${describeChange(first.op)} (plan step ${first.step})", 160),
                            emptyList(), "the record names no home screen of the TV's own to go back to")
                    } else step(first, gone)
                }
                else -> step(first, gone)
            }
        }
    }

    private fun step(change: ChangeRecord, gone: Set<PackageName>): RestoreStep? {
        val what = TextSafety.ascii("${describeChange(change.op)} (plan step ${change.step})", 160)
        val prior = change.prior
            ?: return RestoreStep(RestoreStepKind.NOT_FROM_LAPTOP, change, null, what, emptyList(),
                "there is no record of how it was before, so it is left as it is")
        // WP4 (G4, RF-LAUNCHER-240 F7): a script cannot read the TV first, so it never writes
        // accessibility back on: the person may have switched it off since our records.
        if (switchesAccessibilityOn(change.op, prior)) {
            return RestoreStep(RestoreStepKind.NOT_FROM_LAPTOP, change, null, what, emptyList(),
                "it would switch accessibility back on, which this app never does; switch it on in the TV's settings if you want it")
        }
        val inverse = renderer.inverse(change.op, prior)
            ?: return if (nothingToPutBack(change.op)) null
            else RestoreStep(RestoreStepKind.NOT_FROM_LAPTOP, change, null, what, emptyList(), cannotUndo(change.op))
        if (isTransient(inverse) || packageOf(inverse)?.let { it in gone } == true) return null
        val rendered = try {
            renderer.render(inverse)
        } catch (e: RenderRefused) {
            return RestoreStep(RestoreStepKind.NOT_FROM_LAPTOP, change, inverse, what, emptyList(),
                TextSafety.ascii("the app refused the command that would put it back: ${e.reason}", 200))
        }
        val description = TextSafety.ascii(describeInverse(inverse), 160)
        if (rendered.transfer != null) {
            return RestoreStep(RestoreStepKind.NOT_FROM_LAPTOP, change, inverse, description, emptyList(),
                "it needs a file from the phone; use the app's Undo everything instead")
        }
        val lines = rendered.lines.map { it.text }
        if (lines.isEmpty()) return null
        if (lines.any { it.isEmpty() || it.length > MAX_LINE || !TextSafety.isPrintableAscii(it) }) {
            return RestoreStep(RestoreStepKind.NOT_FROM_LAPTOP, change, inverse, description, emptyList(),
                "its command is not a single printable line")
        }
        val kind = if (inverse is Op.Uninstall) RestoreStepKind.REMOVE_APP else RestoreStepKind.PUT_BACK
        return RestoreStep(kind, change, inverse, description, lines, null)
    }

    companion object {
        const val MAX_LINE = 131_000

        fun isTransient(op: Op): Boolean = when (op) {
            is Op.Read, is Op.VerifyHome, is Op.Settle, is Op.ArmHomeWatchdog, is Op.ConfirmHome, is Op.KillWatchdog,
            Op.PressHome, is Op.RestartHome, is Op.StartActivity, Op.DebugOff, Op.Reboot, is Op.Mkdir, is Op.CurtainCue -> true
            else -> false
        }

        fun isRestorePointFile(op: Op): Boolean = when (op) {
            is Op.PushAsset -> op.dest.dir == RemoteDir.RESTORE
            is Op.RemoveFile -> op.path.dir == RemoteDir.RESTORE
            else -> false
        }

        // The inverse of this record would write accessibility_enabled 1 or a non-empty service list.
        private fun switchesAccessibilityOn(op: Op, prior: Prior): Boolean = op is Op.SettingsPut && when (op.key) {
            SettingKey.ACCESSIBILITY_ENABLED -> prior.settingValue == "1"
            SettingKey.ENABLED_ACCESSIBILITY -> !prior.settingValue.isNullOrEmpty()
            else -> false
        }

        // A package state or setting change whose renderer inverse is null: nothing to put back.
        private fun nothingToPutBack(op: Op): Boolean =
            op is Op.Disable || op is Op.Enable || op is Op.DefaultState || op is Op.SettingsPut

        // The package an undo op acts on, a home screen's package included.
        private fun packageOf(op: Op): PackageName? = when (op) {
            is Op.Enable -> op.pkg
            is Op.Disable -> op.pkg
            is Op.DefaultState -> op.pkg
            is Op.Uninstall -> op.pkg
            is Op.SetHome -> op.component.pkg
            else -> null
        }

        // The app whose folder holds a file (the launcher's overlay folder), so a file there goes with the app.
        private fun ownerOf(path: RemotePath): PackageName? =
            path.dir.path.substringAfter("/Android/data/", "").substringBefore('/').takeIf { it.isNotEmpty() }?.let(::PackageName)

        fun describeChange(op: Op): String = when (op) {
            is Op.Disable -> "Switched off ${op.pkg.value}"
            is Op.Enable -> "Switched on ${op.pkg.value}"
            is Op.DefaultState -> "Reset ${op.pkg.value} to its default state"
            is Op.Install -> "Installed ${op.pkg.value}"
            is Op.InstallStream -> "Installed ${op.pkg.value}"
            is Op.Uninstall -> "Removed ${op.pkg.value}"
            is Op.SettingsPut -> if (op.value == null) "Cleared the setting ${op.key.key}" else "Set ${op.key.key} to ${op.value}"
            is Op.SetHome -> "Made ${op.component.flat} the home screen"
            is Op.PushAsset -> "Wrote ${op.dest.absolute}"
            is Op.RemoveFile -> "Deleted ${op.path.absolute}"
            else -> "Ran ${ChangeJson.typeOf(op)}"
        }

        fun describeInverse(op: Op): String = when (op) {
            is Op.Enable -> "Switch back on: ${op.pkg.value}"
            is Op.Disable -> "Switch off again: ${op.pkg.value}"
            is Op.DefaultState -> "Back to its default state: ${op.pkg.value}"
            is Op.Uninstall -> "Remove the app: ${op.pkg.value}"
            is Op.SettingsPut -> if (op.value == null) "Setting ${op.key.key} back to not set" else "Setting ${op.key.key} back to ${op.value}"
            is Op.SetHome -> "Home screen back to ${op.component.flat}"
            is Op.RemoveFile -> "Delete ${op.path.absolute}"
            else -> describeChange(op)
        }

        private fun cannotUndo(op: Op): String = when (op) {
            is Op.PushAsset -> "the earlier file has to be pushed from the phone; use Undo everything in the app"
            is Op.Install, is Op.InstallStream -> "the app was already on the TV, so it stays"
            is Op.Uninstall -> "the app cannot be reinstalled from here"
            else -> "this change has no undo"
        }
    }
}
