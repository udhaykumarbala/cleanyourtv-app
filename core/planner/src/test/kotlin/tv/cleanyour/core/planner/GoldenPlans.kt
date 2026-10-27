package tv.cleanyour.core.planner

import tv.cleanyour.core.ops.Transfer
import tv.cleanyour.testing.fixtures.Fixtures
import java.io.File
import kotlin.test.fail

// "Show commands" as the person sees it (SetupMachine.commandsOf: each rendered line, a transfer
// as its sync line), with the step's group, failure policy, narration and prior around it, pinned
// under src/test/resources/golden-plans so a change to any line the person can read before tapping
// is a reviewed diff.
object GoldenPlans {
    fun check(name: String, p: Plan) {
        val sb = StringBuilder("# ").append(name).append('\n')
        for (s in p.steps) {
            sb.append("step ").append(s.index).append(": group=").append(s.group).append(" policy=").append(s.onFailure.name)
                .append(" narration=").append(s.narration).append('\n')
            s.prior?.let { pr -> sb.append("  prior: ").append(listOfNotNull(pr.packageState?.let { "packageState=$it" }, pr.settingValue?.let { "settingValue=$it" },
                pr.home?.let { "home=${it.flat}" }, pr.present?.let { "present=$it" }).joinToString(" ").ifEmpty { "empty" }).append('\n') }
            s.rendered.lines.forEach { sb.append("  ").append(it.text).append('\n') }
            when (val t = s.rendered.transfer) {
                is Transfer.Push -> sb.append("  sync:${t.dest.absolute} ${t.src.sha256.hex}\n")
                is Transfer.Pull -> sb.append("  sync:${t.src.absolute} ${t.maxBytes}\n")
                is Transfer.StreamInstall -> sb.append("  sync:${t.src.path} ${t.size}\n")
                null -> {}
            }
        }
        val actual = sb.toString()
        val file = File(Fixtures.repoRoot, "core/planner/src/test/resources/golden-plans/$name.txt")
        val expected = if (file.isFile) file.readText() else null
        if (expected != actual) {
            // Written beside the build for review, never over the golden file.
            File(Fixtures.repoRoot, "core/planner/build/golden-actual").apply { mkdirs() }.resolve("$name.txt").writeText(actual)
            fail("golden-plans/$name.txt ${if (expected == null) "missing" else "differs"}; actual:\n$actual")
        }
    }
}
