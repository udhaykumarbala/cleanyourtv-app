package tv.cleanyour.core.flows

import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.cleanyour.core.engine.RunStatus
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.EnabledState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.ops.HomePlaceholder
import tv.cleanyour.core.planner.FireOs
import tv.cleanyour.core.planner.Plan
import tv.cleanyour.core.planner.PlanRequest
import tv.cleanyour.core.planner.TclGoogleTv
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.Looks
import tv.cleanyour.core.spec.ProfileResolution
import tv.cleanyour.core.spec.resolveFor
import tv.cleanyour.core.spec.StyleId

// M2 Home screen: the dock (S3b sheet), the look (theme picker), "Use the TV's own home screen"
// with its preview, the drift banner (D15: the overlay digest re-read on every connect; "Restore
// mine" pushes ours again) and "Update the home screen" with its preview (D4, RFC-LAUNCHER-1).
data class AppChoice(val pkg: PackageName, val name: String)

// 0.3.3 D2 (issue #3): the new home screen is not on this TV (Setup did not install it, or it was
// removed since), so there is no dock or look to send: the planner refuses ApplyTheme then, and
// 0.3.2 showed that refusal as "We stopped". Instead the screen says why, from the wizard's stored
// decision (HomeDecisions), and what can change it.
// `reason`: the home switch block Setup recorded; null when none was recorded (a TV set up before
// 0.3.2) or the switch was planned and the launcher is gone since.
// `fireTv`: for TV_REFUSES, name Fire TV instead of the brand (issue #2).
// `unlisted`: no exact profile knows this TV, so even with the Wi-Fi saved another way its setup
// wizard has to stay on (HomeSwitchRisk) and WIZARD_WIFI needs a list first.
// `setupAgain`: running Setup again can add it (nothing blocks it, or the block can change).
// `helpAddTv`: a list for this TV is what is missing; "Help add this TV" makes its report.
// `earlier` (beta3): an earlier Setup decided it, with another version or list (HomeDecision.earlier),
// so the screen says that instead of a reason that may no longer hold, and offers Setup again.
data class NotOnTv(
    val reason: HomeBlock?,
    val brand: String,
    val fireTv: Boolean = false,
    val unlisted: Boolean = false,
    val setupAgain: Boolean = false,
    val helpAddTv: Boolean = false,
    val earlier: Boolean = false,
)

// 0.4.0: a picture of the Picture row: a gallery picture by id, with its file for the thumbnail
// (null when it has none on this phone). look2: `until`, the last day of a dated picture (Diwali,
// "Until 10 Nov"), and its background colour (spec 6.2).
data class PictureOption(val id: String, val name: String, val path: String?, val until: LocalDate? = null,
    val backgroundArgb: Int = 0xFF000000.toInt())

data class HomeScreenState(
    val tvName: String = "",
    val dock: List<AppChoice> = emptyList(),           // the dock, then Row 2 and Row 3 (DockLayers), as the TV shows them
    val apps: List<AppChoice> = emptyList(),           // installed apps for the dock sheet
    val themeId: String? = null,
    val themes: List<ThemeOption> = emptyList(),
    val drift: Boolean = false,
    val stock: Component? = null,                       // the profile's stock launcher, when there is one
    val stockPreview: Plan? = null,
    val busy: Busy? = null,
    val homeScreenOurs: Boolean = true,
    val columns: Int = DockLayers.DEFAULT_COLUMNS,     // apps per dock row on the TV: the saved apps in a row, else the look's own
    val updateTo: String? = null,                       // D4: the bundled launcher's version, when the TV has an older build of ours
    val updatePreview: Plan? = null,
    val tvSideSettings: Boolean = false,                // the TV's launcher has Launcher settings of its own (2.4, versionCode 5, and later)
    val notOnTv: NotOnTv? = null,                       // 0.3.3 D2: the new home screen is not on this TV; no dock or look rows
    val tclRemoteKeys: Boolean = false,                 // issue #275: a TCL Google TV, whose remote's gear, Netflix and YouTube buttons need its own home screen
    val endedLook: String? = null,                      // 0.4.0: the name of a dated look that has ended and is still on the TV
    val pictureId: String? = null,                      // 0.4.0: the saved picture choice (HomeChoice.picture)
    val pictures: List<PictureOption> = emptyList(),    // 0.4.0: the gallery of the Picture row
    val ownPicture: BlobRef? = null,                    // 0.4.0: the person's photo kept for this TV (OwnPicture), as a file
    val ownDim: Int = OwnPicture.DEFAULT_DIM,           // and its dim
    // 0.4.0: a photo just made in the open Look sheet (staged, OwnPicture.stage) and its dim. It
    // becomes the kept one only when "Put it on the TV" put it there; closing the sheet or a failed
    // push drops it, so ownPicture and ownDim stay what the TV has.
    val newPicture: BlobRef? = null,
    val newDim: Int = OwnPicture.DEFAULT_DIM,
    val look: LookDraft? = null,                        // 0.4.0: the Look sheet's choice, while it is open
    // 0.4.0: the apps of the saved dock the TV would show after `dock` with more apps in a row (the
    // saved list holds up to 24, DockLayers.keep). The dock sheet never shows them, so a new dock
    // from it keeps them after its own apps; the Look sheet's preview and lines count them.
    val waitingApps: List<AppChoice> = emptyList(),
    // look2: the earlier look's photo (the "Earlier photo" tile, EarlierLook), as a file.
    val earlierPicture: BlobRef? = null,
    // look2: what was last sent, for the Look row's mini preview, its style and its picture id
    // (the ui names it); null before anything was sent from this phone.
    val sent: LookPreview? = null,
    val sentStyle: StyleId? = null,
    val sentPicture: String? = null,
    // look2: the TV's home screen version (the snapshot's package versionCode), its memory and
    // whether it shows Inputs (snapshot HDMI sources, or the profile's live TV app installed).
    val launcherVersionCode: Long? = null,
    val ramMb: Int? = null,
    val hasInputs: Boolean = true,
    // look2: the last look send failed and the draft is kept; Home offers "Open Look" (OpenLook).
    val lookFailed: Boolean = false,
    // look2 W1: a look send runs with the sheet closed; the Look row reads "Sending..." while busy.
    val lookSending: Boolean = false,
) {
    val waiting: Int get() = waitingApps.size
}

sealed interface HomeScreenIntent {
    data class SetDock(val dock: List<PackageName>) : HomeScreenIntent
    data class SetTheme(val id: String, val picture: String? = null) : HomeScreenIntent
    // 0.4.0: the Look sheet, starting from the TV's look; look2: after a failed send, the kept draft.
    data object OpenLook : HomeScreenIntent
    data class Look(val edit: LookEdit) : HomeScreenIntent   // look2: every tap of the Look editor
    data class SelectLook(val id: String) : HomeScreenIntent   // 0.4.0, removed after look2 merges
    data class SelectPicture(val id: String?) : HomeScreenIntent   // 0.4.0, removed after look2 merges
    data class SelectColumns(val count: Int) : HomeScreenIntent   // 0.4.0, removed after look2 merges
    data object UseLook : HomeScreenIntent               // the sheet's choice to the TV
    // The photo the phone just made (OwnPicture: 16:9, 1920 x 1080, at most 280 KB) and its dim: staged
    // and chosen in the sheet. Nothing reaches the TV, and nothing is kept, until UseLook succeeds.
    class SetOwnPicture(val jpeg: ByteArray, val dim: Int) : HomeScreenIntent
    data object CloseLook : HomeScreenIntent
    data object RestoreMine : HomeScreenIntent
    data object PreviewStock : HomeScreenIntent
    data object UseStock : HomeScreenIntent
    data object CancelPreview : HomeScreenIntent
    data object PreviewUpdate : HomeScreenIntent       // D4
    data object UpdateHomeScreen : HomeScreenIntent    // D4
    data object RunSetupAgain : HomeScreenIntent       // 0.3.3 D2, NotOnTv.setupAgain
    data object HelpAddTv : HomeScreenIntent           // 0.3.3 D2, NotOnTv.helpAddTv
}

class HomeScreenMachine(
    private val d: ManageDeps,
    scope: CoroutineScope,
    private val tvId: TvId,
) : Machine<HomeScreenState, HomeScreenIntent, ManageEffect>(HomeScreenState(themes = offeredThemes(d)), scope) {

    private val writer = TvWriter(d)
    private val drafts = LookDrafts(d.themes, d.pictures, d.encoder, d.clock)
    private data class Loaded(val s: HomeScreenState, val saved: HomeChoice?, val earlier: EarlierLook.Saved?, val cards: Boolean, val keyNames: Boolean)
    private data class BusyNow(val busy: Busy?)
    private data class Preview(val plan: Plan?)
    private data class Drift(val drift: Boolean)
    private data class UpdatePreview(val plan: Plan?)
    private class OwnPictureStaged(val ref: BlobRef, val dim: Int, val jpeg: ByteArray)
    private data class LookResult(val sent: Boolean)
    private var staged: ByteArray? = null   // the bytes of state.newPicture, kept once the TV has them
    private var updated = false   // D4: this screen updated the launcher; the stored snapshot does not know yet
    // look2: what the Look sheet works from, as the last load read it: the saved choice (what was
    // last sent), the earlier look, this TV's cards choice and Show launcher names.
    private var saved: HomeChoice? = null
    private var earlier: EarlierLook.Saved? = null
    private var cards: Boolean = Cards.MANAGE_DEFAULT
    private var keyNames = false
    private var kept: LookDraft? = null      // the draft of a send in flight, or of one that failed (OpenLook reopens it)
    private var sending = false              // one send per UseLook

    init { scope.launch { load(); checkDrift() } }

    override fun reduce(state: HomeScreenState, event: Any): HomeScreenState = when (event) {
        is Loaded -> event.s.copy(busy = state.busy, stockPreview = state.stockPreview, drift = state.drift, updatePreview = state.updatePreview,
            newPicture = state.newPicture, newDim = state.newDim, lookFailed = state.lookFailed, lookSending = state.lookSending).let { s ->
            saved = event.saved; earlier = event.earlier; cards = event.cards; keyNames = event.keyNames
            s.copy(look = state.look?.let { drafts.refresh(it, lookContext(s)) })
        }
        HomeScreenIntent.OpenLook -> when {
            state.busy != null || state.notOnTv != null -> state
            state.lookFailed && kept != null -> state.copy(look = drafts.refresh(kept!!, lookContext(state)), lookFailed = false)
            else -> state.copy(look = drafts.open(saved, lookContext(state)))
        }
        is HomeScreenIntent.Look -> state.look?.let { l -> state.copy(look = drafts.edit(l, event.edit, lookContext(state))) } ?: state
        // 0.4.0, removed after look2 merges: a look of the 0.4.0 list is the draft it migrates to; a
        // picture and apps in a row are the look2 edits.
        is HomeScreenIntent.SelectLook -> state.look?.let { l -> state.copy(look = drafts.selectLegacy(l, event.id, lookContext(state))) } ?: state
        is HomeScreenIntent.SelectPicture -> state.look?.let { l ->
            state.copy(look = drafts.edit(l, LookEdit.SelectPicture(drafts.legacyPicture(l, event.id)), lookContext(state)))
        } ?: state
        is HomeScreenIntent.SelectColumns -> state.look?.let { l -> state.copy(look = drafts.edit(l, LookEdit.SelectColumns(event.count), lookContext(state))) } ?: state
        // Closing the sheet drops a photo made in it and its dim, and a draft kept from a failed send.
        HomeScreenIntent.CloseLook -> { kept = null; state.copy(look = null, newPicture = null, newDim = state.ownDim, lookFailed = false) }
        // "Put it on the TV" closes the sheet; the draft and a new photo stay until the result.
        HomeScreenIntent.UseLook -> state.copy(look = null, lookFailed = false,
            lookSending = state.lookSending || (state.look != null && state.busy == null && state.notOnTv == null))
        is LookResult -> if (event.sent) state.copy(newPicture = null, newDim = state.ownDim, lookFailed = false, lookSending = false)
            else state.copy(lookFailed = true, lookSending = false)
        // A photo made for a sheet that has closed since is dropped.
        is OwnPictureStaged -> state.look?.let { l ->
            state.copy(newPicture = event.ref, newDim = event.dim).let { s ->
                s.copy(look = if (l.picture == LookPicture.NEW_ID) drafts.refresh(l, lookContext(s)) else drafts.edit(l, LookEdit.SelectPicture(LookPicture.NEW_ID), lookContext(s)))
            }
        } ?: state
        is UpdatePreview -> state.copy(updatePreview = event.plan)
        is BusyNow -> state.copy(busy = event.busy)
        is Preview -> state.copy(stockPreview = event.plan)
        is Drift -> state.copy(drift = event.drift, look = state.look?.let { if (it.tvChanged == event.drift) it else drafts.refresh(it, lookContext(state.copy(drift = event.drift))) })
        HomeScreenIntent.CancelPreview -> state.copy(stockPreview = null, updatePreview = null)
        else -> state
    }

    // What a draft depends on (LookContext): the dock as the TV shows it with its waiting apps, the
    // TV's other apps, the photos, the earlier look, what was last sent and the TV.
    private fun lookContext(state: HomeScreenState): LookContext {
        val dock = state.dock + state.waitingApps
        val inDock = dock.map { it.pkg }.toSet()
        return LookContext(
            apps = dock, others = state.apps.filter { it.pkg !in inDock },
            mine = state.ownPicture?.let { LookPicture.Of(it, state.ownDim) }, new = state.newPicture?.let { LookPicture.Of(it, state.newDim) },
            earlier = earlier, sent = saved, tv = lookTv(state), cards = cards, keyNames = keyNames, tvChanged = state.drift,
            pushDock = saved?.dock,
        )
    }

    private fun lookTv(state: HomeScreenState) = LookTv(state.launcherVersionCode, state.ramMb, state.hasInputs)

    override fun handle(event: Any, before: HomeScreenState, after: HomeScreenState) {
        if (before.busy != null && event is HomeScreenIntent) return
        // 0.3.3 D2: nothing to send the dock or the look to.
        if (after.notOnTv != null && (event is HomeScreenIntent.SetDock || event is HomeScreenIntent.SetTheme || event == HomeScreenIntent.RestoreMine ||
            event == HomeScreenIntent.UseLook)) return
        when (event) {
            // 0.4.0: the sheet's list, then the saved apps it never showed (waiting for more apps in
            // a row), so a new dock never loses them. Apps in a row stays the saved one.
            // look2: a saved choice in the new form is pushed again in its own form; a legacy one
            // keeps its 0.4.0 bytes (LegacyLook) until the next send.
            is HomeScreenIntent.SetDock -> scope.launch {
                val tail = after.waitingApps.map { it.pkg }.filter { it !in event.dock }
                val choice = savedChoice()
                if (choice?.isNew == true) pushSaved(choice, event.dock + tail)
                else apply(event.dock + tail, after.themeId, after.pictureId, columns = savedColumns())
            }
            // RF-LAUNCHER-250 F4: a new look and "Restore mine" push the dock as it was saved, as
            // ModulesMachine does, not the list as the TV shows it now (an app since gone or switched
            // off would drop out and change dock_apps, and the TV's own dock edit would stop applying,
            // upstream #340). Only SetDock changes the list.
            // A look tap resets apps in a row to that look's own (columns null).
            is HomeScreenIntent.SetTheme -> if (Looks.offered(event.id, d.clock.today())) scope.launch { apply(savedDock() ?: after.dock.map { it.pkg }, event.id, event.picture, columns = null) }
            // look2: the draft goes to the TV in the new form; it and a new photo are kept until the
            // result, so a failed send loses neither (OpenLook reopens the draft).
            HomeScreenIntent.UseLook -> before.look?.takeUnless { sending }?.let { l ->
                val photo = when (l.picture) {
                    LookPicture.NEW_ID -> staged?.let { b -> before.newPicture?.let { NewPhoto(it, b, before.newDim) } }
                    else -> null
                }
                kept = l
                sending = true
                scope.launch { try { sendLook(l, photo, before) } finally { sending = false } }
            }
            HomeScreenIntent.CloseLook -> staged = null
            is OwnPictureStaged -> if (after.newPicture == event.ref) staged = event.jpeg
            is HomeScreenIntent.Look -> (event.edit as? LookEdit.SetKeyNames)?.let { e -> keyNames = e.on; scope.launch { saveKeyNames(e.on) } }
            HomeScreenIntent.RestoreMine -> scope.launch {
                val choice = savedChoice()
                if (choice?.isNew == true) pushSaved(choice, choice.dock)
                else apply(savedDock() ?: after.dock.map { it.pkg }, after.themeId, after.pictureId, columns = savedColumns())
            }
            is HomeScreenIntent.SetOwnPicture -> scope.launch {
                val dim = event.dim.coerceIn(0, 90)
                attempt { OwnPicture.stage(d, event.jpeg) }.onSuccess { send(OwnPictureStaged(it, dim, event.jpeg)) }
                    .onFailure { effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false)) }
            }
            HomeScreenIntent.PreviewStock -> after.stock?.let { stock -> scope.launch { send(Preview(writer.preview(tvId, PlanRequest.UseStockHome(stock)))) } }
            HomeScreenIntent.UseStock -> after.stock?.let { stock -> scope.launch { useStock(stock) } }
            HomeScreenIntent.PreviewUpdate -> scope.launch { previewUpdate() }
            HomeScreenIntent.UpdateHomeScreen -> scope.launch { update() }
            HomeScreenIntent.RunSetupAgain -> if (after.notOnTv?.setupAgain == true) effect(ManageEffect.Navigate("setup/${tvId.value}"))
            HomeScreenIntent.HelpAddTv -> if (after.notOnTv?.helpAddTv == true) effect(ManageEffect.Navigate("report/tv/${tvId.value}"))
            else -> {}
        }
    }

    private suspend fun load() {
        val tv = d.tv(tvId) ?: return
        val profile = d.profileOf(tv)
        val snap = d.snapshots.latest(tvId)
        val packages = snap?.packages.orEmpty()
        val choice = d.homePrefs.load(tvId)
        val today = d.clock.today()
        val lookId = choice?.lookOn(today)
        // The stored snapshot cannot say which apps are launchable, so the rule without that
        // answer applies: never a home screen, a provider or a system-only package.
        val apps = (snap?.let { DockCandidates.of(it, null, profile, d.facts) } ?: emptySet())
            .map { AppChoice(it, d.nameOf(it, packages[it]?.label)) }.sortedBy { it.name.lowercase() }
        // The dock as the TV shows it: an app no longer there (gone, switched off) is skipped and the
        // later ones move up a slot, as the launcher does. 0.4.0: at the saved apps in a row, else the
        // look's own; the saved apps beyond what that shows wait (waitingApps).
        val columns = choice?.columns ?: DockLayers.columnsOf(theme(lookId)?.doc)
        val shown = { p: PackageName -> snap == null || packages[p]?.enabled.let { it != null && it != EnabledState.DISABLED && it != EnabledState.DISABLED_USER } }
        fun named(p: PackageName) = AppChoice(p, d.nameOf(p, packages[p]?.label))
        val dock = DockLayers.decode(choice?.dock.orEmpty(), shown, columns).map(::named)
        val waiting = DockLayers.keep(choice?.dock.orEmpty().filter(shown)).drop(DockLayers.capacity(columns)).map(::named)
        // look2: the TV's home screen version and memory, and whether it shows Inputs (HDMI sources,
        // or the profile's live TV app installed).
        val versionCode = packages[Launcher.pkg]?.versionCode
        val ramMb = snap?.facts?.ramMb?.takeIf { it > 0 }
        val hasInputs = snap == null || snap.hdmi.isNotEmpty() || profile?.liveTv?.let { it in packages } == true
        val tvLook = LookTv(versionCode, ramMb, hasInputs)
        val own = attempt { OwnPicture.ref(d, tvId) }.getOrNull()
        val cardsNow = attempt { Cards.current(d, tvId, choice, snap?.launcherOverlay) }.getOrNull() ?: Cards.MANAGE_DEFAULT
        val earlierLook = attempt { EarlierLook.load(d.documents, d.blobs, tvId) }.getOrNull()
        val keys = attempt { d.appPrefs.load() }.getOrNull()?.lookKeyNames ?: false
        val inDock = (dock + waiting).map { it.pkg }.toSet()
        val sentPreview = choice?.let { drafts.previewOf(it, dock + waiting, apps.filter { a -> a.pkg !in inDock }, cardsNow, own, tvLook) }
        val pictureId = if (choice?.isNew == true) choice.picture else choice?.picture?.takeUnless { Looks.dated(lookId) }
        val ended = if (choice?.isNew == true) choice.picture?.takeIf { drafts.datedPicture(it) && drafts.pictureOptions().none { o -> o.id == it } }
            ?.let { id -> drafts.gallery().firstOrNull { it.id == id }?.name ?: id }
            else choice?.endedLook(today)?.let { id -> theme(id)?.name ?: id }
        send(Loaded(HomeScreenState(tv.nickname, dock, apps, if (choice?.isNew == true) null else lookId, offeredThemes(d),
            // 0.4.6: a TV on a brand set or none (R0) has no list of its own; the stock home the
            // switch armed its watchdog with is the TV's own then.
            stock = profile?.stockLaunchers?.firstOrNull() ?: attempt { HomePlaceholder.stockFromRecords(d.ledger.changes(tvId)) }.getOrNull(),
            homeScreenOurs = tv.launcher != null, columns = columns, updateTo = updateTo(tv, packages),
            tvSideSettings = (packages[Launcher.pkg]?.versionCode ?: 0) >= FIRST_WITH_TV_SETTINGS,
            notOnTv = notOnTv(tv, snap), tclRemoteKeys = snap != null && TclGoogleTv.applies(snap.facts, snap),
            endedLook = ended, pictureId = pictureId, pictures = pictureOptions(),
            ownPicture = own, ownDim = choice?.dim ?: OwnPicture.DEFAULT_DIM,
            waitingApps = waiting, earlierPicture = earlierLook?.photo?.ref, sent = sentPreview, sentStyle = drafts.styleOf(choice, tvLook),
            sentPicture = drafts.pictureIdOf(choice, tvLook), launcherVersionCode = versionCode, ramMb = ramMb, hasInputs = hasInputs),
            choice, earlierLook, cardsNow, keys))
    }

    // 0.3.3 D2: null while the new home screen is on the TV: Setup recorded it (tv.launcher), or the
    // newest snapshot lists it (installed, perhaps not the home screen now: "Use the TV's own home
    // screen" keeps it, and a look still applies). Without a snapshot this phone cannot tell, so the
    // rows stay and the planner's refusal is the backstop.
    // 0.3.4 (issues #171, #187, #220): a launcher under our package name that Setup left as it is
    // (HOME_CHANGED: installed another way, with another key) is not ours either, so the screen says
    // why, as for issue #8, instead of offering a dock and a look for it.
    private suspend fun notOnTv(tv: tv.cleanyour.core.model.Tv, snap: tv.cleanyour.core.model.Snapshot?): NotOnTv? {
        if (tv.launcher != null || snap == null) return null
        val decision = attempt { d.homeDecisions.load(tvId) }.getOrNull()
        val reason = decision?.block?.let { b -> HomeBlock.entries.firstOrNull { it.name == b } }
        if (Launcher.pkg in snap.packages && reason != HomeBlock.HOME_CHANGED) return null
        // beta3 (issues #701, #1273): a block an earlier Setup decided, with an earlier version of
        // the app or another list than the TV resolves to now, may not hold any more: Setup again
        // decides it with this version. The TV's own facts (it refuses other home screens, its
        // Android is too old, its family boot-loops, it is Google's casting hardware, its remote's
        // app buttons need its home screen) and a home screen the person changed do not change with
        // the app, so those keep their line and their own next step.
        val now = attempt { d.profiles.resolveFor(snap) }.getOrNull()
        val earlier = reason != null && reason !in KEEPS_ITS_LINE &&
            decision?.earlier(d.about.version, now, tv.profileId) == true
        val unlisted = tv.profileId == null && (!earlier || now !is ProfileResolution.Exact)
        if (earlier) return NotOnTv(reason = reason, brand = LogCodes.clean(snap.facts.brand, 40).ifEmpty { tv.nickname },
            unlisted = unlisted, setupAgain = true, earlier = true)
        val setupAgain = when (reason) {
            null, HomeBlock.NONE, HomeBlock.WIZARD_WIFI_UNKNOWN -> true
            // Issue #8: the person can make the TV's own home screen Home again, and on a TV an exact
            // profile knows Setup then offers the switch. Without that list it may still not.
            HomeBlock.HOME_CHANGED -> !unlisted
            // Forgetting and adding the network again makes the TV's own Wi-Fi settings the owner; on a
            // TV no exact profile knows the wizard still has to stay on (HomeSwitchRisk), so not there.
            HomeBlock.WIZARD_WIFI -> !unlisted
            // beta3: with that other home screen app removed, Setup can switch.
            HomeBlock.OTHER_HOME -> true
            else -> false
        }
        val needsList = reason == HomeBlock.UNKNOWN_HOME || reason == HomeBlock.WIZARD_STAYS_ON || (reason == HomeBlock.WIZARD_WIFI && unlisted)
        return NotOnTv(
            reason = reason, brand = LogCodes.clean(snap.facts.brand, 40).ifEmpty { tv.nickname },
            fireTv = reason == HomeBlock.TV_REFUSES && FireOs.isFireOs(snap.facts, snap), unlisted = unlisted,
            setupAgain = setupAgain, helpAddTv = needsList && unlisted,
        )
    }

    // D4: the bundled launcher is newer than ours on the TV (same signer, lower versionCode). The
    // stored snapshot says which build is installed and with which key; one from before the app
    // read signers falls back to what Setup recorded for this TV. The planner checks again on a
    // fresh snapshot before anything is installed.
    private fun updateTo(tv: tv.cleanyour.core.model.Tv, packages: Map<PackageName, tv.cleanyour.core.model.PackageState>): String? {
        if (updated) return null
        val pin = runCatching { d.launcherPin.bundled() }.getOrNull() ?: return null
        val installed = packages[pin.pkg] ?: return null
        val signer = installed.signer ?: tv.launcher?.signer ?: return null
        return pin.versionName.takeIf { signer == pin.signer && installed.versionCode < pin.versionCode }
    }

    private suspend fun previewUpdate() {
        val pin = attempt { d.launcherPin.bundled() }.getOrNull() ?: return
        val plan = writer.preview(tvId, PlanRequest.UpdateLauncher(pin), fresh = true)
        if (plan == null || plan.steps.isEmpty()) {
            // Nothing to update after all (a fresher look at the TV), or it cannot be planned.
            if (plan != null) updated = true
            effect(ManageEffect.Snackbar(if (plan != null) SnackKind.HOME_UPDATED else SnackKind.FAILED, pin.versionName, false))
            load()
            return
        }
        send(UpdatePreview(plan))
    }

    // "Update the home screen": the bundled launcher over ours, in place (RFC-LAUNCHER-1). The dock,
    // the look (config.json) and the card answers stay on the TV, since only an uninstall removes
    // them, so nothing is pushed again.
    private suspend fun update() {
        send(UpdatePreview(null))
        val pin = attempt { d.launcherPin.bundled() }.getOrNull() ?: return effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        val r = writer.write(tvId, PlanRequest.UpdateLauncher(pin), fresh = true) { send(BusyNow(it)) }
        send(BusyNow(null))
        when {
            r is WriteResult.Done && r.finished.status == RunStatus.COMPLETED -> {
                updated = true
                d.tv(tvId)?.let { t -> t.launcher?.let { l -> d.tvs.upsert(t.copy(launcher = l.copy(versionCode = pin.versionCode, signer = pin.signer))) } }
                effect(ManageEffect.Snackbar(SnackKind.HOME_UPDATED, pin.versionName, false))
            }
            r is WriteResult.GateCancelled || r is WriteResult.NoTv || r is WriteResult.Paused -> {}   // Paused: the open-run card (O2)
            else -> effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        }
        load()
    }

    private suspend fun checkDrift() {
        val tv = d.tv(tvId) ?: return
        val ours = tv.launcher?.ourOverlayDigest ?: return
        val session = d.access.live(tvId) ?: return
        val now = attempt { d.configTransport.readDigest(session) }.getOrNull()
        send(Drift(now != null && now != ours))
    }

    companion object {
        // beta3: the blocks an earlier Setup's decision keeps across app versions (notOnTv).
        val KEEPS_ITS_LINE: Set<HomeBlock> = setOf(HomeBlock.TV_REFUSES, HomeBlock.OLD_ANDROID, HomeBlock.BOOT_LOOP, HomeBlock.CASTING,
            HomeBlock.REMOTE_BUTTONS, HomeBlock.HOME_CHANGED)

        // Launcher 2.4 (versionCode 5) added Launcher settings on the TV (RF-LAUNCHER-240 F16).
        const val FIRST_WITH_TV_SETTINGS = 5L
        const val DEFAULT_LOOK = "default"

        // 0.4.0: the looks on offer today; a dated look (a festival) up to its last day.
        private fun offeredThemes(d: ManageDeps): List<ThemeOption> = LookDrafts(d.themes, d.pictures, d.encoder, d.clock).offered()
    }

    private fun theme(id: String?) = drafts.theme(id)

    private fun pictureOptions(): List<PictureOption> = drafts.pictureOptions()

    private suspend fun savedChoice(): HomeChoice? = d.homePrefs.load(tvId)
    private suspend fun savedDock(): List<PackageName>? = d.homePrefs.load(tvId)?.dock
    private suspend fun savedColumns(): Int? = d.homePrefs.load(tvId)?.columns

    // Advanced's Show launcher names is a phone setting (AppPrefs, lookKeyNames).
    private suspend fun saveKeyNames(on: Boolean) {
        attempt { d.appPrefs.save(d.appPrefs.load().copy(lookKeyNames = on)) }
    }

    // A photo made in the Look sheet on its way to the TV: its staged file, its bytes and its dim.
    private class NewPhoto(val ref: BlobRef, val jpeg: ByteArray, val dim: Int)

    // Dock and look go to the TV as one config.json (LauncherConfigCodec), pushed by an ApplyTheme
    // plan; the digest we wrote is remembered so the next connect can warn on drift. dock_apps is
    // the dock and the rows under it in order, rows_below_dock the rows under the dock that hold an
    // app (DockLayers), so the TV shows the rows the sheet showed. module_cards keeps this TV's cards
    // choice (RFC-CARDS-1): a new dock or theme never turns cards on or off.
    // 0.4.0 `picture`: the Picture row's choice (HomeChoice.picture); a dated look keeps its own.
    // `photo`: the photo made in the sheet (LookPicture.NEW_ID), kept for this TV only once the TV has
    // it; until then the kept one (and "Restore mine") stays the photo on the TV.
    // 0.4.0 `columns`: the apps in a row to send, null for the look's own; it is kept only when it
    // differs from the look's own. The whole `dock` is kept (DockLayers.keep, up to 24 apps), not the
    // part this many in a row shows, so a smaller row never loses the rest of the order.
    private suspend fun apply(dock: List<PackageName>, themeId: String?, picture: String?, photo: NewPhoto? = null, columns: Int? = null) {
        val cards = Cards.current(d, tvId, d.homePrefs.load(tvId))
        val pictureId = picture.takeUnless { Looks.dated(themeId) }
        val sendsNew = pictureId == LookPicture.NEW_ID && photo != null
        val dim = if (sendsNew) photo!!.dim else state.value.ownDim
        val lookPicture = if (sendsNew) LookPicture.Of(photo!!.ref, photo.dim)
            else OwnPicture.lookPicture(d, tvId, pictureId.takeUnless { it == LookPicture.NEW_ID }, dim)
        val override = drafts.override(themeId, columns)
        val look = attempt { Look.encode(d.encoder, d.blobs, theme(themeId), dock, cards, lookPicture, override) }.getOrNull()
            ?: return effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        val kept = DockLayers.keep(dock)
        val r = writer.write(tvId, PlanRequest.ApplyTheme(look.doc, look.config, look.wallpaper)) { send(BusyNow(it)) }
        send(BusyNow(null))
        if (r is WriteResult.Done) {
            // The new photo is kept only when the run completed, so it is the one on the TV; when it
            // cannot be kept, the choice stays as it was saved.
            val keptNew = sendsNew && r.finished.status == RunStatus.COMPLETED &&
                attempt { OwnPicture.keep(d, tvId, photo!!.jpeg) }.isSuccess
            // A dated look keeps its own picture, so the picture and dim saved for the look before it
            // stay, and come back with that look once the dated one ends.
            val saved = d.homePrefs.load(tvId) ?: HomeChoice(emptyList(), null)
            val keepSaved = Looks.dated(themeId) || (pictureId == LookPicture.NEW_ID && !keptNew)
            val chosen = if (keptNew) LookPicture.MINE_ID else pictureId
            d.homePrefs.save(tvId, saved.withTheme(themeId, kept, cards, picture = if (keepSaved) saved.picture else chosen,
                dim = if (keepSaved) saved.dim else if (chosen == LookPicture.MINE_ID) dim else null, columns = override))
            rememberPushedLook(d, tvId, look.digest)
            send(Drift(false))
            effect(ManageEffect.Snackbar(SnackKind.HOME_SAVED, "", false))
            load()
            return
        }
        load()
        // 0.3.3 D2: the TV said the new home screen is gone (removed since the snapshot this screen
        // read): the reload shows why there is nothing to send, which says more than "We stopped".
        if (r is WriteResult.Refused && state.value.notOnTv != null) return
        if (r !is WriteResult.GateCancelled && r !is WriteResult.NoTv && r !is WriteResult.Paused) {   // Paused: the open-run card (O2)
            effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        }
    }

    // look2: a saved choice in the new form pushed again as it is, with `dock` (a dock change,
    // Restore mine): its own bytes on this TV. The choice keeps its form; only the dock changes.
    private suspend fun pushSaved(choice: HomeChoice, dock: List<PackageName>) {
        val cards = Cards.current(d, tvId, choice)
        val tv = lookTv(state.value)
        val own = attempt { OwnPicture.ref(d, tvId) }.getOrNull()
        val look = attempt { val (doc, w) = drafts.docOf(choice, dock, cards, own, tv); Look.encode(d.encoder, d.blobs, doc, w) }.getOrNull()
            ?: return effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
        val r = writer.write(tvId, PlanRequest.ApplyTheme(look.doc, look.config, look.wallpaper)) { send(BusyNow(it)) }
        send(BusyNow(null))
        if (r is WriteResult.Done) {
            d.homePrefs.save(tvId, choice.copy(dock = DockLayers.keep(dock), cards = cards))
            rememberPushedLook(d, tvId, look.digest)
            send(Drift(false))
            effect(ManageEffect.Snackbar(SnackKind.HOME_SAVED, "", false))
            load()
            return
        }
        load()
        if (r is WriteResult.Refused && state.value.notOnTv != null) return
        if (r !is WriteResult.GateCancelled && r !is WriteResult.NoTv && r !is WriteResult.Paused) effect(ManageEffect.Snackbar(SnackKind.FAILED, "", false))
    }

    // look2 "Put it on the TV": the draft in the new form (style keys outright, fill_dock false,
    // columns always), with the saved dock. `photo`: a photo made in the sheet; the earlier look's
    // photo comes from earlier.jpg. On success the choice is saved and the look the TV had before
    // becomes the earlier look (EarlierLook); a photo is kept for the TV only once the TV has it.
    // On failure the draft and the photo stay (lookFailed, OpenLook), and no generic snackbar shows.
    private suspend fun sendLook(draft: LookDraft, photo: NewPhoto?, at: HomeScreenState) {
        val before = d.homePrefs.load(tvId)
        val dock = before?.dock ?: at.dock.map { it.pkg }
        val cards = Cards.current(d, tvId, before)
        val earlierPhoto = if (draft.picture == LookPicture.EARLIER_ID)
            attempt { d.documents.read(EarlierLook.photoPath(tvId)) { it } }.getOrNull()?.let { b -> earlier?.photo?.let { NewPhoto(it.ref, b, it.dim ?: OwnPicture.DEFAULT_DIM) } }
            else null
        val sendsPhoto = photo ?: earlierPhoto
        val ctx = lookContext(at).copy(cards = cards)
        val look = attempt { val (doc, w) = drafts.docOf(draft, dock, cards, ctx); Look.encode(d.encoder, d.blobs, doc, w) }.getOrNull()
        if (look == null || (draft.newPhoto && sendsPhoto == null)) { send(LookResult(false)); return }
        val r = writer.write(tvId, PlanRequest.ApplyTheme(look.doc, look.config, look.wallpaper)) { send(BusyNow(it)) }
        send(BusyNow(null))
        if (r is WriteResult.Done) {
            val oldPhoto = attempt { d.documents.read(OwnPicture.path(tvId)) { it } }.getOrNull()
            val keptNew = sendsPhoto != null && r.finished.status == RunStatus.COMPLETED && attempt { OwnPicture.keep(d, tvId, sendsPhoto.jpeg) }.isSuccess
            val picture = when {
                !draft.newPhoto -> draft.picture ?: LookPicture.NONE_ID
                keptNew -> LookPicture.MINE_ID
                else -> before?.picture ?: LookPicture.NONE_ID
            }
            val dim = when {
                picture != LookPicture.MINE_ID -> null
                keptNew -> sendsPhoto.dim
                else -> at.ownDim
            }
            val kept = DockLayers.keep(dock)
            val next = drafts.choiceOf(draft, before, kept, cards, picture, dim)
            attempt { EarlierLook.remember(d.documents, tvId, EarlierLook.firstLook(before, kept), next, keptNew, oldPhoto) }
            d.homePrefs.save(tvId, next)
            rememberPushedLook(d, tvId, look.digest)
            if (photo != null) staged = null
            this.kept = null
            send(LookResult(true))
            send(Drift(false))
            effect(ManageEffect.Snackbar(SnackKind.HOME_SAVED, "", false))
            load()
            return
        }
        load()
        if (r is WriteResult.Paused) { this.kept = null; send(LookResult(true)); return }   // the open-run card (O2)
        if (r is WriteResult.Refused && state.value.notOnTv != null) { this.kept = null; send(LookResult(true)); return }
        send(LookResult(false))
    }

    // "Use the TV's own home screen": re-enables the stock launcher, sets it as home, keeps ours
    // installed (PRODUCT-SPEC M2).
    private suspend fun useStock(stock: Component) {
        send(Preview(null))
        val r = writer.useStockHome(tvId, stock) { send(BusyNow(it)) }
        send(BusyNow(null))
        if (r is WriteResult.Done) effect(ManageEffect.Snackbar(SnackKind.HOME_SAVED, d.nameOf(stock.pkg), false))
        load()
    }
}
