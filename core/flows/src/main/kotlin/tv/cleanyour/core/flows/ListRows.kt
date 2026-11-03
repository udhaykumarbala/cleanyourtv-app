package tv.cleanyour.core.flows

import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.planner.PlanSummary
import tv.cleanyour.core.spec.ProfileResolution

// 0.4.9 (issues #1222, #4896): an exact list that plans "0 to switch off" said nothing more, so a TV
// cleaned before (every row already off) looked like a list that matched nothing. The log, and so
// the report, now counts what became of each row of the TV's list, in fixed words and numbers only
// (no package names): switched off by this plan, not on the TV, already off, protected, left on to
// ask, kept by the person. Each list package counts once, in the first group that names it, as the
// planner does. Null without a list (an unlisted TV has no rows to count).
object ListRows {
    data class Counts(val off: Int, val absent: Int, val alreadyOff: Int, val protected: Int, val ask: Int, val kept: Int) {
        val line: String get() = "list rows: $off to switch off, $absent not on the TV, $alreadyOff already off, $protected protected, $ask ask, $kept kept"
    }

    fun count(resolution: ProfileResolution, snapshot: Snapshot, summary: PlanSummary, kept: Set<PackageName>): Counts? {
        val groups = when (resolution) {
            is ProfileResolution.Exact -> resolution.profile.groups
            is ProfileResolution.BrandSureSet -> resolution.sureSet
            is ProfileResolution.None -> return null
        }
        val rows = summary.groups.flatMap { it.rows }.groupBy { it.pkg }.mapValues { it.value.first() }
        var off = 0; var absent = 0; var alreadyOff = 0; var protected = 0; var ask = 0; var keptRows = 0
        for (pkg in groups.flatMap { g -> g.packages.map { it.name } }.distinct()) {
            val row = rows[pkg]
            when {
                pkg !in snapshot.packages -> absent++
                // The planner leaves out a row that is off already (and not protected for another reason).
                row == null -> alreadyOff++
                row.off -> off++
                row.protected -> protected++
                pkg in kept -> keptRows++
                else -> ask++
            }
        }
        return Counts(off, absent, alreadyOff, protected, ask, keptRows)
    }
}
