package tv.cleanyour.core.ops

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.Op

// A HOME answer that is not a home screen (WP4, B5): the system chooser (package android, its
// ResolverActivity), a FallbackHome placeholder the firmware shows while no launcher is on
// (com.android.tv.settings/.system.FallbackHome, com.tcl.keycustomfunctionservice/.FallbackHome),
// and the first-run setup wizard (setupwraith's RecoveryActivity, com.android.provision). Never
// recorded as the TV's own home screen, so neither step R nor Undo everything hands HOME to one.
// Here in :core:ops so the planner, the engine and the restore files (:core:backup) share it.
object HomePlaceholder {
    fun isPlaceholder(c: Component): Boolean {
        val pkg = c.pkg.value
        return pkg == Denylist.FRAMEWORK || "FallbackHome" in c.cls || pkg == "com.android.provision" ||
            pkg in Denylist.setupWizards || pkg in Denylist.vendorSetupWizards || "setupwraith" in pkg.lowercase()
    }

    // The TV's own home screen the home screen switch handed back to, from the change records: the
    // stock component of the earliest ArmHomeWatchdog (the takeover arms it with StockHome.of).
    // Journals from before 0.4.5 may hold a placeholder as a SetHome prior; this is what replaces it.
    fun stockFromRecords(changes: List<ChangeRecord>): Component? =
        changes.filter { it.op is Op.ArmHomeWatchdog }.minWithOrNull(UndoOrder.CHRONOLOGICAL)?.let { (it.op as Op.ArmHomeWatchdog).stock }
            ?.takeUnless { isPlaceholder(it) }
}
