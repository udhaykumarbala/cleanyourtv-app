package tv.cleanyour.core.flows

import java.time.LocalDate
import java.time.ZoneOffset
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.DeviceFacts
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.HomeClaimant
import tv.cleanyour.core.model.HomeResolution
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.testing.fixtures.Samples

// A compact TV report (0.3.3 and later, TvReport.compact) or a problem report back into what the
// app had: the TV's facts and a snapshot as the probe would read it. The person's own apps get
// made-up names (person.app1, ...), a Home claimant the report does not name is one of them
// (person.home1, ...), and "Switched off by this app" is a list of system apps switched off by the
// user (disabled-user), as the change records leave them. A problem report lists no packages, so
// it gives the claimants and Home as on, the switched-off list as off, and nothing else.
object ReportText {
    class Parsed(val facts: DeviceFacts, val snapshot: Snapshot, val switchedOff: Set<PackageName>, val app: String,
                 val profileLine: String, val homeLine: String, val personHomes: List<PackageName>)

    fun fixture(name: String): String =
        checkNotNull(ReportText::class.java.getResourceAsStream("/reports/$name")) { name }.readBytes().decodeToString()

    private fun st(e: EnabledState, system: Boolean) = PackageState(e, system, 1, null, false, false, null, false)

    fun parse(text: String): Parsed {
        val lines = text.lines()
        fun v(key: String) = lines.firstOrNull { it.startsWith("$key: ") }?.removePrefix("$key: ")?.trim() ?: "unknown"
        val android = v("Android")
        val facts = DeviceFacts(
            brand = v("Brand"), manufacturer = v("Manufacturer"), model = v("Model"), device = v("Device"), serialLast4 = "0000",
            sdk = Regex("SDK (\\d+)").find(android)!!.groupValues[1].toInt(), release = android.substringBefore(" ("),
            fingerprint = v("Build"), abis = listOf("armeabi-v7a"), ramMb = 2048, hasGms = v("Google Play services") == "yes",
            adbSecure = true, adbFeatures = emptySet(), installRefusal = false, locale = "en-GB",
        )
        val personHomes = mutableListOf<PackageName>()
        fun component(flat: String): Component =
            if ('/' in flat) Component(PackageName(flat.substringBefore('/')), flat.substringAfter('/'))
            else Component(PackageName("person.home${personHomes.size + 1}"), ".Main").also { personHomes += it.pkg }
        val claimants = lines.filter { it.startsWith("Claimant: ") }.map {
            val c = it.removePrefix("Claimant: ")
            HomeClaimant(component(c.substringBeforeLast(" priority ")), c.substringAfterLast(" priority ").trim().toInt())
        }
        val res = v("Resolves to")
        val resolved = when {
            '/' in res -> component(res)
            res.startsWith("an app you installed") -> personHomes.firstOrNull()?.let { Component(it, ".Main") } ?: component(res)
            else -> null
        }
        val packages = LinkedHashMap<PackageName, PackageState>()
        val switchedOff = linkedSetOf<PackageName>()
        fun names(l: String) = l.split(',').map { it.trim() }.filter { it.isNotEmpty() && ' ' !in it }.map(::PackageName)
        if ("System apps (" in text) {
            var group: EnabledState? = null
            var ours = false
            for (raw in lines.dropWhile { !it.startsWith("System apps (") }.drop(1)) {
                val line = raw.trim()
                if (line.startsWith("Other apps:")) {
                    val n = line.removePrefix("Other apps:").trim().substringBefore(" ").toInt()
                    for (i in 1..n - personHomes.size) packages[PackageName("person.app$i")] = st(EnabledState.ENABLED, false)
                    break
                }
                if (line.isBlank()) continue
                if (line.endsWith("):")) {
                    ours = line.startsWith("Switched off by this app")
                    group = when {
                        ours || line.startsWith("Off, disabled-user") -> EnabledState.DISABLED_USER
                        line.startsWith("Off, disabled") -> EnabledState.DISABLED
                        line.startsWith("On, enabled") -> EnabledState.ENABLED
                        else -> EnabledState.DEFAULT
                    }
                    continue
                }
                for (p in names(line)) { packages[p] = st(group!!, true); if (ours) switchedOff += p }
            }
        } else {
            val off = lines.dropWhile { !it.startsWith("Switched off by this app (") }.drop(1)
                .takeWhile { it.isNotBlank() && !it.startsWith("and ") && !it.startsWith("Diagnostics") }
            for (l in off) for (p in names(l)) { packages[p] = st(EnabledState.DISABLED_USER, true); switchedOff += p }
            for (c in claimants) packages.putIfAbsent(c.component.pkg, st(EnabledState.DEFAULT, c.component.pkg !in personHomes))
        }
        for (p in personHomes) packages[p] = st(EnabledState.ENABLED, false)
        val read = v("Read from the TV")
        val day = LocalDate.parse(read.substringBefore(",")).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val snap = Samples.snapshot(facts = facts, packages = packages, home = HomeResolution(resolved, claimants),
            reason = if (read.endsWith("before any change")) SnapshotReason.BASELINE else SnapshotReason.MANUAL, takenAtMs = day)
        return Parsed(facts, snap, switchedOff, v("App"), v("Profile"), v("Home switch"), personHomes)
    }
}
