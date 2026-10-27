package tv.cleanyour.core.flows

import java.net.URLDecoder
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
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.testing.fixtures.Samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 0.4.6 (REPORTING-PROPOSAL 2.1, 2.2, 2.4) with real sizes: three problem reports that went out of
// the link and were pasted by hand, as 0.3.7 and 0.4.0 made them (issue #1136 WALTON, 26,369
// characters; #1130 Xiaomi MIBOX3 with 7 apps switched off, pasted into the wrong box; #1006 a phone
// that could not connect, no TV read). Each fixture is rebuilt into the input and the log the app
// had, problem() gives it back line for line, and the report that rides in the link is cut to fit.
class ProblemReportsTest {
    private val issues = listOf(1136, 1130, 1006)

    private fun fixture(n: Int): String =
        checkNotNull(javaClass.getResourceAsStream("/reports/problem-$n.txt")) { "problem-$n.txt" }.readBytes().decodeToString()

    private data class Parsed(val about: AppAbout, val input: TvReportInput?, val log: String)

    @Test fun eachFixtureIsRebuiltExactly() {
        for (n in issues) {
            val p = parse(fixture(n))
            assertEquals(fixture(n), TvReport.problem(p.about, p.input, p.log), "issue #$n")
        }
    }

    private val stepCtx = ReportContext(
        screen = "Setup", card = "We stopped at a step", case = "STEP_FAILED",
        step = "paused STEP_FAILED at step 12: step failed: Disable com.google.android.tvrecommendations: nonzero_exit(1) Error: java.lang.SecurityException",
    )
    private val connectCtx = ReportContext(
        screen = "Connect", card = "The TV answered, but the phone couldn't connect", case = "CONNECT_FAILED", connect = true,
        error = "protocol", found = "android_tv_remote+google_cast+adb_mdns, port 5555 open", port = 5555, detail = "SSLException ECONNRESET",
    )

    // The whole point: every real long report rides in the link, under the cap, with the TV, its
    // matching and home screen lines, what happened on the card and the newest log lines.
    @Test fun everyRealLongReportRidesInTheLink() {
        for (n in issues) {
            val p = parse(fixture(n))
            val ctx = if (p.input == null) connectCtx else stepCtx
            val f = ProblemReports.fit(p.about, p.input, p.log, ctx)
            val q = query(f.link.url)
            println("issue #$n: report ${fixture(n).length} characters, link ${f.link.url.length}, log ${f.logKept} of ${f.logLines} lines, names ${f.namesKept}")
            assertTrue(f.link.reportInLink, "issue #$n: ${f.link.url.length}")
            assertTrue(f.link.url.length <= IssueLinks.MAX_URL, "issue #$n: ${f.link.url.length}")
            assertEquals(f.sent.trimEnd(), q[IssueLinks.PROBLEM_REPORT_FIELD], "issue #$n")
            // beta2 QA: with its 200 connect retries counted (collapseRepeats), #1006 fits whole.
            if (n == 1006) { assertFalse(f.shortened, "issue #$n"); assertEquals(f.full, f.sent); continue }
            assertTrue(f.shortened, "issue #$n")
            // Never the identity: every line before the log is there, but the names of what this
            // app switched off may become their count.
            val head = fixture(n).substringBefore("Diagnostics log").lines().filter { it.isNotBlank() }
            val names = fixture(n).lines().dropWhile { !it.startsWith("Switched off by this app") }.drop(1).takeWhile { it.isNotBlank() }.toSet()
            for (line in head) if (line !in names && !line.startsWith("Switched off by this app")) assertTrue(line in f.sent, "issue #$n: $line")
            assertTrue("What happened\nScreen: ${ctx.screen}\nCard: ${ctx.card} (${ctx.case})\n" in f.sent, f.sent)
            if (ctx.connect) assertTrue("Connection\nFound by: ${ctx.found}\nPort: 5555\nError: protocol (SSLException ECONNRESET)\n" in f.sent, f.sent)
            else assertTrue("Step: ${ctx.step}\n" in f.sent, f.sent)
            // The log's first line, a line that says how many older ones were left out, and the newest.
            // beta2 QA: repeated lines come once, with their count (ProblemReports.collapseRepeats).
            val logLines = ProblemReports.collapseRepeats(p.log).lines()
            val sentLog = f.sent.substringAfter("Diagnostics log (addresses, serial numbers, keys and codes removed)\n").trimEnd().lines()
            assertEquals(logLines.first(), sentLog.first(), "issue #$n")
            assertEquals("(${logLines.size - 1 - f.logKept} older lines left out to fit the link; the app keeps them)", sentLog[1], "issue #$n")
            assertEquals(logLines.takeLast(f.logKept), sentLog.drop(2), "issue #$n")
            assertTrue(f.logKept >= ProblemReports.MIN_LOG_LINES, "issue #$n: ${f.logKept}")
            // The whole report stays on the phone: the preview, the copy and Share.
            assertEquals(TvReport.problem(p.about, p.input, ReportMachine.tail(ProblemReports.collapseRepeats(p.log)), ctx), f.full)
            // The form's other fields, and the title with the TV's key.
            assertEquals("bug_report.yml", q["template"])
            assertTrue(q["what"]!!.startsWith(IssueLinks.FILLED_IN + " ${ctx.screen}: ${ctx.card} (${ctx.case})"), q["what"])
            if (p.input == null) assertEquals("Problem: could not connect (protocol)", q["title"])
        }
    }

    @Test fun theTitleCarriesTheTvKey() {
        val p = parse(fixture(1130))
        assertEquals("Problem: Xiaomi MIBOX3 (once, Android 9)", IssueLinks.problemTitle(p.input))
        assertEquals("Problem: WALTON Android TV (tamachi, Android 11)", IssueLinks.problemTitle(parse(fixture(1136)).input))
        assertEquals("Problem report", IssueLinks.problemTitle(null))
        assertEquals("Problem: could not connect (protocol)", IssueLinks.problemTitle(null, connectCtx))
        // A connect to a TV read before: still "could not connect", the report has the TV.
        assertEquals("Problem: could not connect (protocol)", IssueLinks.problemTitle(p.input, connectCtx))
        // The owner's example TV.
        val mi = TvReportInput(p.about, Samples.snapshot(), null, null)
        assertEquals("Problem: Xiaomi MiTV-AXSO0 (${Samples.miTvFacts().device}, Android ${Samples.miTvFacts().release})", IssueLinks.problemTitle(mi))
    }

    // scripts/triage facts.py keys a TV by the report's Model line, or by the title when an issue
    // has no report: "problem:" and a leading brand dropped, brackets dropped, then only letters and
    // digits. The new title gives the same key as the report and as the titles before 0.4.6.
    @Test fun theTriageKeyFromTheTitleIsTheReportsKey() {
        fun normal(s: String) = s.replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ").lowercase().replace(Regex("[^a-z0-9]+"), "")
        fun fromTitle(t: String) = normal(t.replace(Regex("(?i)^(tv support|problem)\\s*:\\s*"), "").replace(Regex("(?i)^(xiaomi|redmi|amazon|sony|philips|hisense|tcl)\\s+"), ""))
        for (n in listOf(1136, 1130)) {
            val input = assertNotNull(parse(fixture(n)).input)
            assertEquals(normal(input.snapshot.facts.model), fromTitle(IssueLinks.problemTitle(input)), "issue #$n")
            assertEquals(fromTitle("Problem: ${TvReport.tvName(input.snapshot)}"), fromTitle(IssueLinks.problemTitle(input)), "issue #$n")
        }
    }

    // The order of the cuts: the log down to MIN_LOG_LINES first, then the names of what this app
    // switched off become their count (the triage reads it from the same line), then fewer log lines.
    @Test fun theLogGoesFirstThenTheNamesNeverTheTv() {
        val p = parse(fixture(1130))
        val input = assertNotNull(p.input)
        val many = (1..300).map { PackageName("com.example.vendor.firmware.part$it") }
        val pkgs = input.snapshot.packages + many.associateWith { state(EnabledState.DISABLED_USER, true) }
        val big = input.copy(snapshot = input.snapshot.copy(packages = pkgs), switchedOffByApp = input.switchedOffByApp + many)
        val f = ProblemReports.fit(p.about, big, p.log, stepCtx)
        assertTrue(f.link.reportInLink && f.link.url.length <= IssueLinks.MAX_URL, "${f.link.url.length}")
        assertFalse(f.namesKept)
        assertTrue("Switched off by this app (307): names left out to fit the link\n" in f.sent, f.sent)
        assertTrue(Regex("^Switched off by this app \\((\\d+)\\)", RegexOption.MULTILINE).find(f.sent)?.groupValues?.get(1) == "307")
        assertTrue(many.none { it.value in f.sent })
        assertTrue("Model: MIBOX3\nDevice: once\n" in f.sent && "Home switch: planned" in f.sent)
        // The full report keeps every name.
        assertTrue(many.all { it.value in f.full })
        // A short list keeps its names while the log alone can make room.
        val small = ProblemReports.fit(p.about, input, p.log, stepCtx)
        assertTrue(small.namesKept && "com.netflix.ninja" in small.sent, small.sent)
    }

    // However long the log or its lines, the link holds a report, under the cap.
    @Test fun anyLogFits() {
        val p = parse(fixture(1136))
        val about = p.about
        for (log in listOf(
            "Clean Your TV 0.4.6, Android 36\n" + (1..5_000).joinToString("\n") { "17930256${"%05d".format(it)} I setup: line $it " + "x y ".repeat(60) },
            "Clean Your TV 0.4.6, Android 36\n" + "z".repeat(30_000),
            "", "only a head",
        )) {
            for (input in listOf(p.input, null)) {
                val f = ProblemReports.fit(about, input, log, stepCtx)
                assertTrue(f.link.reportInLink, "${log.length}: ${f.link.url.length}")
                assertTrue(f.link.url.length <= IssueLinks.MAX_URL)
            }
        }
        // A report that fits is sent whole, with no line about older lines.
        val short = ProblemReports.fit(about, p.input, "Clean Your TV 0.4.6, Android 36\n1793025667496 I setup: connect start", stepCtx)
        assertFalse(short.shortened)
        assertFalse("left out" in short.sent)
    }

    // A card's text is redacted twice before it reaches the report, the box and the title: an address
    // (whole or as the app's redactor leaves it), a code, a host name, the TV's own name.
    @Test fun whatACardSaysIsRedacted() {
        val p = parse(fixture(1130))
        val title = ReportContext.named("Arun's Living Room TV at 192.168.1.20 halted, code 482913, x.x.x.20, tv.lan", "Arun's Living Room TV")
        val ctx = ReportContext("Setup", title, "STEP_FAILED", connect = true, error = "refused 10.0.0.7", found = "address typed", port = 5555, detail = "fe80::1:2:3 ECONNREFUSED")
        val marker = { s: String -> s.replace("halted", "[redacted by the app]") }
        val f = ProblemReports.fit(p.about, p.input, p.log, ctx, marker)
        val url = URLDecoder.decode(f.link.url, "UTF-8")
        for (out in listOf(f.full, f.sent, url)) {
            for (secret in listOf("Arun", "192.168.1.20", "482913", "x.x.x.20", "tv.lan", "10.0.0.7", "fe80::1", "halted")) assertFalse(secret in out, "$secret in\n$out")
        }
        assertTrue("Card: the TV at [address] [redacted by the app], code [code], [address], [host] (STEP_FAILED)" in f.sent, f.sent)
        assertTrue("Problem: could not connect (refused [address])" == query(f.link.url)["title"], query(f.link.url)["title"])
    }

    // A nickname comes out as a whole word only: a short one ("Mi") leaves "Mirror" and "MiTV" alone,
    // and a nickname that is just "TV" changes nothing.
    @Test fun aNicknameIsTakenOutAsAWholeWord() {
        assertEquals("the TV stopped answering near the Mirror and MiTV", ReportContext.named("Mi stopped answering near the Mirror and MiTV", "Mi"))
        assertEquals("The TV stopped answering", ReportContext.named("The TV stopped answering", "TV"))
        assertEquals("the TV and the TV", ReportContext.named("Arun's TV and Arun", "Arun's TV", "Arun"))
        assertEquals("We were in the middle of setting up the TV.", ReportContext.named("We were in the middle of setting up Living Room (2).", "Living Room (2)"))
        assertEquals("Unchanged", ReportContext.named("Unchanged", null, "", "x"))
    }

    // The form's What happened box is cut at a word with "..." when the step is long, within MAX_VALUE.
    @Test fun theWhatHappenedBoxIsCutAtAWord() {
        val long = stepCtx.copy(step = (1..60).joinToString(" ") { "word$it" })
        val w = IssueLinks.what(long)
        assertTrue(w.length <= IssueLinks.MAX_VALUE, "${w.length}")
        assertTrue(w.endsWith("..."), w)
        assertTrue(Regex("word\\d+\\.\\.\\.$").containsMatchIn(w), w)
        assertEquals(w, query(ProblemReports.fit(parse(fixture(1130)).about, null, "", long).link.url)["what"])
        // A short one is whole.
        assertFalse(IssueLinks.what(stepCtx.copy(step = "short")).endsWith("..."))
    }

    // The route a card sends, back to the TV and the context, whatever the title holds.
    @Test fun theRouteCarriesTheContext() {
        val ctx = stepCtx.copy(card = "A/B: \"it\" stopped?\nnext & more=1%")
        val id = TvId("a1b2c3d4e5f60718")
        val route = IssueRoute.of(id, ctx)
        assertTrue(route.startsWith("issue/${id.value}/"))
        assertEquals(2, route.count { it == '/' })
        assertEquals(id to ctx.copy(card = "A/B: \"it\" stopped? next & more=1%"), IssueRoute.parse(route))
        assertEquals(null to connectCtx, IssueRoute.parse(IssueRoute.of(null, connectCtx)))
        assertNull(IssueRoute.parse("report/problem"))
        assertNull(IssueRoute.parse("issue/x"))
    }

    private fun state(e: EnabledState, system: Boolean) = PackageState(e, system, 0, null, false, false, null, false)

    // The 0.3.7 and 0.4.0 problem report back into its input and log. The report counts the apps and
    // names only what this app switched off and the HOME claimants, so the rest get made-up names.
    private fun parse(text: String): Parsed {
        val lines = text.lines()
        fun v(key: String) = lines.first { it.startsWith("$key: ") }.removePrefix("$key: ")
        val (version, flavour) = v("App").let { it.substringBefore(" (") to it.substringAfter("(").removeSuffix(")") }
        val phone = v("Phone")
        val about = AppAbout(version, flavour, phone.substringBeforeLast(", "), phone.substringAfterLast(", "))
        val log = text.substringAfter("Diagnostics log (addresses, serial numbers, keys and codes removed)\n").trimEnd()
        if ("TV: none read yet" in lines) return Parsed(about, null, log)
        val android = v("Android")
        val facts = DeviceFacts(
            brand = v("Brand"), manufacturer = v("Manufacturer"), model = v("Model"), device = v("Device"), serialLast4 = "0000",
            sdk = android.substringAfter("SDK ").removeSuffix(")").toInt(), release = android.substringBefore(" ("),
            fingerprint = v("Build"), abis = listOf("armeabi-v7a"), ramMb = 2048, hasGms = v("Google Play services") == "yes",
            adbSecure = true, adbFeatures = emptySet(), installRefusal = false, locale = "en-GB",
        )
        val read = v("Read from the TV")
        val day = LocalDate.parse(read.substringBefore(",")).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val reason = if (read.endsWith("before any change")) SnapshotReason.BASELINE else SnapshotReason.MANUAL
        fun component(flat: String) = Component(PackageName(flat.substringBefore('/')), flat.substringAfter('/'))
        val claimants = lines.filter { it.startsWith("Claimant: ") }.map {
            val c = it.removePrefix("Claimant: ")
            HomeClaimant(component(c.substringBefore(" priority ")), c.substringAfter(" priority ").toInt())
        }
        val resolved = v("Resolves to").takeIf { '/' in it }?.let(::component)
        val off = lines.dropWhile { !it.startsWith("Switched off by this app (") }.drop(1).takeWhile { it.isNotBlank() }
            .flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }.map(::PackageName).toSet()
        val (systemCount, others) = v("Apps").let { a -> a.substringBefore(" system").toInt() to a.substringAfter("system, ").substringBefore(" other").toInt() }
        val named = (claimants.map { it.component.pkg } + listOfNotNull(resolved?.pkg) + off).toSet()
        val system = named.associateWith { state(if (it in off) EnabledState.DISABLED_USER else EnabledState.ENABLED, true) } +
            (1..(systemCount - named.size)).associate { PackageName("com.example.system.part$it") to state(EnabledState.DEFAULT, true) }
        val packages = system + (1..others).associate { PackageName("person.app$it") to state(EnabledState.ENABLED, false) }
        val snapshot = Snapshot(
            TvId("0000000000000000"), day, reason, facts, packages, HomeResolution(resolved, claimants),
            emptyMap(), null, emptyList(), null, emptyList(), Samples.zeroHash,
        )
        val profile = v("Profile").let { p ->
            when {
                p == "none" -> ProfileResolution.None(emptyList())
                p.startsWith("brand set ") -> ProfileResolution.BrandSureSet(p.removePrefix("brand set "), emptyList(), emptyList())
                p.startsWith("exact ") -> ProfileResolution.Exact(miProfile.copy(id = p.removePrefix("exact ")))
                p.startsWith("candidate ") -> ProfileResolution.Exact(miProfile.copy(id = p.removePrefix("candidate "),
                    tested = tv.cleanyour.core.model.TestedInfo("candidate", null, null, rebootTested = false, candidate = true)))
                else -> error("profile $p")
            }
        }
        val home = v("Home switch").let { h ->
            when {
                h == "planned" -> HomeDecision(true)
                h.startsWith("blocked ") -> HomeDecision(false, h.removePrefix("blocked "))
                h == "not recorded" -> null
                else -> error("home $h")
            }
        }
        return Parsed(about, TvReportInput(about, snapshot, profile, home, off), log)
    }

    // Issue #1119 (0.4.6-beta2): the newest problem report, 66 lines as posted, its log cut short by
    // the person's paste. Its tail is 22 "socket connect failed" lines of a door retrying. Fitted
    // into the link, "Switched off by this app (4):" is followed by the four names, or says the names
    // were left out, never nothing; and the retries are one line each with their count.
    @Test fun issue1119TheSwitchedOffLineAlwaysSaysWhatFollowsAndRetriesAreCounted() {
        val text = ReportText.fixture("problem-1119.txt")
        val r = ReportText.parse(text)
        val about = AppAbout("0.4.6-beta2", "full", "Xiaomi 25053PC47I", "Android 17")
        val input = TvReportInput(about, r.snapshot, ProfileResolution.BrandSureSet("Xiaomi", emptyList(), emptyList()), HomeDecision(false, "WIZARD_STAYS_ON"), r.switchedOff)
        val p = Parsed(about, input, text.substringAfter("Diagnostics log (addresses, serial numbers, keys and codes removed)\n").trimEnd())
        assertEquals(4, input.switchedOffByApp.size)
        val collapsed = ProblemReports.collapseRepeats(p.log)
        assertTrue("session: socket connect failed: SocketTimeoutException (x3, last at 1793114501712)" in collapsed, collapsed)
        assertEquals(p.log.lines().size - 9, collapsed.lines().size, collapsed)
        // Lines that do not repeat, and the first line, stay as they are.
        assertTrue(collapsed.startsWith("Clean Your TV 0.4.6-beta2, Android 37\n1793028050368 I setup: connect start (new TV, manual)\n"), collapsed)
        // However long the log, the line after the header is the names or the note.
        for (extra in listOf(0, 40, 200)) {
            val log = p.log + (1..extra).joinToString("") { "\n17931146${10000 + it} I setup: probe done in ${it} ms, 117 packages, profile brand set Xiaomi" }
            val f = ProblemReports.fit(p.about, input, log, stepCtx)
            assertTrue(f.link.url.length <= IssueLinks.MAX_URL)
            val after = f.sent.lines().dropWhile { !it.startsWith("Switched off by this app (4)") }
            assertTrue(after.isNotEmpty(), f.sent)
            assertTrue(after[0] == "Switched off by this app (4): names left out to fit the link" ||
                (after[0] == "Switched off by this app (4):" && after[1] == "co.sensara.tv.mitv, com.android.camera2, com.android.gallery3d, com.xiaomi.mitv.tvmanager"),
                "$extra: ${after.take(2)}")
        }
    }

    // beta2 QA: the door's retries in turn ("connect start", "connect failed lost") are one pair.
    @Test fun aRetryingPairIsCountedOnce() {
        val pairs = (0 until 12).flatMap { i -> listOf("${1000 + 4 * i} I setup: connect start (manual)", "${1002 + 4 * i} I setup: connect failed lost") }
        val log = (listOf("Clean Your TV 0.4.6, Android 14", "999 I setup: probe start") + pairs + "1100 I setup: door closed").joinToString("\n")
        assertEquals(listOf("Clean Your TV 0.4.6, Android 14", "999 I setup: probe start", "1000 I setup: connect start (manual)",
            "1002 I setup: connect failed lost (with the line above, x12, last at 1046)", "1100 I setup: door closed").joinToString("\n"),
            ProblemReports.collapseRepeats(log))
        // Two of a kind are left as they are.
        val two = "h\n1 I a\n2 I a\n3 I b"
        assertEquals(two, ProblemReports.collapseRepeats(two))
    }

    private fun query(url: String): Map<String, String> = url.substringAfter('?').split('&').associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }
}
