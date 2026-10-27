package tv.cleanyour.core.planner

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.CurtainGroup
import tv.cleanyour.core.model.CurtainStage
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.InstallFlag
import tv.cleanyour.core.model.LauncherArtifact
import tv.cleanyour.core.model.Op
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.PackageState
import tv.cleanyour.core.model.Prior
import tv.cleanyour.core.model.Profile
import tv.cleanyour.core.model.Quirk
import tv.cleanyour.core.model.RemoteDir
import tv.cleanyour.core.model.RemotePath
import tv.cleanyour.core.model.SettingKey
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.Tier
import kotlinx.coroutines.runBlocking
import tv.cleanyour.core.ops.ConfigTransport
import tv.cleanyour.core.ops.Denylist
import tv.cleanyour.core.ops.FailurePolicy
import tv.cleanyour.core.ops.HomePlaceholder
import tv.cleanyour.core.ops.OpRenderer
import tv.cleanyour.core.ops.RenderRefused
import tv.cleanyour.core.ops.Rendered
import tv.cleanyour.core.ops.UndoOrder
import tv.cleanyour.core.ops.Watchdog
import tv.cleanyour.core.spec.AppNames
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.ProfileCategory
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.spec.resolveFor
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.IdGen

// The planner (PRODUCT-SPEC 5.8): pure, one plan per request, every op rendered through the one
// renderer so the plan screen, the hash and the executor see the same lines. Protected packages
// never reach a step (Denylist, applied here and again by the executor with live facts).
//
// Idempotence: a request whose effect the snapshot already shows yields a plan with no steps.
// Refusals carry plain reasons for the UI. The HOME takeover (section 3.4) is one GuardedSection
// with its rollback (step R) rendered into the plan so the engine can run it from the data alone.
//
// `profiles` (R1 R-3) resolves this TV's profile for the Manage requests (SwitchOff, Leftovers,
// PutBack), so the profile's never_disable list and the wizard quirk hold at plan time and the plan
// screen never offers what the executor's live check would then refuse. Setup takes its resolution
// from the request. The parameter is last and defaulted, so the R0: 15 constructor still compiles.
//
// `watchdogSeconds` (R1 R-1, DECISIONS-W3 D-W3-1): 150, the phone's longest confirm window (120 s
// without a thumbnail, D23) plus 30 s for the section's steps between arming and the check screen,
// so the TV-side layer never fires while the phone still waits for an answer.
//
// The TV curtain (RFC-CURTAIN-1): `curtainActivity` and `curtainReceiver` name the bundled curtain
// app; `curtainSigner` is its pinned signer (null: unknown, then only a package whose signer the
// snapshot reports as different is taken for someone else's). All defaulted, after `profiles`.
//
// `keepUpdatesOn` (STORES-PLAN 1, the Google Play build): every software updater stays on, found by
// its name (ProfileCategory.isUpdater, D22) or by the profile's "updates" group. Setup shows it as
// protected ("We keep these", with REASON_UPDATES), SwitchOff refuses it, and Setup and Leftovers
// skip one an earlier full build had switched off. Off (the default), updates stay a choice (D22).
class DefaultPlanner(
    private val clock: Clock,
    private val idGen: IdGen,
    private val renderer: OpRenderer,
    private val configTransport: ConfigTransport,
    private val appVersion: String,
    private val launcher: Component = Component(SwitchOffRule.OUR_LAUNCHER, ".MainActivity"),
    private val confirmWindowMs: Long = 90_000L,
    private val watchdogSeconds: Int = WATCHDOG_SECONDS,
    private val settleSeconds: Int = 15,
    private val profiles: ProfileSource? = null,
    private val curtainActivity: Component = Component(PackageName(CURTAIN_PACKAGE), ".CurtainActivity"),
    private val curtainReceiver: Component = Component(PackageName(CURTAIN_PACKAGE), ".CueReceiver"),
    private val curtainSigner: Sha256? = null,
    private val keepUpdatesOn: Boolean = false,
) : Planner {

    override fun plan(request: PlanRequest, snapshot: Snapshot, facts: FactsTable, journals: List<ChangeRecord>): PlanResult {
        val id = idGen.planId()   // first, so a plan's id does not depend on how many run tokens it drew
        val d = Draft(snapshot, facts, journals)
        val kind = when (request) {
            is PlanRequest.Setup -> { d.setup(request); PlanKind.SETUP }
            is PlanRequest.PutBack -> { d.putBack(request.pkg); PlanKind.PUT_BACK }
            is PlanRequest.SwitchOff -> { d.switchOff(request.pkgs); PlanKind.SWITCH_OFF }
            is PlanRequest.Leftovers -> { d.leftovers(request.expectedDisabled); PlanKind.LEFTOVERS }
            is PlanRequest.ApplyTheme -> { d.applyTheme(request.config, request.wallpaper); PlanKind.THEME }
            is PlanRequest.AddModule -> { d.addModule(request); PlanKind.ADD_MODULE }
            is PlanRequest.RemoveModule -> { d.removeModule(request.pkg); PlanKind.REMOVE_MODULE }
            is PlanRequest.UseStockHome -> { d.useStockHome(request.stock, null); PlanKind.USE_STOCK_HOME }
            is PlanRequest.UndoEverything -> { d.undoEverything(request.changes, request.removeLauncher); PlanKind.UNDO_EVERYTHING }
            PlanRequest.BeforeFactoryReset -> { d.beforeFactoryReset(); PlanKind.BEFORE_FACTORY_RESET }
            PlanRequest.DebugOff -> { d.debugOff(); PlanKind.DEBUG_OFF }
            is PlanRequest.WriteRestorePoint -> { d.writeRestorePoint(request.restorePointJson, request.restoreTxt); PlanKind.WRITE_RESTORE_POINT }
            PlanRequest.Reboot -> { d.reboot(); PlanKind.REBOOT }
            is PlanRequest.UpdateLauncher -> { d.updateLauncher(request.launcher); PlanKind.UPDATE_LAUNCHER }
        }
        if (d.reasons.isNotEmpty()) return PlanResult.Refused(d.reasons.toList())
        val steps = d.steps.toList()
        val plan = Plan(
            id = id, tvId = snapshot.tvId, kind = kind, baseSnapshot = snapshot.contentHash,
            steps = steps, sections = d.sections.toList(), summary = d.summary(),
            hash = renderer.planHash(steps.map { it.rendered }), createdAtMs = clock.nowMs(), appVersion = appVersion,
        )
        return PlanResult.Ok(plan)
    }

    private inner class Draft(val snapshot: Snapshot, val facts: FactsTable, val journals: List<ChangeRecord>) {
        val steps = mutableListOf<Step>()
        val sections = mutableListOf<GuardedSection>()
        val reasons = mutableListOf<String>()
        var groups = mutableListOf<GroupSummary>()
        var keepNames = listOf<String>()
        var dockNames = listOf<String>()
        var homeScreenPossible = false
        var replacesLauncher = false

        fun summary() = PlanSummary(groups.toList(), keepNames, dockNames, homeScreenPossible, replacesLauncher)

        // This TV's resolution for the Manage requests (R1 R-3), with the snapshot's Home (issue #114).
        // The bundled source answers without suspending, so the blocking call returns at once; the
        // planner stays a plain function.
        val resolution: ProfileResolution? by lazy { profiles?.let { src -> runBlocking { src.resolveFor(snapshot) } } }
        val managedProfile: Profile? get() = (resolution as? ProfileResolution.Exact)?.profile
        val managedStockLaunchers: List<Component> get() = when (val r = resolution) {
            is ProfileResolution.Exact -> r.profile.stockLaunchers
            is ProfileResolution.BrandSureSet -> r.stockLaunchers
            is ProfileResolution.None -> r.stockLaunchers
            null -> emptyList()
        }

        fun name(pkg: PackageName): String = AppNames.of(pkg, facts, snapshot.packages[pkg]?.label)
        // Issue #111: an owner's-choice row says what switching it off costs.
        fun description(pkg: PackageName): String {
            val what = facts.lookup(pkg)?.description ?: ""
            val cost = ProfileCategory.OWNERS_CHOICE[pkg] ?: return what
            return if (what.isEmpty()) cost else "$what. $cost"
        }
        // An uninstall's line on the progress screen. Our launcher is "the new home screen" there as
        // everywhere else in the app: the TV reads no label for it, so AppNames would make its name
        // from the package ("Gathin Home", QA 0.4.5-beta1).
        fun removing(pkg: PackageName): String = if (pkg == launcher.pkg) NARRATION_REMOVE_LAUNCHER else "Removing ${name(pkg)} from the TV"
        fun state(pkg: PackageName): PackageState? = snapshot.packages[pkg]
        fun isOff(pkg: PackageName): Boolean = state(pkg)?.enabled.let { it == EnabledState.DISABLED_USER || it == EnabledState.DISABLED }
        fun installed(pkg: PackageName): Boolean = pkg in snapshot.packages

        // The one place a step is made: render, or record the refusal as a reason.
        fun step(op: Op, prior: Prior?, group: String?, narration: String, policy: FailurePolicy): Int {
            val rendered: Rendered = try {
                renderer.render(op)
            } catch (e: RenderRefused) {
                reasons += "cannot render ${op::class.simpleName}: ${e.reason}"
                return -1
            }
            steps += Step(steps.size, op, rendered, prior, group, narration, policy)
            return steps.size - 1
        }

        fun refuse(reason: String) { reasons += reason }

        // Firmware that takes no other home screen, from the snapshot's facts and packages (FireOs,
        // TclChina). The China-market TCL rule stands in for a missing profile only: an exact TCL
        // Android TV or Google TV profile keeps its own rules, as SetupMachine.homeBlock does.
        fun refusesOtherHomes(profile: Profile?): Boolean = FireOs.isFireOs(snapshot.facts, snapshot) || TclChina.applies(snapshot.facts, profile)
        fun planned(op: Op): Boolean = steps.any { it.op == op }
        fun putBackPlanned(pkg: PackageName): Boolean = planned(Op.Enable(pkg)) || planned(Op.DefaultState(pkg))

        // WP4 (C6, G4): a package the static denylist protects now (an earlier build switched it off,
        // like TCL G10's remote mic service) only ever takes the write towards on. The renderer
        // refuses a DefaultState for it, so one that is off goes back on with Enable, and one that is
        // on needs nothing; an inverse that would switch it off is never planned. Null: no step.
        fun protectedInverse(pkg: PackageName, inverse: Op): Op? {
            if (Denylist.staticReason(pkg.value) == null) return inverse
            return when (inverse) {
                is Op.DefaultState -> if (isOff(pkg)) Op.Enable(pkg) else null
                is Op.Disable -> null
                else -> inverse
            }
        }

        // WP4 (G4): a journal from before 0.4.5 may hold the chooser or another placeholder as the
        // SetHome prior on a TV whose HOME was undecided. The home screen to go back to is then the
        // stock home the switch armed its watchdog with, else the TV's stock home claimant, else a
        // stock launcher of the exact profile (never a brand set's or the generic KnownLaunchers) that
        // is installed and either claims HOME or has a Disable record of ours: a launcher the factory
        // switched off is never handed HOME.
        fun homeToRestore(recorded: Component?, records: List<ChangeRecord>): Component? {
            if (recorded == null || !StockHome.isPlaceholder(recorded)) return recorded
            val all = journals + records
            fun claims(pkg: PackageName) = snapshot.home.claimants.any { it.component.pkg == pkg }
            fun ourDisable(pkg: PackageName) = all.any { (it.op as? Op.Disable)?.pkg == pkg }
            return HomePlaceholder.stockFromRecords(records) ?: StockHome.of(snapshot, managedStockLaunchers)
                ?: managedProfile?.stockLaunchers.orEmpty().firstOrNull { installed(it.pkg) && (claims(it.pkg) || ourDisable(it.pkg)) }
        }

        // The rule the engine applies again with live facts (SwitchOffRule, 0.3.3 issue #10), outside
        // the home screen switch, plus this build's own choice: keepUpdatesOn keeps the updaters.
        fun protectedReason(pkg: PackageName, profile: Profile?, stockLaunchers: List<Component>, groups: List<PackageGroup> = profile?.groups.orEmpty()): String? {
            SwitchOffRule.reason(pkg, state(pkg), profile, snapshot, stockLaunchers)?.let { return it }
            if (keptUpdater(pkg, groups)) return REASON_UPDATES
            return null
        }

        // keepUpdatesOn: a software updater by its name (D22) or by its profile group.
        fun keptUpdater(pkg: PackageName, groups: List<PackageGroup>): Boolean = keepUpdatesOn && ProfileCategory.isUpdater(pkg, groups)

        fun disable(pkg: PackageName, group: String?, policy: FailurePolicy = FailurePolicy.SKIP_IF_REFUSED, narration: String = "Switching off ${name(pkg)}") =
            step(Op.Disable(pkg), Prior(packageState = state(pkg)?.enabled), group, narration, policy)

        fun enable(pkg: PackageName, group: String?, narration: String = "Switching ${name(pkg)} back on", policy: FailurePolicy = FailurePolicy.STOP_AND_ROLLBACK) =
            step(Op.Enable(pkg), Prior(packageState = state(pkg)?.enabled), group, narration, policy)

        // ---- Setup -------------------------------------------------------------------------------

        fun setup(req: PlanRequest.Setup) {
            val profile = (req.resolution as? ProfileResolution.Exact)?.profile
            val packageGroups: List<PackageGroup> = when (val r = req.resolution) {
                is ProfileResolution.Exact -> r.profile.groups
                is ProfileResolution.BrandSureSet -> r.sureSet
                is ProfileResolution.None -> emptyList()
            }
            val stockLaunchers: List<Component> = when (val r = req.resolution) {
                is ProfileResolution.Exact -> r.profile.stockLaunchers
                is ProfileResolution.BrandSureSet -> r.stockLaunchers
                is ProfileResolution.None -> r.stockLaunchers
            }
            val choices = req.choices
            val covered = mutableSetOf<PackageName>()
            var disabled = 0

            for (group in packageGroups) {
                val rows = mutableListOf<RowSummary>()
                for (entry in group.packages) {
                    val pkg = entry.name
                    if (!installed(pkg) || pkg in covered) continue
                    covered += pkg
                    val why = protectedReason(pkg, profile, stockLaunchers, packageGroups)
                    val wanted = when (group.default) {
                        GroupDefault.DISABLE -> pkg !in choices.kept
                        GroupDefault.ASK, GroupDefault.KEEP -> pkg in choices.extraOff && pkg !in choices.kept
                    }
                    // Already asleep: nothing to switch off and nothing to keep, so the row would
                    // only make S3 count what the plan never does (emulator run, round 3). The same
                    // for an updater keepUpdatesOn protects that is off already (an earlier full
                    // build switched it off): "We keep these" would be untrue, and Manage's Switched
                    // off still lists it with Put back (RF-STORES-030 S1).
                    if ((why == null || why == REASON_UPDATES) && isOff(pkg)) continue
                    val off = why == null && wanted
                    rows += RowSummary(pkg, name(pkg), description(pkg), off, why != null, why)
                    if (off) { disable(pkg, group.id); disabled++ }
                }
                groups += GroupSummary(group.id, group.title, rows.count { it.off }, rows.size, rows)
            }
            // Custom: packages switched off outside the groups.
            val custom = mutableListOf<RowSummary>()
            for (pkg in choices.extraOff.sortedBy { it.value }) {
                if (pkg in covered || !installed(pkg) || pkg in choices.kept) continue
                if (keptUpdater(pkg, packageGroups)) continue   // keepUpdatesOn: a choice an earlier full build kept
                val why = protectedReason(pkg, profile, stockLaunchers, packageGroups)
                if (why != null) { refuse("${name(pkg)} cannot be switched off: $why"); continue }
                if (isOff(pkg)) continue
                custom += RowSummary(pkg, name(pkg), description(pkg), off = true, protected = false, whyProtected = null)
                disable(pkg, GROUP_CUSTOM); disabled++
            }
            if (custom.isNotEmpty()) groups += GroupSummary(GROUP_CUSTOM, "Also switched off", custom.size, custom.size, custom)
            keepNames = choices.kept.filter { installed(it) }.map { name(it) }.sorted()
            dockNames = choices.dock.orEmpty().map { name(it) }

            // The settle sits between the debloat and the launcher steps, so the TV has caught up with
            // the switched-off apps before anything is installed or HOME changes.
            val launcherNeeded = choices.installLauncher && !pinInstalled(req.launcher)
            val needsHome = choices.takeover && snapshot.home.resolved != launcher
            if (disabled > 0 && (launcherNeeded || needsHome)) {
                step(Op.Settle(settleSeconds), null, GROUP_LAUNCHER, "Giving the TV $settleSeconds seconds to settle", FailurePolicy.CONTINUE)
            }
            val refusedBefore = reasons.size
            val launcherSteps = if (launcherNeeded) launcherInstall(req.launcher, choices.replaceLauncherSigner, stockLaunchers, profile) else false
            if (reasons.size > refusedBefore) return   // the launcher reason says it all; no second reason about the switch
            // RFC-DOCK-1: the dock (with its rows) and the look picked in the wizard, as one config.json,
            // only when the new home screen is (or will be) on the TV and the switch is chosen. After the
            // install, so the launcher's first start finds itself installed; before the guarded section,
            // so the check screen's "with ... along the bottom" is what the TV shows, and a failed push
            // happens while the TV's own home screen is still in charge and never touches HOME. The
            // pushes CONTINUE on failure: a STOP here would pause the setup with an offer to undo the
            // whole run for a cosmetic step that Manage can send again at any time.
            val config = req.config
            if (choices.takeover && config != null && (launcherSteps || installed(launcher.pkg))) {
                val overlayGone = !installed(launcher.pkg) || replacesLauncher   // a fresh install, or the uninstall took its files
                look(config, req.wallpaper, overlayGone, FailurePolicy.CONTINUE)
            }
            if (choices.takeover) takeover(req.launcher, profile, stockLaunchers, launcherSteps, choices.wizardWifi)
            homeSwitchRows()
            val curtain = req.curtain
            // RF-PROFILES-63 P1: the curtain is an app like the launcher (minSdk TV_MIN_SDK); below it there is none.
            if (curtain != null && choices.curtain && !snapshot.facts.installRefusal && snapshot.facts.sdk >= TV_MIN_SDK && reasons.isEmpty()) weaveCurtain(curtain)
        }

        // ---- The TV curtain (RFC-CURTAIN-1) ---------------------------------------------------------

        val curtainPkg: PackageName get() = curtainActivity.pkg

        // The curtain on the TV is ours only when we installed it (SECURITY 3.2 and threat C6, the
        // rule removeModule uses: installed by us, or an install of it in this TV's change records)
        // and the snapshot names no other signer for it (RF-CURTAIN-1 F2). Anything else under our
        // name is never started, cued or removed.
        fun curtainIsOurs(signer: Sha256?): Boolean {
            val s = state(curtainPkg) ?: return false
            if (s.signer != null && signer != null && s.signer != signer) return false
            return s.installedByUs || journals.any { (it.op as? Op.Install)?.pkg == curtainPkg || (it.op as? Op.InstallStream)?.pkg == curtainPkg }
        }

        // A curtain left on the TV by an interrupted run: removed first by Leftovers (and by Setup's
        // own last step). The TV app finishes itself; this takes the package away.
        fun curtainLeftover() {
            if (!curtainIsOurs(curtainSigner)) return
            step(Op.Uninstall(curtainPkg), Prior(present = true), GROUP_CURTAIN, "Removing the progress screen from the TV", FailurePolicy.CONTINUE)
        }

        fun curtainGroup(groupId: String?): CurtainGroup = when (groupId?.removeSuffix("-optional")) {
            "ads" -> CurtainGroup.ADVERTS
            "tracking" -> CurtainGroup.TRACKING
            "preloads" -> CurtainGroup.NOBODY_ASKED
            "brand" -> CurtainGroup.BRAND_EXTRAS
            "demo" -> CurtainGroup.SHOP_DEMO
            else -> CurtainGroup.OTHER
        }

        // Weaves the curtain into the finished Setup plan, so every count is known when the first cue
        // is rendered. The curtain goes in first (after the engine's PRE_PLAN restore point, which
        // always runs before step 0, so the restore point never contains it): its install, its start
        // and the SCAN and RESTORE_SAVED cues. The SCAN on the TV is a presentation of the read that
        // has just happened, not a live one. Then one OFF cue after each switch-off outside the home
        // screen switch (and GROUP_DONE after a group's last), INSTALL and INSTALLED around the
        // launcher's install, FINALE and a short settle right before the first start of the launcher
        // (or at the end when nothing starts it), and the curtain's removal as the last step. Every
        // curtain step CONTINUEs: the curtain can never stop, pause or roll back the setup. Indices of
        // the body's steps and of its guarded section shift; nothing else about them changes.
        fun weaveCurtain(artifact: LauncherArtifact) {
            if (artifact.pkg != curtainPkg) return   // not the curtain: no curtain steps, the setup is unchanged
            if (steps.isEmpty()) return               // nothing to do on the TV, nothing to show
            val current = state(curtainPkg)
            if (current != null && !curtainIsOurs(artifact.signer)) return   // not ours (another signer, or no install of ours): never touched
            val body = steps.toList()
            val bodySections = sections.toList()
            steps.clear(); sections.clear()
            val token = idGen.runToken()
            fun inSection(i: Int) = bodySections.any { i in it.from..it.to }
            val counted = body.filter { it.op is Op.Disable && it.group != GROUP_HOME && !inSection(it.index) }
            val countedIdx = counted.map { it.index }.toSet()
            val offTotal = counted.size
            val groupTotal = counted.groupingBy { it.group }.eachCount()
            val lastOfGroup = counted.groupBy { it.group }.mapValues { (_, v) -> v.last().index }.values.toSet()
            val installAt = body.firstOrNull { it.group == GROUP_LAUNCHER && it.op !is Op.Settle }?.index
            val installedAt = body.firstOrNull { packageOf(it.op) == launcher.pkg && (it.op is Op.Install || it.op is Op.InstallStream) }?.index
            val finaleAt = body.firstOrNull { it.op == Op.StartActivity(launcher, wait = true) }?.index

            fun n(x: Int) = x.coerceIn(0, tv.cleanyour.core.ops.ShellRenderer.CUE_MAX)
            fun cue(stage: CurtainStage, count: Int = 0, total: Int = 0, group: CurtainGroup = CurtainGroup.NONE, groupCount: Int = 0, groupTotal: Int = 0) =
                step(Op.CurtainCue(curtainReceiver, token, stage, n(count), n(total), group, n(groupCount), n(groupTotal)), null, GROUP_CURTAIN, "", FailurePolicy.CONTINUE)
            fun finale() {
                cue(CurtainStage.FINALE)
                step(Op.Settle(CURTAIN_SETTLE_SECONDS), null, GROUP_CURTAIN, NARRATION_CURTAIN_SETTLE, FailurePolicy.CONTINUE)
            }

            val pinned = current != null && current.versionCode == artifact.versionCode
            if (!pinned) {
                installApk(artifact.blob, artifact.sha256, artifact.pkg, artifact.versionCode, artifact.signer,
                    present = current != null, group = GROUP_CURTAIN, narration = NARRATION_CURTAIN, quiet = true)
            }
            step(Op.StartActivity(curtainActivity, wait = false), null, GROUP_CURTAIN, "", FailurePolicy.CONTINUE)
            cue(CurtainStage.SCAN, 0, offTotal)
            cue(CurtainStage.RESTORE_SAVED)

            val moved = IntArray(body.size)
            var k = 0
            val inGroup = mutableMapOf<String?, Int>()
            var finaled = false
            for (s in body) {
                if (s.index == finaleAt) { finale(); finaled = true }
                if (s.index == installAt) cue(CurtainStage.INSTALL)
                moved[s.index] = steps.size
                steps += s.copy(index = steps.size)
                if (s.index == installedAt) cue(CurtainStage.INSTALLED)
                if (s.index in countedIdx) {
                    k++
                    val g = (inGroup[s.group] ?: 0) + 1
                    inGroup[s.group] = g
                    val words = curtainGroup(s.group)
                    cue(CurtainStage.OFF, k, offTotal, words, g, groupTotal.getValue(s.group))
                    if (s.index in lastOfGroup) cue(CurtainStage.GROUP_DONE, k, offTotal, words, g, g)
                }
            }
            if (!finaled) finale()
            sections += bodySections.map { it.copy(from = moved[it.from], to = moved[it.to]) }
            // The prior is what the run's staleness check compares with the TV before step 0: the
            // curtain is there then only when it was left by an earlier run (e2e S09 found this).
            step(Op.Uninstall(curtainPkg), if (current != null) Prior(present = true) else null, GROUP_CURTAIN, "", FailurePolicy.CONTINUE)
        }

        // The home screen switch switches off the TV's own home screen (and the wizard on the
        // quirk). Those are on the list too, in "Needed for the new home screen", so S3's numbers,
        // the list, "Show commands" and the plan name the same switch-offs (emulator run, round 3:
        // S3 said nothing was found while the plan switched the TV's home screen off). They are part
        // of the switch, not a choice: off and protected, so the list shows them without a pill to
        // flip, and a row elsewhere for the same package (a profile's "keep" home group, a debloat
        // group naming the stock launcher) moves here.
        fun homeSwitchRows() {
            val switched = steps.filter { it.group == GROUP_HOME && it.op is Op.Disable }
            if (switched.isEmpty()) return
            val pkgs = switched.map { (it.op as Op.Disable).pkg }.toSet()
            fun GroupSummary.with(rows: List<RowSummary>) = copy(off = rows.count { it.off }, total = rows.size, rows = rows)
            groups = groups.map { g -> g.with(g.rows.filter { it.pkg !in pkgs }) }.toMutableList()
            val rows = switched.map { s ->
                val pkg = (s.op as Op.Disable).pkg
                if (s.narration == NARRATION_WIZARD) RowSummary(pkg, NARRATION_WIZARD, DESCRIPTION_WIZARD, off = true, protected = true, whyProtected = REASON_SWITCH_PART)
                else RowSummary(pkg, name(pkg), DESCRIPTION_OLD_HOME, off = true, protected = true, whyProtected = REASON_SWITCH_PART)
            }
            val i = groups.indexOfFirst { it.id == GROUP_HOME }
            if (i >= 0) groups[i] = groups[i].let { it.with(rows + it.rows) }
            else groups += GroupSummary(GROUP_HOME, TITLE_HOME, rows.size, rows.size, rows)
        }

        // The pin, or a newer build of ours, is on the TV (launcher v2.4.0 plan: a higher versionCode
        // with our signer is never downgraded; one with another signer goes through the signer check).
        fun pinInstalled(artifact: LauncherArtifact): Boolean {
            val current = state(artifact.pkg) ?: return false
            return current.versionCode >= artifact.versionCode && current.signer == artifact.signer
        }

        // D39 ladder: streaming install when the TV advertises `cmd`, else push to tmp and pm install.
        // Returns true when install steps were added (false when the pin is already installed).
        fun launcherInstall(artifact: LauncherArtifact, replaceSigner: Boolean, stockLaunchers: List<Component>, profile: Profile?): Boolean {
            if (artifact.pkg != launcher.pkg) { refuse("the launcher artifact is ${artifact.pkg.value}, not ${launcher.pkg.value}"); return false }
            // Issue #2: Setup never asks for it on a TV that only allows its own home screen; this is
            // the defence in depth, from the TV's own facts so an unlisted Fire TV is caught too.
            if (refusesOtherHomes(profile)) { refuse(REASON_TV_REFUSES_HOME); return false }
            // RF-PROFILES-63 P1: Setup never asks for it below Android 8; this is the defence in depth.
            if (snapshot.facts.sdk < TV_MIN_SDK) { refuse(REASON_OLD_ANDROID); return false }
            val current = state(artifact.pkg)
            val present = current != null
            if (current != null && current.signer != null && current.signer != artifact.signer) {
                if (!replaceSigner) { refuse("the home screen app on the TV was installed with a different key; replacing it needs consent (D36)"); return false }
                val homeNow = snapshot.home.resolved
                if (homeNow == null || stockLaunchers.none { it.pkg == homeNow.pkg }) { refuse("the home screen app can only be replaced while the TV's own home screen is in charge (D36)"); return false }
                replacesLauncher = true
                step(Op.Uninstall(artifact.pkg), Prior(present = true), GROUP_LAUNCHER, NARRATION_REPLACE_LAUNCHER, FailurePolicy.STOP_AND_ROLLBACK)
            } else if (current != null && current.versionCode >= artifact.versionCode && current.signer == artifact.signer) {
                return false   // the pin (or a newer build of ours) is already installed
            }
            installApk(artifact.blob, artifact.sha256, artifact.pkg, artifact.versionCode, artifact.signer,
                present = present && !replacesLauncher, group = GROUP_LAUNCHER, narration = "Installing the new home screen")
            return true
        }

        // `quiet` (RFC-CURTAIN-1): the helper steps around the install carry a blank narration, so
        // only the install itself is a line on the Working screen.
        fun installApk(blob: BlobRef, sha256: Sha256, pkg: PackageName, versionCode: Long, signer: Sha256, present: Boolean, group: String, narration: String, quiet: Boolean = false) {
            val prior = Prior(present = present)
            fun helper(text: String) = if (quiet) "" else text
            if ("cmd" in snapshot.facts.adbFeatures) {
                step(Op.InstallStream(blob, sha256, pkg, versionCode, signer), prior, group, narration, FailurePolicy.CONTINUE)
            } else {
                val apk = RemotePath(RemoteDir.TMP, listOf(sha256.hex.take(APK_NAME_HEX) + ".apk"))   // a FileName is at most 64 characters
                step(Op.Mkdir(RemoteDir.TMP), null, group, helper("Preparing the TV"), FailurePolicy.CONTINUE)
                step(Op.PushAsset(blob, apk, sha256), Prior(present = false), group, helper("Copying the app to the TV"), FailurePolicy.CONTINUE)
                val flags = if (present) setOf(InstallFlag.REPLACE) else emptySet()
                step(Op.Install(apk, sha256, pkg, versionCode, signer, flags), prior, group, narration, FailurePolicy.CONTINUE)
                step(Op.RemoveFile(apk), null, group, helper("Tidying up"), FailurePolicy.CONTINUE)
            }
        }

        // Section 3.4: the guarded HOME takeover.
        fun takeover(artifact: LauncherArtifact, profile: Profile?, stockLaunchers: List<Component>, launcherInstalledByPlan: Boolean, wizardWifi: WizardWifi) {
            if (snapshot.home.resolved == launcher) { homeScreenPossible = true; return }   // already ours
            if (snapshot.facts.installRefusal) { refuse("this TV refuses app installs, so the home screen switch is not possible"); return }
            if (snapshot.facts.sdk < TV_MIN_SDK) { refuse(REASON_OLD_ANDROID); return }
            // Issue #2: Fire OS (and a China-market TCL) refuses another home screen, told from the
            // TV's facts, whatever the profile resolution says.
            if (refusesOtherHomes(profile)) { refuse(REASON_TV_REFUSES_HOME); return }
            // RF-SAFETY-250: the TVs where the switch is skipped up front (SetupMachine never asks for it).
            HomeSwitchRisk.of(snapshot.facts, profile, snapshot, wizardWifi)?.let { refuse(it.reason); return }
            val current = state(launcher.pkg)
            val installedAndVerified = current != null && current.signer == artifact.signer
            if (!launcherInstalledByPlan && !installedAndVerified) { refuse("the new home screen is not installed and the plan does not install it"); return }
            if (stockLaunchers.isEmpty()) { refuse("no known home screen for this TV model, so the switch is not possible"); return }
            // The component used for SetHome in step R is the claimant the TV itself reported
            // (StockHome, shared with SetupMachine's decision to offer the switch, issue #8).
            val claimants = snapshot.home.claimants.map { it.component }
            val stock = StockHome.of(snapshot, stockLaunchers)
            if (stock == null) { refuse("the TV's home screen is not one of the known ones for this model (issue #85)"); return }
            // Never a section around a Fire OS home: its switch-off and its step R Enable are both refused.
            if (FireOs.isHome(stock.pkg)) { refuse(REASON_TV_REFUSES_HOME); return }
            // What the engine allows inside the open switch (SwitchOffRule), for the same facts.
            fun mayGoOff(pkg: PackageName) = SwitchOffRule.reason(pkg, state(pkg), profile, snapshot, stockLaunchers, inHomeSwitch = true) == null
            // D3: never the wizard while it may own the saved Wi-Fi network (switching it off deletes
            // that network and takes the TV, and this connection, off Wi-Fi). Without it the switch may
            // not hold on this TV; the HOME_SETUPWRAITH stop then says why.
            val wizard = profile?.setupWraith?.takeIf {
                Denylist.isWizardAllowed(it, profile) && installed(it) && mayGoOff(it) && wizardWifi == WizardWifi.NOT_OWNER
            }
            // D25: the stock launcher is disabled unless the profile keeps it enabled (quirk or
            // never_disable) or the rule keeps it on for this TV's facts (persistent, an input).
            val keepStockEnabled = (profile != null && Quirk.STOCK_LAUNCHER_KEEP_ENABLED in profile.quirks) || !mayGoOff(stock.pkg)
            val token = idGen.runToken()

            // Launcher v2.4.0's precondition: the TV's own home screen is left alone unless the new one
            // really starts ("Status: ok" from am start -W, ShellRenderer.check). The step CONTINUEs;
            // the engine opens the section only when it is done (RF-LAUNCHER-240 F3), so a launcher
            // that does not start ends the run cleanly with the switch skipped.
            step(Op.StartActivity(launcher, wait = true), null, GROUP_HOME, "Starting the new home screen", FailurePolicy.CONTINUE)
            step(Op.Mkdir(RemoteDir.TMP), null, GROUP_HOME, "Preparing the safety timer", FailurePolicy.CONTINUE)
            val from = step(Op.ArmHomeWatchdog(stock, watchdogSeconds, token), null, GROUP_HOME, "Arming the safety timer", FailurePolicy.STOP_AND_ROLLBACK)
            if (!keepStockEnabled) disable(stock.pkg, GROUP_HOME, FailurePolicy.STOP_AND_ROLLBACK, "Switching off the TV's own home screen")
            if (wizard != null) disable(wizard, GROUP_HOME, FailurePolicy.STOP_AND_ROLLBACK, NARRATION_WIZARD)
            // WP4 (B5): the prior is the home screen the TV goes back to; never the chooser or a placeholder.
            step(Op.SetHome(launcher), Prior(home = StockHome.recorded(snapshot, stock)), GROUP_HOME, "Making the new home screen the default", FailurePolicy.STOP_AND_ROLLBACK)
            val to = step(Op.VerifyHome(launcher), null, GROUP_HOME, "Checking the home screen", FailurePolicy.STOP_AND_ROLLBACK)
            step(Op.ConfirmHome(token), null, GROUP_HOME, "Confirming the new home screen", FailurePolicy.CONTINUE)
            step(Op.KillWatchdog(token), null, GROUP_HOME, "Disarming the safety timer", FailurePolicy.CONTINUE)
            if (from < 0 || to < 0) return

            // Step R (section 3.4): defuse both layers first, then the TV's own home screen back.
            val rollback: List<Op> = listOfNotNull(
                Op.ConfirmHome(token), Op.KillWatchdog(token),
                if (keepStockEnabled) null else Op.Enable(stock.pkg), wizard?.let { Op.Enable(it) },
                Op.SetHome(stock), Op.VerifyHome(stock), Op.PressHome,
            )
            // Focus on any of these after the HOME key means the takeover did not hold: the TV's
            // own home screens, the setup wizard, the resolver (package android, admitted by
            // PackageName since RFC-W1-2, R1 R-8) and FallbackHome.
            val rollbackIfFocus: Set<PackageName> = (
                stockLaunchers.map { it.pkg } +
                    claimants.filter { it != launcher }.map { it.pkg } +
                    (Denylist.setupWizards + Denylist.vendorSetupWizards + Denylist.FRAMEWORK).map(::PackageName) +
                    PackageName("com.android.settings")
                ).toSet()
            sections += GuardedSection(SECTION_HOME, from, to, token, confirmWindowMs, launcher, rollbackIfFocus, rollback)
            homeScreenPossible = true
        }

        // ---- Manage ------------------------------------------------------------------------------

        fun putBack(pkg: PackageName) {
            if (!installed(pkg)) { refuse("${pkg.value} is not on this TV"); return }
            if (!isOff(pkg)) return   // already on
            // The wizard quirk (R1 R-3): on a TV whose wizard takes the Home button back (issue #49),
            // the wizard comes back on only with the TV's own home screen, never under the new one.
            val profile = managedProfile
            if (profile != null && Denylist.isWizardAllowed(pkg, profile) && snapshot.home.resolved == launcher) {
                refuse("${name(pkg)} cannot be switched back on while the new home screen is in charge: $REASON_WIZARD_WHILE_OURS"); return
            }
            val change = journals.filter { it.op is Op.Disable && (it.op as Op.Disable).pkg == pkg }.maxByOrNull { it.atMs }
            val inverse = change?.let { renderer.inverse(it.op, it.prior ?: Prior()) } ?: Op.Enable(pkg)
            val op = protectedInverse(pkg, inverse) ?: return
            step(op, Prior(packageState = state(pkg)?.enabled), GROUP_PUT_BACK, "Switching ${name(pkg)} back on", FailurePolicy.STOP_AND_ROLLBACK)
        }

        fun switchOff(pkgs: Set<PackageName>) {
            val rows = mutableListOf<RowSummary>()
            for (pkg in pkgs.sortedBy { it.value }) {
                if (!installed(pkg)) { refuse("${pkg.value} is not on this TV"); continue }
                val why = protectedReason(pkg, managedProfile, managedStockLaunchers)   // R1 R-3: never_disable and the wizard quirk
                if (why != null) { refuse("${name(pkg)} cannot be switched off: $why"); continue }
                rows += RowSummary(pkg, name(pkg), description(pkg), off = true, protected = false, whyProtected = null)
                if (!isOff(pkg)) disable(pkg, GROUP_SWITCH_OFF)
            }
            if (rows.isNotEmpty()) groups += GroupSummary(GROUP_SWITCH_OFF, "Switched off", rows.size, rows.size, rows)
        }

        fun leftovers(expectedDisabled: Set<PackageName>) {
            curtainLeftover()   // RFC-CURTAIN-1
            for (pkg in expectedDisabled.sortedBy { it.value }) {
                if (!installed(pkg) || isOff(pkg)) continue
                if (keptUpdater(pkg, managedProfile?.groups.orEmpty())) continue   // keepUpdatesOn: an updater that came back on stays on
                // After a TV update the stock launcher may be enabled and HOME again (R1): a leftover
                // that is now a home screen is never switched off outside the home screen switch.
                val why = protectedReason(pkg, managedProfile, managedStockLaunchers)   // R1 R-3: never_disable and the wizard quirk
                if (why != null) { refuse("${name(pkg)} cannot be switched off: $why"); continue }
                disable(pkg, GROUP_LEFTOVERS)
            }
        }

        fun applyTheme(config: BlobRef, wallpaper: BlobRef?) {
            if (!installed(launcher.pkg)) { refuse("the new home screen is not installed"); return }
            look(config, wallpaper, overlayGone = false, pushPolicy = FailurePolicy.STOP_AND_ROLLBACK)
        }

        // The transport's config.json steps (D15) with their priors and narration, shared by ApplyTheme
        // and Setup (RFC-DOCK-1). `overlayGone`: the launcher's folder holds nothing of ours before this
        // plan (Setup installs the launcher fresh, or replaces it and the uninstall takes its files), so
        // both pushes record that the files were absent and Undo everything removes them again.
        // Otherwise the config prior is the overlay the probe read and the wallpaper's is unknown.
        // `pushPolicy` is the pushes' failure policy; the other steps always CONTINUE.
        fun look(config: BlobRef, wallpaper: BlobRef?, overlayGone: Boolean, pushPolicy: FailurePolicy) {
            val ops = try {
                configTransport.writePlanSteps(config, wallpaper)
            } catch (e: IllegalArgumentException) {
                refuse("the look cannot be sent to the TV: ${e.message}"); return
            }
            val overlayPrior = if (overlayGone) Prior(present = false) else Prior(present = snapshot.launcherOverlay != null, overlay = snapshot.launcherOverlay)
            val otherFile = Prior(present = if (overlayGone) false else null)
            for (op in ops) {
                val (prior, narration, policy) = when (op) {
                    is Op.PushAsset -> Triple(if (op.src == config) overlayPrior else otherFile, "Sending the look to the TV", pushPolicy)
                    is Op.RestartHome -> Triple(null, "Restarting the home screen", FailurePolicy.CONTINUE)
                    is Op.RemoveFile -> Triple(otherFile, "Clearing the old wallpaper", FailurePolicy.CONTINUE)
                    else -> Triple(null, "Preparing the home screen", FailurePolicy.CONTINUE)
                }
                step(op, prior, GROUP_THEME, narration, policy)
            }
            if (ops.any { it is Op.RestartHome }) restoreAccessibility(GROUP_THEME)
        }

        // Launcher v2.4.0 plan (tools/theme.sh): the force-stop of RestartHome drops the launcher's
        // accessibility services (InputKeyService, HomeKeyService) from the enabled list and turns
        // accessibility off, so a remote button the launcher handled stops working. When the TV had
        // one of ours on, wait a second (Android drops them while the launcher restarts), clear the
        // list and switch accessibility off, wait a second, write the list back and switch accessibility
        // on, so they bind again. Typed, allowlisted settings steps (SECURITY 3.3) with the probed
        // values, shown in Show commands. The off and on again is v2.5.0's step (upstream #424): some
        // TVs (TCL Smart TV Pro G03, Android 11) keep the service in their crashed list after the
        // force-stop and leave it unbound until accessibility itself goes off and on.
        //
        // Only when the TV had accessibility on (RF-LAUNCHER-240 F7): switching it on would bind every
        // service in the list, someone else's too, and an accessibility service reads the screen, so
        // the app never switches one on; it only puts back exactly what the TV had.
        fun restoreAccessibility(group: String) {
            if (snapshot.settings[SettingKey.ACCESSIBILITY_ENABLED] != "1") return
            val list = snapshot.settings[SettingKey.ENABLED_ACCESSIBILITY] ?: return
            if (list.split(':').none { it.startsWith(launcher.pkg.value + "/") }) return
            if (!SettingKey.ENABLED_ACCESSIBILITY.valueShape.matches(list)) return   // a list the renderer would refuse is left as the TV has it
            val narration = "Switching the home screen's remote buttons back on"
            step(Op.Settle(1), null, group, narration, FailurePolicy.CONTINUE)
            step(Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, null), Prior(settingValue = list), group, narration, FailurePolicy.CONTINUE)
            step(Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "0"), Prior(settingValue = "1"), group, narration, FailurePolicy.CONTINUE)
            step(Op.Settle(1), null, group, narration, FailurePolicy.CONTINUE)
            step(Op.SettingsPut(SettingKey.ENABLED_ACCESSIBILITY, list), Prior(settingValue = list), group, narration, FailurePolicy.CONTINUE)
            step(Op.SettingsPut(SettingKey.ACCESSIBILITY_ENABLED, "1"), Prior(settingValue = "1"), group, narration, FailurePolicy.CONTINUE)
        }

        fun addModule(req: PlanRequest.AddModule) {
            val module = req.module; val version = req.version; val apk = req.apk
            if (module.tier != Tier.VERIFIED && module.tier != Tier.COMMUNITY) { refuse("${module.name} is ${module.tier.name.lowercase()} in the index"); return }
            if (apk.sha256 != version.artifact.sha256 || apk.size != version.artifact.size) { refuse("the downloaded file does not match the index entry"); return }
            if (version.minSdk > snapshot.facts.sdk) { refuse("${module.name} needs Android ${version.minSdk}+ and this TV has ${snapshot.facts.sdk}") ; return }
            if (version.abis.isNotEmpty() && version.abis.none { it in snapshot.facts.abis }) { refuse("${module.name} has no build for this TV's processor"); return }
            if (snapshot.facts.installRefusal) { refuse("this TV refuses app installs"); return }
            val current = state(module.pkg)
            if (current != null && current.signer != null && current.signer != version.artifact.signer) { refuse("${module.name} is already on the TV, installed with another key"); return }
            if (current != null && current.versionCode >= version.versionCode) return   // already there
            // postInstall is parsed and ignored in 0.1 (R0: no VERIFIED module exists yet).
            installApk(apk, version.artifact.sha256, module.pkg, version.versionCode, version.artifact.signer,
                present = current != null, group = GROUP_MODULE, narration = "Adding ${module.name} to the TV")
            // RFC-CARDS-1: the look with module_cards on, when the person's add is what turns cards on
            // for this TV. After the install, so the launcher's restart finds the new card and asks on
            // the TV; the same steps, priors and failure policy as ApplyTheme. Without the new home
            // screen on the TV there is nothing to read it, so the add goes without it.
            val config = req.config
            if (config != null && installed(launcher.pkg)) look(config, req.wallpaper, overlayGone = false, pushPolicy = FailurePolicy.STOP_AND_ROLLBACK)
            step(Op.PressHome, null, GROUP_MODULE, "Showing the home screen", FailurePolicy.CONTINUE)
        }

        fun removeModule(pkg: PackageName) {
            val current = state(pkg) ?: return   // already gone
            if (current.system) { refuse("${name(pkg)} is part of the TV's system and cannot be removed"); return }
            val ours = current.installedByUs || journals.any { (it.op as? Op.Install)?.pkg == pkg || (it.op as? Op.InstallStream)?.pkg == pkg }
            if (!ours) { refuse("${name(pkg)} was not installed by this app"); return }
            if (pkg == launcher.pkg && snapshot.home.resolved == launcher) { refuse("use the TV's own home screen first, then remove the new one"); return }
            step(Op.Uninstall(pkg), Prior(present = true), GROUP_MODULE, removing(pkg), FailurePolicy.STOP_AND_ROLLBACK)
        }

        // Also the core of UndoEverything and BeforeFactoryReset: the TV's own home screen back.
        // `records`: change records besides the journals that may name it (Undo everything's own).
        // WP4 (G4): the stock home the switch armed its watchdog with, and an installed stock
        // launcher of this TV's exact profile (not a brand set's, not the generic KnownLaunchers), are
        // home screens this TV had too (a placeholder prior is mapped to one of them by homeToRestore).
        fun useStockHome(stock: Component, wizardFromJournal: PackageName?, records: List<ChangeRecord> = emptyList()) {
            if (stock == launcher) { refuse("that is the new home screen, not the TV's own"); return }
            val all = journals + records
            val known = snapshot.home.claimants.any { it.component == stock } ||
                all.any { it.op is Op.SetHome && it.prior?.home == stock } ||
                all.any { (it.op as? Op.ArmHomeWatchdog)?.stock == stock } ||
                (stock in managedProfile?.stockLaunchers.orEmpty() && installed(stock.pkg))
            if (!known) { refuse("${stock.flat} is not a home screen this TV had"); return }
            // A package the undo already puts back (Enable, or DefaultState for an original DEFAULT)
            // is not enabled a second time: that would leave it ENABLED instead of DEFAULT (R1 R-10).
            if (isOff(stock.pkg) && !putBackPlanned(stock.pkg)) enable(stock.pkg, GROUP_HOME, "Switching the TV's own home screen back on")
            val wizard = wizardFromJournal ?: journals.firstOrNull { it.op is Op.Disable && (it.op as Op.Disable).pkg.value in Denylist.setupWizards }?.let { (it.op as Op.Disable).pkg }
            if (wizard != null && installed(wizard) && isOff(wizard) && !putBackPlanned(wizard)) enable(wizard, GROUP_HOME, "Switching the setup wizard back on")
            if (snapshot.home.resolved != stock) {
                step(Op.SetHome(stock), Prior(home = snapshot.home.resolved), GROUP_HOME, "Making the TV's own home screen the default", FailurePolicy.STOP_AND_ROLLBACK)
                step(Op.VerifyHome(stock), null, GROUP_HOME, "Checking the home screen", FailurePolicy.STOP_AND_ROLLBACK)
                step(Op.PressHome, null, GROUP_HOME, "Showing the home screen", FailurePolicy.CONTINUE)
            }
        }

        // Computed from the change records (every change this app made), never from "enable
        // everything disabled". UndoOrder (shared with the laptop restore scripts) picks the
        // earliest record of each item, which holds the state before this app touched it, and
        // orders them: package states, settings, the home screen, our apps, our files.
        fun undoEverything(changes: List<ChangeRecord>, removeLauncher: Boolean) {
            val order = UndoOrder.undoOrder(changes)
            val originalHome: Component? = homeToRestore(order.firstOrNull { it.op is Op.SetHome }?.prior?.home, changes + journals)
            val ourApps = order.filter { UndoOrder.target(it.op) is UndoOrder.Target.App && it.prior?.present == false }
                .mapNotNull { packageOf(it.op) }.toSet()
            var clearedLook = false
            for (c in order) {
                when (val t = UndoOrder.target(c.op)) {
                    is UndoOrder.Target.PackageEnabled -> {
                        val current = state(t.pkg) ?: continue
                        val prior = c.prior ?: continue
                        val original = prior.packageState ?: continue
                        if (current.enabled == original) continue
                        val inverse = renderer.inverse(c.op, prior)?.let { protectedInverse(t.pkg, it) } ?: continue
                        step(inverse, Prior(packageState = current.enabled), GROUP_UNDO, undoNarration(inverse, t.pkg), FailurePolicy.CONTINUE)
                    }
                    is UndoOrder.Target.Setting -> {
                        val original = c.prior?.settingValue
                        val current = snapshot.settings[t.key]
                        if (t.key !in snapshot.settings || current == original) continue
                        if (t.key == SettingKey.ADB_ENABLED || t.key == SettingKey.ADB_WIFI_ENABLED) continue   // never switched back on by the app
                        // WP4 (G4, RF-LAUNCHER-240 F7): nor is accessibility. The helper put back journals
                        // accessibility_enabled 1 and the service list as priors; after the person switched
                        // accessibility off or changed the list, writing them back would switch a service
                        // on again. So an undo writes neither "1" nor a non-empty list: every accessibility
                        // write is null, "0" or what the TV has right now (AccessibilityPutBackPropertyTest).
                        if (t.key == SettingKey.ACCESSIBILITY_ENABLED && original == "1") continue
                        if (t.key == SettingKey.ENABLED_ACCESSIBILITY && !original.isNullOrEmpty()) continue
                        step(Op.SettingsPut(t.key, original), Prior(settingValue = current), GROUP_UNDO, "Putting back the TV's ${t.key.key.replace('_', ' ')} setting", FailurePolicy.CONTINUE)
                    }
                    UndoOrder.Target.Home -> if (originalHome != null && originalHome != launcher) useStockHome(originalHome, null, changes)
                    is UndoOrder.Target.App -> {
                        val pkg = t.pkg
                        if (pkg !in ourApps || !installed(pkg)) continue
                        if (pkg == launcher.pkg && !removeLauncher) continue
                        if (pkg == launcher.pkg && snapshot.home.resolved == launcher && (originalHome == null || originalHome == launcher)) {
                            refuse("the new home screen is still in charge; the TV's own home screen must come back first"); continue
                        }
                        val removed = step(Op.Uninstall(pkg), Prior(present = true), GROUP_UNDO, removing(pkg), FailurePolicy.CONTINUE)
                        if (pkg == launcher.pkg && removed >= 0) tidyWatchdogFiles(changes + journals)
                    }
                    is UndoOrder.Target.File -> {
                        // Only files in the launcher's folder that did not exist before us; a file that
                        // existed cannot be rebuilt in 0.1, and nothing is left to tidy when the launcher goes.
                        // The earliest record says whether it existed (a push, or the Setup's wallpaper
                        // removal on a fresh launcher, RFC-DOCK-1); it is on the TV now only when our
                        // last record of it is a push.
                        val launcherStays = installed(launcher.pkg) && !(removeLauncher && launcher.pkg in ourApps)
                        if (!launcherStays || t.path.dir != RemoteDir.OVERLAY || c.prior?.present != false) continue
                        val last = changes.filter { UndoOrder.target(it.op) == t }.maxWithOrNull(UndoOrder.CHRONOLOGICAL)
                        if (last?.op !is Op.PushAsset) continue
                        step(Op.RemoveFile(t.path), null, GROUP_UNDO, "Clearing the look", FailurePolicy.CONTINUE)
                        clearedLook = true
                    }
                    null -> {}
                }
            }
            if (clearedLook) {
                step(Op.RestartHome(launcher), null, GROUP_UNDO, "Restarting the home screen", FailurePolicy.CONTINUE)
                restoreAccessibility(GROUP_UNDO)
            }
        }

        // QA 0.4.5-beta1: the home switch leaves two small files of ours in /data/local/tmp/cleanyourtv,
        // the token file that defused the watchdog and the watchdog's log (empty unless it fired).
        // When the undo removes the new home screen they have no use left, so they go with it: one
        // RemoveFile (`rm -f` of that one file, nothing broader) per file name the watchdog template
        // writes, for the run tokens of our ArmHomeWatchdog and ConfirmHome records, skipping a file
        // an earlier undo already removed. The folder itself stays (there is no op that removes one).
        fun tidyWatchdogFiles(records: List<ChangeRecord>) {
            val tokens = records.sortedWith(UndoOrder.CHRONOLOGICAL).mapNotNull { c ->
                when (val op = c.op) { is Op.ArmHomeWatchdog -> op.token; is Op.ConfirmHome -> op.token; else -> null }
            }.distinct()
            val gone = records.mapNotNull { (it.op as? Op.RemoveFile)?.path }.toSet()
            var first = true
            for (token in tokens) {
                for (file in listOf(Watchdog.tokenFileName(token), Watchdog.logFileName(token))) {
                    val path = RemotePath(RemoteDir.TMP, listOf(file))
                    if (path in gone) continue
                    if (step(Op.RemoveFile(path), null, GROUP_UNDO, if (first) "Tidying up" else "", FailurePolicy.CONTINUE) >= 0) first = false
                }
            }
        }

        fun beforeFactoryReset() {
            val originalHome = homeToRestore(journals.filter { it.op is Op.SetHome }.minByOrNull { it.atMs }?.prior?.home, journals)
            val wizard = journals.firstOrNull { it.op is Op.Disable && (it.op as Op.Disable).pkg.value in Denylist.setupWizards }?.let { (it.op as Op.Disable).pkg }
            if (originalHome != null && originalHome != launcher) {
                useStockHome(originalHome, wizard)
            } else if (wizard != null && installed(wizard) && isOff(wizard)) {
                enable(wizard, GROUP_HOME, "Switching the setup wizard back on")
            }
        }

        fun debugOff() {
            if (snapshot.facts.sdk >= 30) {
                step(Op.SettingsPut(SettingKey.ADB_WIFI_ENABLED, "0"), Prior(settingValue = snapshot.settings[SettingKey.ADB_WIFI_ENABLED]),
                    GROUP_SESSION_END, "Switching wireless debugging off", FailurePolicy.CONTINUE)
            }
            step(Op.DebugOff, Prior(settingValue = snapshot.settings[SettingKey.ADB_ENABLED]), GROUP_SESSION_END, "Switching debugging off", FailurePolicy.CONTINUE)
        }

        fun writeRestorePoint(json: BlobRef, txt: BlobRef) {
            step(Op.Mkdir(RemoteDir.RESTORE), null, GROUP_RESTORE, "Preparing the restore point folder", FailurePolicy.CONTINUE)
            step(Op.PushAsset(json, RemotePath(RemoteDir.RESTORE, listOf("restore-point.json")), json.sha256), Prior(present = null), GROUP_RESTORE, "Saving the restore point on the TV", FailurePolicy.CONTINUE)
            step(Op.PushAsset(txt, RemotePath(RemoteDir.RESTORE, listOf("restore.txt")), txt.sha256), Prior(present = null), GROUP_RESTORE, "Saving the restore list on the TV", FailurePolicy.CONTINUE)
        }

        // RFC-LAUNCHER-1 (D4): "Update the home screen". The pin installed over our older launcher in
        // place (same package and signer, newer versionCode): the launcher's files, the dock and the
        // look in config.json, its card answers and its Launcher settings all stay, since only an
        // uninstall removes them. Anything else is not an update and is refused: another key is
        // Setup's replace (D36), an unknown key is never guessed, a newer one is never downgraded.
        fun updateLauncher(artifact: LauncherArtifact) {
            if (artifact.pkg != launcher.pkg) { refuse("the launcher artifact is ${artifact.pkg.value}, not ${launcher.pkg.value}"); return }
            val current = state(artifact.pkg) ?: run { refuse("the new home screen is not on this TV"); return }
            if (current.signer == null) { refuse("the key of the home screen app on the TV cannot be read, so it is not updated"); return }
            if (current.signer != artifact.signer) { refuse("the home screen app on the TV was installed with a different key; set the TV up again to replace it (D36)"); return }
            if (current.versionCode >= artifact.versionCode) return   // already this version or newer: nothing to do
            if (snapshot.facts.installRefusal) { refuse("this TV refuses app installs"); return }
            installApk(artifact.blob, artifact.sha256, artifact.pkg, artifact.versionCode, artifact.signer,
                present = true, group = GROUP_LAUNCHER, narration = "Updating the home screen to version ${artifact.versionName}")
            // The update stops the old version; when it is the TV's home screen, restart the new one at
            // once (upstream #1101: a TCL G03 restarted it mid-update and kept the old picture on
            // screen, so a plain start is not enough). Then the remote button helpers: the update
            // stops the launcher and drops its accessibility services as a force-stop does, so they
            // are put back exactly as the TV had them, whether or not it is
            // Home. restoreAccessibility adds nothing unless the TV had one of ours on, and it never
            // switches one on (RF-LAUNCHER-240 F7).
            if (snapshot.home.resolved == launcher) {
                step(Op.RestartHome(launcher), null, GROUP_LAUNCHER, NARRATION_RESTART_UPDATED, FailurePolicy.CONTINUE)
            }
            restoreAccessibility(GROUP_LAUNCHER)
        }

        // RFC-W8-5: the D24 restart test as a plan, so the reboot is journaled like any other write.
        fun reboot() {
            step(Op.Reboot, null, GROUP_REBOOT, "Restarting the TV", FailurePolicy.CONTINUE)
        }

        private fun undoNarration(op: Op, pkg: PackageName) = when (op) {
            is Op.Enable, is Op.DefaultState -> "Switching ${name(pkg)} back on"
            else -> "Switching ${name(pkg)} back off"
        }
    }

    companion object {
        const val SECTION_HOME = "home_takeover"
        const val APK_NAME_HEX = 40
        const val GROUP_CUSTOM = "custom"
        const val GROUP_LAUNCHER = "launcher"
        const val GROUP_HOME = "home"
        const val GROUP_PUT_BACK = "put_back"
        const val GROUP_SWITCH_OFF = "switch_off"
        const val GROUP_LEFTOVERS = "leftovers"
        const val GROUP_THEME = "theme"
        const val GROUP_MODULE = "module"
        const val GROUP_UNDO = "undo"
        const val GROUP_SESSION_END = "session_end"
        const val GROUP_RESTORE = "restore"
        const val GROUP_REBOOT = "reboot"
        const val NARRATION_RESTART_UPDATED = "Restarting the updated home screen"
        // RFC-CURTAIN-1
        const val GROUP_CURTAIN = "curtain"
        const val CURTAIN_PACKAGE = "tv.cleanyour.curtain"
        const val CURTAIN_SETTLE_SECONDS = 3
        const val NARRATION_CURTAIN = "Showing the progress on the TV"
        const val NARRATION_CURTAIN_SETTLE = "Giving the TV a moment"
        const val WATCHDOG_SECONDS = 150
        // RF-PROFILES-63 P1: the launcher (upstream minSdk 26) and the TV curtain (Toolchain.TV_MIN_SDK)
        // need Android 8. Below it a Setup is the clean-up alone: no install, no look, no curtain, no switch.
        const val TV_MIN_SDK = 26
        const val REASON_OLD_ANDROID = "the new home screen needs Android 8 or newer, and this TV has an older Android"
        const val REASON_WIZARD_WHILE_OURS = "on this TV the first-run setup wizard takes the Home button back; use the TV's own home screen first"
        const val REASON_HOME_SCREEN = "it is the TV's own home screen; only the home screen switch changes it"
        // Issue #2: Fire OS and the China-market TCL firmware take no other home screen.
        const val REASON_TV_REFUSES_HOME = "this TV only allows its own home screen, so the new one is not installed and the switch is not possible"
        const val REASON_FIRE_OS_HOME = "it is Fire OS's own home screen, which Fire OS never lets anyone switch off"
        const val REASON_WIZARD_SECTION_ONLY = "the first-run setup wizard is only switched off as part of the home screen switch"
        const val NARRATION_WIZARD = "Switch off the first-run setup wizard so the new home screen can take over"
        // Launcher v2.4.0 plan, finding 4: the uninstall erases everything the other launcher kept.
        const val NARRATION_REMOVE_LAUNCHER = "Removing the new home screen from the TV"
        const val NARRATION_REPLACE_LAUNCHER = "Replace the home screen app (its look, order, app list, settings and card answers on the TV are reset)"
        // UX 2, the preview sheet's "Needed for the new home screen" group.
        const val TITLE_HOME = "Needed for the new home screen"
        const val DESCRIPTION_OLD_HOME = "The old home screen. Switched off only after the new one works."
        const val DESCRIPTION_WIZARD = "We switch it back on if you ever undo or reset the TV."
        const val REASON_SWITCH_PART = "part of the home screen switch; it comes back on if the switch is undone"
        // STORES-PLAN 1 (keepUpdatesOn): the Google Play build keeps the TV's updaters on.
        const val REASON_UPDATES = "it brings the TV's software and security updates, which this version of the app always keeps on"

        fun packageOf(op: Op): PackageName? = when (op) {
            is Op.Disable -> op.pkg
            is Op.Enable -> op.pkg
            is Op.DefaultState -> op.pkg
            is Op.Uninstall -> op.pkg
            is Op.Install -> op.pkg
            is Op.InstallStream -> op.pkg
            else -> null
        }
    }
}
