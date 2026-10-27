package tv.cleanyour.core.spec

import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Issue #111 (Xiaomi Mi TV 4i, magnolia): "the patch wall button on the remote is not working". The
// test list, like every upstream Xiaomi list, switched off PatchWall (com.mitv.tvhome.atv), and with
// it the remote's PatchWall button (upstream's xiaomi-mitv-axso2 notes the same). On a TV where the
// app keeps the TV's own home screen, PatchWall is still how the owner browses, so it is the owner's
// choice everywhere: an ask row, kept on by default, that Customise can still switch off.
class Issue111PatchWallTest {
    private val patchwall = PackageName("com.mitv.tvhome.atv")

    @Test fun issue111PatchWallIsAnAskRowInEveryShippedList() {
        val lists = BundledProfileSource.bundled.profiles.filter { p -> p.groups.any { g -> g.packages.any { it.name == patchwall } } }
        assertTrue(lists.size >= 15, "the Xiaomi lists that name PatchWall: ${lists.map { it.id }}")
        for (p in lists) {
            val groups = p.groups.filter { g -> g.packages.any { it.name == patchwall } }
            assertEquals(listOf(GroupDefault.ASK), groups.map { it.default }, p.id)
        }
    }

    @Test fun theXiaomiSetOffersPatchWallAndNeverSwitchesItOff() {
        val xiaomi = BundledProfileSource.bundled.sureSets.single { it.brand == "Xiaomi" }
        val where = xiaomi.groups.filter { g -> g.packages.any { it.name == patchwall } }
        assertEquals(listOf(GroupDefault.ASK), where.map { it.default })
        assertTrue(xiaomi.groups.none { it.default == GroupDefault.DISABLE && it.packages.any { e -> e.name == patchwall } })
        // Every other row of the set is still a default-off row, as D26 makes them.
        assertTrue(xiaomi.groups.filter { it.default == GroupDefault.ASK }.all { g -> g.packages.all { it.name == patchwall } })
    }

    // The test list made for the Mi TV 4i (a candidate, test builds only) gets the same rule.
    @Test fun theMiTv4iCandidateOffersPatchWall() {
        val dir = File(Fixtures.repoRoot, "core/spec/candidates")
        val meta = ProfileMeta.decode(File(dir, "candidate-meta.json").readText())
        val profile = DebloatTxt.parse(File(dir, "xiaomi-mitv4i-magnolia/debloat.txt").readText(), "xiaomi-mitv4i-magnolia", meta.profiles["xiaomi-mitv4i-magnolia"])
        val groups = profile.groups.filter { g -> g.packages.any { it.name == patchwall } }
        assertEquals(listOf(GroupDefault.ASK), groups.map { it.default })
    }

    @Test fun theRowSaysWhatSwitchingItOffCosts() {
        val why = ProfileCategory.OWNERS_CHOICE.getValue(patchwall)
        assertTrue("the remote's PatchWall button stops working while the TV's own home screen is in use" in why, why)
    }
}
