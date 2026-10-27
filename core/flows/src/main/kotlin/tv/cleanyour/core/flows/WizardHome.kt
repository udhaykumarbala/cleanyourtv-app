package tv.cleanyour.core.flows

import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.ops.HomePlaceholder
import tv.cleanyour.core.planner.HomeSwitchRisk

// 0.4.6: the safety net under R0 (HomeSwitchRisk.wizardStaysOnSafely), for every TV. The switch may
// leave a setup wizard on when it claims no Home; should the wizard become a Home claimant later
// (a TV update, a firmware that turns it back on), it could take Home while the TV's own home
// screen is off, the RecoveryActivity risk of upstream #506 and #422. So each time Manage connects,
// it reads the TV's Home again, and when ours is Home, the TV's own home screen is off (no claimant)
// and a setup wizard is now a claimant, Manage says so and offers the way back: "Use the TV's own
// home screen" (PlanRequest.UseStockHome), the existing undo of the switch, which switches nothing
// else on (no accessibility, no Wi-Fi).
object WizardHome {
    // `home`: what the TV answers now; `latest`: the newest stored snapshot (its package states, so a
    // wizard the TV has switched off does not count); `stock`: the TV's own home screen (stockOf).
    fun applies(home: HomeResolution, latest: Snapshot?, profile: Profile?, stock: Component?): Boolean {
        if (home.resolved?.pkg != Launcher.pkg || stock == null || latest == null) return false
        if (home.claimants.any { it.component.pkg == stock.pkg }) return false   // the TV's own home screen is on
        return HomeSwitchRisk.homeWizards(profile, latest.copy(home = home)).isNotEmpty()
    }

    // The TV's own home screen the switch handed Home away from: the stock home its watchdog was
    // armed with (a brand set or no profile has no list of its own), else the exact profile's.
    fun stockOf(changes: List<ChangeRecord>, profile: Profile?): Component? =
        HomePlaceholder.stockFromRecords(changes) ?: profile?.stockLaunchers?.firstOrNull()
}
