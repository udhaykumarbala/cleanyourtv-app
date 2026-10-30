package tv.cleanyour.core.flows

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.SnapshotReason
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.store.DocumentStore

// 0.3.2: the plain text reports a person can post on GitHub or send
// themselves, built from what this phone already stored (the probe snapshot), so no TV connection is
// needed. The app itself sends nothing: the preview screen copies or shares the text, and opens the
// issue form in the browser only when the person taps it (with the report in the link when it fits,
// 0.3.3 D1; nothing is public until the person posts the form).
//
// What a report may hold: the app version and build, the phone model and Android version, the TV's
// brand, maker, model, device codename, SDK, Android release and build fingerprint, how the profile
// resolved, the home screen switch decision, the HOME resolver and claimants, and the system
// packages with their state. Everything else the app knows is left out on purpose: the TV's serial
// (DeviceFacts.serialLast4), its id (TvId is derived from the serial), its name and room, every
// address, the saved Wi-Fi, the settings the probe read (device_name is the person's own words),
// the input names, the key and every code. Apps the person installed are only counted: their names
// say something about the person, the factory's system apps do not. That holds for the HOME lines
// too: a launcher the person installed is named only as "an app you installed". A package whose
// system flag the probe could not read counts as the person's (the safe side).
//
// The text is English on purpose: it is for the maintainers, and a fixed shape keeps it easy to
// read and to compare. Sections and lines keep a fixed order (packages by name, claimants by
// priority), so the same TV gives the same report.

// The app and the phone the report comes from; the app fills it from its versionName, its store
// flavour and android.os.Build.
// 0.4.8: `testBuild`, a build with any of the test switches (candidate lists, R0 for any brand, the Home button helper),
// for the confirmation's "Build kind" line; the graduation rule counts test builds.
data class AppAbout(val version: String, val flavour: String, val phone: String, val phoneAndroid: String, val testBuild: Boolean = false) {
    val appLine: String get() = "$version ($flavour)"
    val buildKind: String get() = if (testBuild) "test build" else "release"
    // "0.4.8 (github, test build)": what counted a restart (RestartTally.build).
    val countLine: String get() = "$version ($flavour, $buildKind)"
    val phoneLine: String get() = listOf(phone, phoneAndroid).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "unknown" }
}

// Why S3 did or did not plan the new home screen, as the wizard decided it (SetupMachine.homeBlock and
// the plan). Kept beside the TV record, so Manage's report knows it without the TV.
// beta3 (issues #701, #1273): `app` and `profile`, the app version and the list (LogCodes.profile)
// Setup decided with; null in a decision kept by 0.4.6-beta2 or earlier. A decision is Setup's last
// word and is not made again until Setup runs again, so after an update that brought the TV a list
// (or a rule that changed), Manage's report said "blocked WIZARD_STAYS_ON" beside "Profile: exact"
// and the Home screen page said we did not know the TV. `earlier` tells such a decision.
// RFC-HELPER-1 (test build): `redirect`, the Home button helper was planned instead of the switch
// (decision.txt "home=redirect", the report's "Home switch: redirect").
data class HomeDecision(val planned: Boolean, val block: String? = null, val alreadyOn: Boolean = false,
                        val app: String? = null, val profile: String? = null, val redirect: Boolean = false) {
    val line: String get() = when {
        redirect -> "home switch redirect"
        planned -> "home switch planned"
        block != null -> "home switch blocked $block"
        alreadyOn -> "home switch not needed (the new home screen is on)"
        else -> "home switch not in the plan"
    }

    // A block decided by another version of the app, or with another list than the TV resolves to
    // now (`now`). beta3 always writes app=, so a decision without it is from 0.4.6-beta2 or older
    // (beta3 QA: a 0.4.5 UNKNOWN_HOME on a TV with no list was never told as earlier). The TV's own
    // reasons (KEEPS_ITS_LINE) do not change with the app.
    fun earlier(app: String, now: ProfileResolution?): Boolean {
        val b = block ?: return false
        if (HomeBlock.entries.firstOrNull { it.name == b } in KEEPS_ITS_LINE) return false
        if (this.app == null || this.app != app) return true
        return profile != LogCodes.profile(now)
    }

    companion object {
        // The blocks that come from the TV's own facts or the person's choice: an app update does not
        // change them, so an earlier Setup's decision keeps its line and next step.
        // beta4: VENDOR_HOME, a system service's FallbackHome on the TV.
        val KEEPS_ITS_LINE: Set<HomeBlock> = setOf(HomeBlock.TV_REFUSES, HomeBlock.OLD_ANDROID, HomeBlock.BOOT_LOOP, HomeBlock.CASTING,
            HomeBlock.REMOTE_BUTTONS, HomeBlock.HOME_CHANGED, HomeBlock.VENDOR_HOME)

        fun of(block: HomeBlock, planHasSwitch: Boolean, alreadyOn: Boolean, app: String? = null, profile: String? = null, redirect: Boolean = false): HomeDecision = when {
            redirect -> HomeDecision(false, app = app, profile = profile, redirect = true)
            block != HomeBlock.NONE -> HomeDecision(false, block.name, app = app, profile = profile)
            planHasSwitch -> HomeDecision(true, app = app, profile = profile)
            else -> HomeDecision(false, alreadyOn = alreadyOn, app = app, profile = profile)
        }
    }
}

// tvs/<id>/decision.txt, one line ("home=planned", "home=blocked:WIZARD_STAYS_ON", "home=on" or
// "home=none"). Forget this TV and Delete everything remove it with the TV's folder.
class HomeDecisions(private val store: DocumentStore) {
    private fun path(id: TvId) = "tvs/${id.value}/decision.txt"

    suspend fun save(id: TvId, d: HomeDecision) {
        val v = when {
            d.redirect -> "redirect"
            d.planned -> "planned"
            d.block != null -> "blocked:${d.block}"
            d.alreadyOn -> "on"
            else -> "none"
        }
        // beta3: "app=" and "profile=" lines after it, each only when known; an older file has none.
        val extra = listOfNotNull(d.app?.let { "app=${oneLine(it)}" }, d.profile?.let { "profile=${oneLine(it)}" })
        store.write(path(id), (listOf("home=$v") + extra).joinToString("") { "$it\n" }.encodeToByteArray())
    }

    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ').trim()

    suspend fun load(id: TvId): HomeDecision? = store.read(path(id)) { bytes ->
        val lines = bytes.decodeToString().lines()
        fun value(key: String) = lines.firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")?.trim()?.ifEmpty { null }
        val v = value("home")
        val decision = when {
            v == null -> null
            v == "planned" -> HomeDecision(true)
            v.startsWith("blocked:") -> v.removePrefix("blocked:").takeIf { b -> HomeBlock.entries.any { it.name == b } }?.let { HomeDecision(false, it) }
            v == "on" -> HomeDecision(false, alreadyOn = true)
            v == "redirect" -> HomeDecision(false, redirect = true)
            v == "none" -> HomeDecision(false)
            else -> null
        }
        decision?.copy(app = value("app"), profile = value("profile"))
    }
}

// Everything a report is built from.
data class TvReportInput(
    val about: AppAbout,
    val snapshot: Snapshot,
    val profile: ProfileResolution?,
    val home: HomeDecision?,
    val switchedOffByApp: Set<PackageName> = emptySet(),
    // beta3: the home decision is an earlier Setup's (HomeDecision.earlier), so the line says so.
    val homeEarlier: Boolean = false,
    // 0.4.7: Manage's last connect found the TV's own home screen, which this app switched off, claiming Home again
    // (StockHomeBack: XGIMI's remote buttons switch launcherx back on).
    val stockHomeBack: Boolean = false,
    // RFC-HELPER-1 (test build): the Accessibility section (PLAN 4.7); off in a release build.
    val accessibility: Boolean = tv.cleanyour.core.planner.HomeHelper.BUILD_ON,
    // 0.4.8: what this phone counted across the TV's restarts (Restarts), for every report of a TV it switched.
    val restarts: RestartFacts? = null,
)

// 0.4.8: the restart count as a report says it: the home path, held and failed, when, which app counted, and the
// look's route (the last look this app sent: by the launcher's ThemeProvider, a sync push, or none).
data class RestartFacts(val path: HomePath, val tally: RestartTally, val lookRoute: String)

object TvReport {
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    // B1 "Help add this TV": the whole TV report, system packages included, one per line. Share sends it.
    fun text(input: TvReportInput): String = buildString {
        appendLine("Clean Your TV: TV report")
        appendHeader(input.about)
        appendTv(input, Packages.LINES)
    }.trimEnd() + "\n"

    // 0.3.3 D1: the same report in fewer characters, for the GitHub form's report box, which it
    // reaches inside the link (IssueLinks). The header and the HOME lines as in text(), then the
    // system packages grouped by state, each group a sorted, comma separated list wrapped at WRAP
    // characters. The same facts as text(): a package the app switched off and that is still off is
    // in the first group; one that came back on is in its state's group. Real reports of 110 to 130
    // system packages come to about 5,000 characters encoded, under the link's cap.
    fun compact(input: TvReportInput): String = buildString {
        appendLine("Clean Your TV: TV report")
        appendHeader(input.about)
        appendTv(input, Packages.GROUPED)
    }.trimEnd() + "\n"

    const val WRAP = 100

    private enum class Packages { NONE, LINES, GROUPED }

    // B2 "Report a problem": the TV summary (no package list) when a TV was read, then the redacted
    // diagnostics log (already redacted by the app's logger).
    // 0.4.6: from a card, what happened there comes after the header (ReportContext): the screen,
    // the card, the step and, for a connect, the phone's side; each value cleaned like the TV's own
    // text, with every address masked (ConnectErrorSummary.redact) and the app's redactor (`redact`)
    // over it. A connect report's log lines get the same address mask as "Copy error". `names` false
    // gives the count of what this app switched off instead of the names (ProblemReports.fit).
    fun problem(
        about: AppAbout, input: TvReportInput?, log: String, context: ReportContext? = null, names: Boolean = true,
        redact: (String) -> String = { it },
    ): String = buildString {
        appendLine("Clean Your TV: problem report")
        appendHeader(about)
        if (context != null) appendContext(context, redact)
        if (input != null) appendTv(input, Packages.NONE, names) else { appendLine(); appendLine("TV: none read yet") }
        appendLine()
        appendLine("Diagnostics log (addresses, serial numbers, keys and codes removed)")
        append(if (context?.connect == true) log.trimEnd().lines().joinToString("\n") { ConnectErrorSummary.redact(it, about.version) } else log.trimEnd())
    }.trimEnd() + "\n"

    // One value from a card, on one line, at most `max` characters, masked twice.
    fun said(s: String, redact: (String) -> String, max: Int = 200): String =
        redact(ConnectErrorSummary.redact(LogCodes.clean(s, max))).ifEmpty { "unknown" }

    private fun StringBuilder.appendContext(c: ReportContext, redact: (String) -> String) {
        appendLine()
        appendLine("What happened")
        appendLine("Screen: ${said(c.screen, redact, 60)}")
        appendLine("Card: ${said(c.card, redact)} (${said(c.case, redact, 60)})")
        c.step?.let { appendLine("Step: ${said(it, redact, 400)}") }
        if (!c.connect) c.error?.let { appendLine("Error: ${said(it, redact, 120)}") }
        if (c.connect) {
            appendLine()
            appendLine("Connection")
            appendLine("Found by: ${c.found?.let { said(it, redact, 120) } ?: "unknown"}")
            appendLine("Port: ${c.port ?: "unknown"}")
            appendLine("Error: ${said(c.error ?: "unknown", redact, 120)}${c.detail?.let { " (${said(it, redact, 160)})" } ?: ""}")
        }
    }

    // RFC-HELPER-1 (PLAN 4.7): what the TV says about the Home button helper, from the snapshot's two
    // settings and the overlay. Other services are only counted, never named, as the person's apps.
    // Bound or not is not read (no dumpsys accessibility): "listed" and "Switched on" stand for it.
    private fun StringBuilder.appendAccessibility(s: Snapshot) {
        val list = tv.cleanyour.core.planner.HomeHelper.list(s)
        val listed = tv.cleanyour.core.ops.HelperLines.listed(list)
        val entries = tv.cleanyour.core.ops.HelperLines.entries(list)
        val others = tv.cleanyour.core.ops.HelperLines.others(list).size
        val doc = s.launcherOverlay?.let { (tv.cleanyour.core.spec.LauncherConfigCodec.decode(it) as? tv.cleanyour.core.spec.DecodeResult.Ok)?.doc }
        val redirect = doc?.let { tv.cleanyour.core.spec.HomeHelperKeys.redirectOf(it) }
        val keyFilter = doc?.let { tv.cleanyour.core.spec.HomeHelperKeys.keyFilterOf(it) }
        val on = s.settings[tv.cleanyour.core.model.SettingKey.ACCESSIBILITY_ENABLED]
        appendLine()
        appendLine("Accessibility")
        appendLine("Switched on: ${when (on) { "1" -> "yes"; "0" -> "no"; else -> "unknown" }}")
        appendLine("Services listed: " + when {
            entries.isEmpty() -> "none"
            listed -> "${entries.size} (the new home screen's Home button helper, $others other, not named)"
            else -> "${entries.size} (not named)"
        })
        appendLine("Home button helper: " + when {
            listed && on == "1" -> "listed, accessibility on"
            listed -> "listed, accessibility off"
            else -> "not listed"
        })
        appendLine("Home redirect: ${redirect?.value ?: "none"}")
        appendLine("Key filter: ${when (keyFilter) { false -> "off"; true -> "on"; null -> "the launcher's default" }}")
        appendLine("Button map: ${doc?.appKeys?.size?.takeIf { it > 0 }?.let { "$it buttons" } ?: "none"}")
    }

    // 0.4.8: the restart count (Restarts), the same lines in a problem report as in the confirmation (ItWorked). The
    // issue bot reads "Held:" and "Failed:" (research/release-0.4.8/CONFIRMATION-FORMAT.md); dates are UTC days.
    private fun StringBuilder.appendRestarts(r: RestartFacts) {
        val t = r.tally
        appendLine()
        appendLine("Restarts")
        appendLine("Home path: ${r.path.word}")
        appendLine("Held: ${t.held}")
        appendLine("Failed: ${t.failed}")
        appendLine("Last restart: ${when { t.counted == 0 -> "none counted yet"; t.lastFailed -> "failed"; else -> "held" }}")
        appendLine("First counted: ${t.firstAtMs?.let { DAY.format(Instant.ofEpochMilli(it)) } ?: "none"}")
        appendLine("Last counted: ${t.lastAtMs?.let { DAY.format(Instant.ofEpochMilli(it)) } ?: "none"}")
        appendLine("Counted by: ${t.build?.let { own(it) } ?: "none"}")
        appendLine("Look route: ${r.lookRoute}")
        // 0.4.8 (issues #2061, #1799): the helper's path reads its accessibility settings too (Restarts.HelperNow).
        appendLine("Checked: ${if (r.path == HomePath.HELPER) "boot_id, Home and the helper's accessibility settings" else "boot_id and Home only"}")
    }

    // 0.4.8 (PLAN item 2): "Tell us it worked", the confirmation the issue bot counts. A fixed block of the app, the
    // build kind, the phone, the TV's facts, how its home screen resolved and the restart count; no package list, no
    // log, and nothing the problem report leaves out either (no serial, no id, no name, no address, no Wi-Fi, no key).
    // The format is research/release-0.4.8/CONFIRMATION-FORMAT.md; change both together.
    fun confirmation(about: AppAbout, input: TvReportInput, restarts: RestartFacts): String = buildString {
        val s = input.snapshot
        val f = s.facts
        appendLine("Clean Your TV: confirmation")
        appendLine("Format: ${ItWorked.FORMAT}")
        appendHeaderOnly(about)
        appendLine("Build kind: ${about.buildKind}")
        appendLine("Phone: ${v(about.phoneLine)}")
        appendLine()
        appendLine("TV")
        appendLine("Brand: ${v(f.brand)}")
        appendLine("Manufacturer: ${v(f.manufacturer)}")
        appendLine("Model: ${v(f.model)}")
        appendLine("Device: ${v(f.device)}")
        appendLine("Android: ${v(f.release)} (SDK ${f.sdk})")
        appendLine("Build: ${Scrub.text(oneLine(f.fingerprint), codes = false).ifEmpty { "unknown" }}")
        appendLine()
        appendLine("Home screen")
        appendLine("Profile: ${LogCodes.profile(input.profile)}")
        appendLine("Home switch: ${input.home?.line?.removePrefix("home switch ") ?: "not recorded"}")
        appendRestarts(restarts)
    }.trimEnd() + "\n"

    private fun StringBuilder.appendHeaderOnly(about: AppAbout) {
        appendLine("App: ${own(about.appLine)}")
    }

    private fun StringBuilder.appendHeader(about: AppAbout) {
        appendLine("App: ${own(about.appLine)}")
        appendLine("Phone: ${v(about.phoneLine)}")
    }

    private fun StringBuilder.appendTv(input: TvReportInput, packages: Packages, names: Boolean = true) {
        val s = input.snapshot
        val f = s.facts
        appendLine()
        appendLine("TV")
        appendLine("Brand: ${v(f.brand)}")
        appendLine("Manufacturer: ${v(f.manufacturer)}")
        appendLine("Model: ${v(f.model)}")
        appendLine("Device: ${v(f.device)}")
        appendLine("Android: ${v(f.release)} (SDK ${f.sdk})")
        appendLine("Build: ${Scrub.text(oneLine(f.fingerprint), codes = false).ifEmpty { "unknown" }}")
        appendLine("Google Play services: ${if (f.hasGms) "yes" else "no"}")
        appendLine("Home screen it came with: ${platformOf(s)}")
        appendLine("Read from the TV: ${DAY.format(Instant.ofEpochMilli(s.takenAtMs))}${if (s.reason == SnapshotReason.BASELINE) ", before any change" else ""}")
        appendLine()
        appendLine("Matching")
        appendLine("Profile: ${LogCodes.profile(input.profile)}")
        appendLine("Home switch: ${input.home?.line?.removePrefix("home switch ") ?: "not recorded"}${if (input.homeEarlier) earlierNote(input.home) else ""}")
        appendLine()
        appendLine("Home screen")
        appendLine("Resolves to: ${s.home.resolved?.let { named(s, it) } ?: "nothing (or the chooser)"}")
        // Named ones first by priority and name, then the person's own apps by priority only, so the
        // order never depends on a name the report leaves out.
        val claimants = s.home.claimants.sortedWith(
            compareByDescending<tv.cleanyour.core.model.HomeClaimant> { it.priority }
                .thenBy { if (nameable(s, it.component.pkg)) 0 else 1 }
                .thenBy { if (nameable(s, it.component.pkg)) it.component.flat else "" },
        )
        if (claimants.isEmpty()) appendLine("Claimants: none")
        for (c in claimants) appendLine("Claimant: ${named(s, c.component)} priority ${c.priority}")
        if (input.stockHomeBack) appendLine("Since then: the TV's own home screen, switched off by this app, is on again and claims Home")
        // Only when the probe read either setting or the overlay names a redirect: a report from a
        // snapshot that knows nothing about accessibility stays as it was.
        if (input.accessibility && (tv.cleanyour.core.model.SettingKey.ENABLED_ACCESSIBILITY in s.settings ||
                tv.cleanyour.core.model.SettingKey.ACCESSIBILITY_ENABLED in s.settings || tv.cleanyour.core.spec.HomeHelperKeys.redirectOf(s.launcherOverlay) != null)) {
            appendAccessibility(s)
        }
        input.restarts?.let { appendRestarts(it) }
        val system = s.packages.filter { it.value.system }.toSortedMap(compareBy { it.value })
        val others = s.packages.size - system.size
        appendLine()
        when (packages) {
            Packages.LINES -> {
                appendLine("System apps (${system.size})")
                for ((pkg, st) in system) {
                    val ours = if (pkg in input.switchedOffByApp) ", switched off by this app" else ""
                    appendLine("${pkg.value} ${state(st.enabled)}$ours")
                }
                appendLine()
                appendLine("Other apps: $others (not named)")
            }
            Packages.GROUPED -> {
                appendLine("System apps (${system.size})")
                val off = { e: EnabledState -> e == EnabledState.DISABLED_USER || e == EnabledState.DISABLED }
                val groups = linkedMapOf<String, MutableList<String>>(
                    "Switched off by this app" to mutableListOf(),
                    "Off, disabled-user" to mutableListOf(),
                    "Off, disabled" to mutableListOf(),
                    "On, enabled" to mutableListOf(),
                    "On, default" to mutableListOf(),
                )
                for ((pkg, st) in system) {
                    val group = when {
                        pkg in input.switchedOffByApp && off(st.enabled) -> "Switched off by this app"
                        st.enabled == EnabledState.DISABLED_USER -> "Off, disabled-user"
                        st.enabled == EnabledState.DISABLED -> "Off, disabled"
                        st.enabled == EnabledState.ENABLED -> "On, enabled"
                        else -> "On, default"
                    }
                    groups.getValue(group).add(pkg.value)
                }
                for ((label, names) in groups) {
                    if (names.isEmpty()) continue
                    appendLine("$label (${names.size}):")
                    for (line in wrap(names, WRAP)) appendLine(line)
                    appendLine()
                }
                if (system.isEmpty()) appendLine()
                appendLine("Other apps: $others (not named)")
            }
            Packages.NONE -> {
                appendLine("Apps: ${system.size} system, $others other")
                appendSwitchedOff(input, names)
            }
        }
    }

    // beta3: " (from an earlier Setup: 0.3.7, brand set TCL)", or without the version and list when
    // the decision is older than beta3 and names neither.
    private fun earlierNote(d: HomeDecision?): String {
        val with = listOfNotNull(d?.app?.let { own(it) }, d?.profile?.let { v(it) })
        return " (from an earlier Setup${if (with.isEmpty()) "" else ": " + with.joinToString(", ")})"
    }

    // Issue #375 (and #253, #275): the problem report named nothing the app had switched off, so a
    // reply to "the Wi-Fi drops" or "a button stopped working" could not say what to put back. The
    // change records' list (still off by this app, RunLedger.switchedOffByUs), the TV's own apps by
    // name, any app the person installed only counted, as everywhere in the reports.
    // 0.4.6: `names` false (a report cut to fit the link) keeps the count on the same line, which
    // the triage reads, and leaves the names to the whole report on the phone.
    private fun StringBuilder.appendSwitchedOff(input: TvReportInput, names: Boolean = true) {
        val all = input.switchedOffByApp
        if (all.isEmpty()) return
        val s = input.snapshot
        val named = all.filter { nameable(s, it) }.map { it.value }.sorted()
        val theirs = all.size - named.size
        appendLine()
        if (!names) { appendLine("Switched off by this app (${all.size}): names left out to fit the link"); return }
        appendLine("Switched off by this app (${all.size}):")
        for (line in wrap(named, WRAP)) appendLine(line)
        if (theirs > 0) appendLine("and $theirs ${if (theirs == 1) "app" else "apps"} you installed (not named)")
    }

    // "a, b, c" in lines of at most `width` characters (a longer name gets a line of its own); every
    // line but the last ends with the comma that follows its last name.
    private fun wrap(names: List<String>, width: Int): List<String> {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        for ((i, n) in names.withIndex()) {
            val piece = if (i < names.lastIndex) "$n," else n
            if (line.isNotEmpty() && line.length + 1 + piece.length > width) { lines.add(line.toString()); line.clear() }
            if (line.isNotEmpty()) line.append(' ')
            line.append(piece)
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return lines
    }

    // A HOME activity is named only when it belongs to a system package the probe read as one, or to
    // the launcher this app installs. Any other launcher (Projectivy, Wolf and the like) is an app the
    // person chose, so it stays unnamed like every other app of theirs.
    const val NOT_NAMED = "an app you installed (not named)"

    private fun nameable(s: Snapshot, pkg: PackageName): Boolean = pkg == Launcher.pkg || s.packages[pkg]?.system == true

    private fun named(s: Snapshot, c: tv.cleanyour.core.model.Component): String = if (nameable(s, c.pkg)) c.flat else NOT_NAMED

    private fun state(e: EnabledState): String = when (e) {
        EnabledState.ENABLED -> "enabled"
        EnabledState.DEFAULT -> "default"
        EnabledState.DISABLED_USER -> "disabled-user"
        EnabledState.DISABLED -> "disabled"
    }

    private fun oneLine(s: String) = s.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }.joinToString("").trim()

    // A TV-provided value, on one line, masked (LogCodes.clean) and never empty.
    private fun v(s: String): String = LogCodes.clean(s, 120).ifEmpty { "unknown" }

    // The app's own words about a build ("0.4.6.1 (full)"): `v` took a four-part version for an
    // address, so its version is kept as it is and the rest masked like a TV's value (OwnVersion).
    private fun own(s: String): String = OwnVersion.field(s) { v(it) }.ifEmpty { "unknown" }

    // The home screen the TV came with, for the tv_support form's "platform" field, from the TV's own
    // packages and facts (never from a profile: the TVs that need a report have none). A guess, so
    // the form lets the person change it.
    fun platformOf(s: Snapshot): String {
        val f = s.facts
        val has = { p: String -> s.packages.keys.any { it.value == p } }
        val claimants = s.home.claimants.map { it.component.pkg.value }
        return when {
            f.manufacturer.equals("Amazon", true) || f.brand.equals("Amazon", true) || f.model.startsWith("AFT", true) ||
                has("com.amazon.tv.launcher") || has("com.amazon.firehomestarter") -> "Fire TV"
            has("com.google.android.apps.tv.launcherx") -> "Google TV"
            has("com.google.android.tvlauncher") || has("com.google.android.leanbacklauncher") -> "Android TV (Leanback)"
            claimants.any { it != Launcher.pkg.value && !it.startsWith("com.google.") && !it.startsWith("com.android.") && "setupwraith" !in it } -> "The maker's own launcher"
            else -> "Not sure"
        }
    }

    // "Hisense 55A6H": the brand, then the model unless it already starts with the brand.
    fun tvName(s: Snapshot): String {
        val brand = v(s.facts.brand)
        val model = v(s.facts.model)
        return if (model.startsWith(brand, ignoreCase = true)) model else "$brand $model"
    }

    // 0.4.6 (REPORTING-PROPOSAL 2.4): the TV's key for a problem issue's title, "Xiaomi MiTV-AXSO0
    // (dangal, Android 9)": the name, then the device codename and the Android release in brackets.
    // The triage keys a TV by its title when an issue has no report (scripts/triage facts.py
    // model_key), and drops what is in brackets, so the key stays "mitvaxso0" as for the report's
    // own Model line and for titles before 0.4.6.
    fun tvKey(s: Snapshot): String {
        val device = LogCodes.clean(s.facts.device, 60)
        return "${tvName(s)} (${listOfNotNull(device.ifEmpty { null }, "Android ${v(s.facts.release)}").joinToString(", ")})"
    }

    // "Android 11, RTM6.230109.155": the release and the build id from the fingerprint
    // (brand/product/device:release/id/incremental:type/tags).
    fun androidLine(s: Snapshot): String {
        val id = s.facts.fingerprint.split(':').getOrNull(1)?.split('/')?.getOrNull(1)?.let { LogCodes.clean(it, 40) }
        return listOfNotNull("Android ${v(s.facts.release)}", id?.takeIf { it.isNotEmpty() }).joinToString(", ")
    }
}

// The new-issue links the preview screen opens (B1, B2). GitHub issue forms take a field's id as a
// query parameter and prefill that field. 0.3.3 D1 (issues #8, #12, #13, #14, #16: the paste step was
// missed or went into the wrong box): the report itself goes into the form's report box through the
// link when the whole link stays at or under MAX_URL characters; otherwise the box stays empty (the
// form's placeholder asks for a paste, and the box is required) and the app copies the report, as
// the preview screen says before it opens GitHub. The short fields are cut to MAX_VALUE each; the
// report is all or nothing, never cut.
object IssueLinks {
    const val REPO = "https://github.com/udhaykumarbala/cleanyourtv-app"
    // 0.4.7: the TV list page, which TVs get the new home screen now, in the test build, soon, the
    // clean-up only (and why) or not at all.
    const val TV_LIST = "https://udhaykumarbala.github.io/cleanyourtv-app/tvs/"
    const val MAX_VALUE = 200
    // 0.4.6: GitHub answers HTTP 500 once the link and the request headers together pass about
    // 6,875 bytes (measured 2026-10-27: a bare request with a 6,900 character link works and 6,920
    // fails; with 1.5 KB of cookies, 5,400 works and 5,600 fails), and a phone signed in to GitHub
    // sends 1.5 to 2.5 KB of cookies. Signed out, a link over about 6,000 characters loses its
    // return_to at the sign-in page, so the form never opens. 4,000 leaves room for both. Before
    // 0.4.6 the cap was 7,000, which failed for most signed-in phones.
    const val MAX_URL = 4000

    // The form fields that hold the report (the ids in .github/ISSUE_TEMPLATE).
    const val TV_REPORT_FIELD = "report"
    const val PROBLEM_REPORT_FIELD = "diagnostics"

    // `reportInLink`: the report rides in the link, so the form opens with it in the report box.
    data class Link(val url: String, val reportInLink: Boolean)

    // B1 with the compact TV report (TvReport.compact).
    fun tvSupport(input: TvReportInput, report: String? = TvReport.compact(input)): Link = link("tv_support.yml", listOf(
        "title" to "TV support: ${TvReport.tvName(input.snapshot)}",
        "model" to TvReport.tvName(input.snapshot),
        "android" to TvReport.androidLine(input.snapshot),
        "platform" to TvReport.platformOf(input.snapshot),
    ), TV_REPORT_FIELD, report)

    // B2 with the problem report (TvReport.problem).
    // 0.4.6: the title carries the TV's key (TvReport.tvKey), or for a connect that failed before
    // the TV was read "Problem: could not connect (refused)" with the named error. From a card, the
    // form's "What happened" box says what the card said, marked as the app's words, so the person
    // can post it as it is.
    fun bugReport(
        about: AppAbout, input: TvReportInput?, report: String? = null, context: ReportContext? = null, redact: (String) -> String = { it },
    ): Link = link("bug_report.yml", listOfNotNull(
        "title" to problemTitle(input, context, redact),
        "version" to about.appLine,
        "phone" to about.phoneLine,
        input?.let { "tv" to "${TvReport.tvName(it.snapshot)}, ${TvReport.androidLine(it.snapshot)} (${TvReport.platformOf(it.snapshot)})" },
        context?.let { "what" to what(it, redact) },
    ), PROBLEM_REPORT_FIELD, report)

    // 0.4.8: "Tell us it worked", the confirmation form (.github/ISSUE_TEMPLATE/confirmation.yml) with its title and the
    // block (TvReport.confirmation) in its one box. The block is short (about 700 characters), well inside MAX_URL.
    const val CONFIRMATION_FIELD = "confirmation"

    fun confirmation(input: TvReportInput, block: String): Link = link("confirmation.yml", listOf(
        "title" to confirmationTitle(input),
    ), CONFIRMATION_FIELD, block)

    // "Confirmation: Xiaomi MiTV-AXSO0 (dangal, Android 9)": the same TV key as a problem's title (TvReport.tvKey).
    fun confirmationTitle(input: TvReportInput): String = "${ItWorked.TITLE_PREFIX}${TvReport.tvKey(input.snapshot)}"

    fun problemTitle(input: TvReportInput?, context: ReportContext? = null, redact: (String) -> String = { it }): String = when {
        context?.connect == true -> "Problem: could not connect (${TvReport.said(context.error ?: "unknown", redact, 60)})"
        input != null -> "Problem: ${TvReport.tvKey(input.snapshot)}"
        else -> "Problem report"
    }

    // "(Filled in by the app) Setup: A step didn't work (STEP_FAILED). paused STEP_FAILED at step 72".
    const val FILLED_IN = "(Filled in by the app)"

    // Cut at a word with "..." to stay within MAX_VALUE, so link() never cuts it mid word.
    fun what(c: ReportContext, redact: (String) -> String = { it }): String =
        ("$FILLED_IN ${TvReport.said(c.screen, redact, 60)}: ${TvReport.said(c.card, redact, 120)} (${TvReport.said(c.case, redact, 60)})" +
            (c.step ?: c.error)?.let { ". ${TvReport.said(it, redact, 160)}" }.orEmpty()).let { w ->
            if (w.length <= MAX_VALUE) w else w.take(MAX_VALUE - 3).substringBeforeLast(' ') + "..."
        }

    private fun link(template: String, params: List<Pair<String, String>>, reportField: String, report: String?): Link {
        val query = (listOf("template" to template) + params).joinToString("&") { (k, v) -> "$k=${enc(v.take(MAX_VALUE))}" }
        val base = "$REPO/issues/new?$query"
        if (report.isNullOrBlank()) return Link(base, false)
        val full = "$base&$reportField=${enc(report.trimEnd())}"
        return if (full.length <= MAX_URL) Link(full, true) else Link(base, false)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
