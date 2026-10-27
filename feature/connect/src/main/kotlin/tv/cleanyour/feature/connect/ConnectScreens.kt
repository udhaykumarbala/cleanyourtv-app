// CytBottomSheet takes the Material 3 sheet state, which is still marked experimental.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package tv.cleanyour.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import tv.cleanyour.core.discovery.DiscoverySource
import tv.cleanyour.core.discovery.FoundTv
import tv.cleanyour.core.flows.ChipState
import tv.cleanyour.core.flows.ConnectError
import tv.cleanyour.core.flows.DoorPhase
import tv.cleanyour.core.flows.Families
import tv.cleanyour.core.flows.SetupActions
import tv.cleanyour.core.flows.SetupExtras
import tv.cleanyour.core.flows.SetupIntent
import tv.cleanyour.core.flows.SetupState
import tv.cleanyour.core.flows.Sheet
import tv.cleanyour.core.flows.StopAction
import tv.cleanyour.core.flows.StopCase
import tv.cleanyour.core.flows.fingerprintTail
import tv.cleanyour.ui.design.component.ActionColumn
import tv.cleanyour.ui.design.component.AdbAllowMirror
import tv.cleanyour.ui.design.component.Banner
import tv.cleanyour.ui.design.component.BannerTone
import tv.cleanyour.ui.design.component.CheckLine
import tv.cleanyour.ui.design.component.ChoiceList
import tv.cleanyour.ui.design.component.ChoiceOption
import tv.cleanyour.ui.design.component.CodeMatchCard
import tv.cleanyour.ui.design.component.CytBottomSheet
import tv.cleanyour.ui.design.component.CytTextField
import tv.cleanyour.ui.design.component.CytTopBar
import tv.cleanyour.ui.design.component.DiscoveryChip
import tv.cleanyour.ui.design.component.GlassChip
import tv.cleanyour.ui.design.component.Gap
import tv.cleanyour.ui.design.component.Lead
import tv.cleanyour.ui.design.component.MonospaceBlock
import tv.cleanyour.ui.design.component.Overline
import tv.cleanyour.ui.design.component.PagerDots
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.ScreenScaffold
import tv.cleanyour.ui.design.component.ScreenTitle
import tv.cleanyour.ui.design.component.SecondaryButton
import tv.cleanyour.ui.design.component.SheetHeader
import tv.cleanyour.ui.design.component.StepCard
import tv.cleanyour.ui.design.component.StopCard
import tv.cleanyour.ui.design.component.TertiaryButton
import tv.cleanyour.ui.design.component.TitleSize
import tv.cleanyour.ui.design.component.TvMenuArt
import tv.cleanyour.ui.design.component.TvStatusChip
import tv.cleanyour.ui.design.component.TvThumbnail
import tv.cleanyour.ui.design.component.WelcomeIllustration
import tv.cleanyour.ui.design.component.rememberEmphasis
import tv.cleanyour.ui.design.theme.Glow

// Screens S1, S1b, S1c, S2a to S2d and S2x (PRODUCT-SPEC 4, UX 2 screen 1 and 2, UX 4.1 to 4.5),
// built from the :ui:design components. Stateless: they render a SetupState plus its extras and
// send everything back through SetupActions. `isConnectState` is false for the states
// :feature:setup draws.
fun isConnectState(state: SetupState): Boolean =
    state is SetupState.Welcome || state is SetupState.Picker || state is SetupState.OpenDoor || state is SetupState.PairingNeeded


@Composable
fun ConnectFlow(state: SetupState, extras: SetupExtras, actions: SetupActions, onReturning: () -> Unit, modifier: Modifier = Modifier, onCopyError: (String) -> Unit = {}) {
    when (state) {
        is SetupState.Welcome -> WelcomeScreen(state, extras, actions, onReturning, modifier)
        is SetupState.Picker -> PickerScreen(state, extras, actions, modifier)
        is SetupState.OpenDoor -> DoorScreen(state, extras, actions, modifier, onCopyError)
        is SetupState.PairingNeeded -> PairingScreen(state, extras, actions, modifier)
        else -> {}
    }
    if (extras.sheet == Sheet.ADDRESS) AddressSheet(extras.addressError, actions)
}

// The inline stops a report helps with (0.4.6): the TV's debugging connection is too basic. The
// others are the person's next step (no TV yet, Wi-Fi off, a different TV at the address).
private val REPORT_CASES = setOf(StopCase.TV_NOT_SUPPORTED)

// An inline stop (TV not found, wrong network, identity changed, not supported) under the screen.
@Composable
private fun InlineCard(extras: SetupExtras, actions: SetupActions) {
    val c = extras.card ?: return
    // The card is the news: it scrolls into view when it appears instead of sitting under the
    // pinned Start (R4-W8).
    val bring = remember { BringIntoViewRequester() }
    // The request waits for the card's first placement: a request made before layout is a no-op.
    var placed by remember(c.case) { mutableStateOf(false) }
    LaunchedEffect(placed) { if (placed) bring.bringIntoView() }
    StopCard(
        c.title, c.body, Modifier.padding(top = 20.dp).bringIntoViewRequester(bring).onGloballyPositioned { placed = true },
        primaryLabel = c.primary, onPrimary = { actions.intent(SetupIntent.StopAction(StopAction.PRIMARY)) },
        secondaryLabel = c.secondary, onSecondary = { actions.intent(SetupIntent.StopAction(StopAction.SECONDARY)) },
        detailsLabel = if (c.case in REPORT_CASES) stringResource(R.string.connect_report) else null, onDetails = { actions.report(c.title) },
        primaryGuarded = c.case.primaryActs(),
    )
}

// S1 Welcome (D8, D19, D5): discovery is already running; the live line is the discovery chip.
// Mockup 01: one promise, one action ("Start") and the "I've set up a TV before" link. Address entry
// lives where S1b and UX 4.1 put it (Which TV?, the "No TV found yet" card) and on the door of a TV
// Start went straight to ("Not this TV?"), not on Welcome (round 3).
@Composable
fun WelcomeScreen(state: SetupState.Welcome, extras: SetupExtras, actions: SetupActions, onReturning: () -> Unit, modifier: Modifier = Modifier) {
    val found = state.found
    val line = when {
        found.size >= 2 -> msg(R.string.welcome_found_many, "count" to found.size)
        found.size == 1 -> msg(R.string.welcome_found_one_noroom, "name" to displayName(found[0]))
        state.looking -> stringResource(R.string.welcome_looking)
        else -> stringResource(R.string.welcome_none_yet)
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // A short phone (the 360 x 720 dp emulator leaves about 600 dp between its bars) gets a
        // smaller hero and tighter gaps, so the promise, the body and the live line all fit above
        // Start in the first view, as mockup 01 shows them.
        val density = LocalDensity.current
        val insets = WindowInsets.safeDrawing
        val usable = maxHeight - with(density) { (insets.getTop(density) + insets.getBottom(density)).toDp() }
        val compact = usable < WELCOME_COMPACT_BELOW
        ScreenScaffold(
            Glow.Brand,
            bottomBar = {
                // The live discovery line is pinned with Start, the button it speaks for, so it shows
                // before the first tap on every phone height (mockup 01: "the live status chip says
                // the TV is found before the first tap"); pinned like S3's safety line (R4-W8 H1).
                DiscoveryChip(line, found = found.isNotEmpty(), modifier = Modifier.padding(bottom = 14.dp))
                ActionColumn {
                    PrimaryButton(stringResource(R.string.welcome_start), { actions.intent(SetupIntent.Start) })
                    TertiaryButton(stringResource(R.string.welcome_returning), onReturning)
                }
            },
        ) {
            // The product name scrolls with the hero (no top bar on Welcome), so a stop card scrolled
            // into view never slides the film under it (R4-W8). Gaps and the 300 dp hero are the
            // mockup's (28, 32, 40).
            Overline(stringResource(R.string.welcome_overline), Modifier.padding(top = if (compact) 12.dp else 28.dp))
            WelcomeIllustration(
                Modifier.padding(top = if (compact) 16.dp else 32.dp).align(Alignment.CenterHorizontally)
                    .widthIn(max = if (compact) 248.dp else 300.dp),
                contentDescription = stringResource(R.string.welcome_hero_cd),
            )
            ScreenTitle(stringResource(R.string.welcome_title), Modifier.padding(top = if (compact) 20.dp else 40.dp), size = TitleSize.DISPLAY)
            Lead(stringResource(R.string.welcome_body), Modifier.padding(top = 12.dp))
            InlineCard(extras, actions)
        }
    }
}

private val WELCOME_COMPACT_BELOW = 680.dp

@Composable
fun displayName(tv: FoundTv): String = tv.friendlyName ?: tv.model ?: tv.host

// S1b Which TV? (2 or more found; UX 4.12).
@Composable
fun PickerScreen(state: SetupState.Picker, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableStateOf(state.found.firstOrNull()?.host) }
    val chosen = state.found.firstOrNull { it.host == selected } ?: state.found.firstOrNull()
    ScreenScaffold(
        Glow.Tv, modifier,
        topBar = { CytTopBar(onBack = { actions.back() }) },
        bottomBar = {
            ActionColumn {
                PrimaryButton(
                    msg(R.string.picker_primary, "name" to (chosen?.let { displayName(it) } ?: "")),
                    { chosen?.let { actions.intent(SetupIntent.PickTv(it.host)) } }, enabled = chosen != null,
                )
                TertiaryButton(stringResource(R.string.picker_tertiary), { actions.intent(SetupIntent.Open(Sheet.ADDRESS)) })
            }
        },
    ) {
        ScreenTitle(stringResource(R.string.picker_title), Modifier.padding(top = 20.dp))
        Lead(stringResource(R.string.picker_body), Modifier.padding(top = 12.dp))
        Overline(msg(R.string.welcome_found_many, "count" to state.found.size), Modifier.padding(start = 4.dp, top = 24.dp, bottom = 10.dp))
        // Two TVs with the same name get the last part of the address (UX 4.12).
        val dupes = state.found.groupBy { displayName(it) }.filterValues { it.size > 1 }.keys
        val options = state.found.map { tv ->
            val name = displayName(tv)
            val title = if (name in dupes) msg(R.string.picker_name_with_suffix, "name" to name, "suffix" to tv.host.substringAfterLast('.')) else name
            ChoiceOption(tv.host, title, subtitle = tv.model?.takeIf { it != name })
        }
        ChoiceList(options, chosen?.host, { selected = it.key })
        Banner(stringResource(R.string.picker_footer), BannerTone.INFO, Modifier.padding(top = 20.dp))
        InlineCard(extras, actions)
    }
}

// S2a to S2d: one screen, four states; the phone advances on its own (UX 2 screen 2).
@Composable
fun DoorScreen(state: SetupState.OpenDoor, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier, onCopyError: (String) -> Unit = {}) {
    // A typed address has no name until the TV answers: the chip then reads the model the TV gave
    // (the identity line's), not the address (round 3).
    val name = extras.tvName ?: state.tv.friendlyName ?: state.tv.model ?: extras.identity?.model ?: state.tv.host.ifEmpty { null }
    var showNotSeen by rememberSaveable { mutableStateOf(false) }
    var brandsOpen by rememberSaveable { mutableStateOf(state.family == Families.UNKNOWN) }
    // S2c has no primary, only the way out; S2d asks the identity question with Yes and No.
    val allowBar: @Composable ColumnScope.() -> Unit = { SecondaryButton(stringResource(R.string.allow_dont_see), { showNotSeen = true }) }
    val identityBar: @Composable ColumnScope.() -> Unit = {
        ActionColumn {
            PrimaryButton(stringResource(R.string.identity_yes), { actions.intent(SetupIntent.ConfirmIdentity) })
            SecondaryButton(stringResource(R.string.identity_no), { actions.intent(SetupIntent.RejectIdentity) })
        }
    }
    val bottom = when (state.phase) {
        DoorPhase.ALLOW -> allowBar
        DoorPhase.IDENTITY -> identityBar
        else -> null
    }
    ScreenScaffold(
        Glow.Tv, modifier,
        topBar = {
            CytTopBar(onBack = { actions.back() }, center = { if (name != null) TvStatusChip(name, (extras.chip ?: ChipState.WAITING).toTvStatus()) })
        },
        bottomBar = bottom,
    ) {
        when (state.phase) {
            DoorPhase.ALLOW -> { AllowContent(state, extras); NotThisTv(state, actions) }
            DoorPhase.IDENTITY -> IdentityContent(extras)
            DoorPhase.CONNECTED -> {
                ScreenTitle(msg(R.string.connected_title, "tv" to (name ?: "")), Modifier.padding(top = 8.dp))
                CheckLine(stringResource(R.string.allow_trusted), Modifier.padding(top = 16.dp))
            }
            DoorPhase.STEPS, DoorPhase.CONNECTING -> {
                // beta2 QA: "Not a 192.168.2.1? Change model" with a typed address; the line names a
                // model only, never the address the chip falls back to.
                StepsContent(state, actions, name?.takeUnless { it == state.tv.host }, brandsOpen, onBrands = { brandsOpen = !brandsOpen })
                extras.connectError?.let { ConnectErrorCard(it, onCopyError, onReport = { title -> actions.report(title) }) }
                NotThisTv(state, actions)
            }
        }
        InlineCard(extras, actions)
        Gap(16.dp)
    }
    if (showNotSeen) {
        CytBottomSheet({ showNotSeen = false }) {
            NotSeenContent(extras.allowRetries, onAsk = { actions.intent(SetupIntent.AskTvAgain); showNotSeen = false })
        }
    }
}

// 0.3.6 (issues #98, #270, #325, #468): the TV's port answered but the connection failed. What may be
// in the way on the phone's side, and "Copy error" with a short redacted summary for an issue.
// 0.4.6: "Report a problem" under it opens the GitHub form with that side filled in, no paste.
@Composable
internal fun ConnectErrorCard(error: ConnectError, onCopy: (String) -> Unit, modifier: Modifier = Modifier, onReport: (String) -> Unit = {}) {
    var copied by remember(error) { mutableStateOf(false) }
    val title = stringResource(R.string.door_connect_failed_title)
    StopCard(
        title,
        msg(R.string.door_connect_failed_body, "kind" to error.kind) + "\n\n" + stringResource(R.string.door_connect_failed_hint),
        modifier.padding(top = 20.dp),
        primaryLabel = stringResource(if (copied) R.string.door_error_copied else R.string.door_copy_error),
        onPrimary = { onCopy(error.summary); copied = true },
        detailsLabel = stringResource(R.string.connect_report), onDetails = { onReport(title) },
        primaryGuarded = false,
    )
}

// Start goes straight to the one device discovery found, which may not be the TV (emulator run,
// finding 1). A quiet way out to the address sheet (S1c) while the door waits; on the identity line
// "No" does the same. Not shown for a typed address (Back is the way to retype it) or before a TV
// is known (the "No TV found yet" card offers the address).
@Composable
private fun NotThisTv(state: SetupState.OpenDoor, actions: SetupActions) {
    if (state.tv.host.isEmpty() || DiscoverySource.MANUAL in state.tv.sources) return
    TertiaryButton(stringResource(R.string.door_not_this_tv), { actions.intent(SetupIntent.Open(Sheet.ADDRESS)) }, Modifier.padding(top = 4.dp))
}

@Composable
private fun StepsContent(state: SetupState.OpenDoor, actions: SetupActions, name: String?, brandsOpen: Boolean, onBrands: () -> Unit) {
    ScreenTitle(stringResource(R.string.door_title), Modifier.padding(top = 8.dp))
    Lead(stringResource(R.string.door_body), Modifier.padding(top = 12.dp))
    state.banner?.let { Banner(it, BannerTone.INFO, Modifier.padding(top = 16.dp)) }
    if (state.family == Families.UNKNOWN) Body(stringResource(R.string.door_unknown_note), Modifier.padding(top = 12.dp))
    if (brandsOpen) BrandChips(onPick = { actions.intent(SetupIntent.ChangeFamily(it)) })
    val steps = familySteps(state.family)
    var page by rememberSaveable { mutableIntStateOf(state.step.coerceIn(0, 2)) }
    // The card follows the TV: when the port opens it moves to step 3 on its own.
    LaunchedEffect(state.step) { page = state.step.coerceIn(0, 2) }
    val shield = state.family == Families.ATV && listOfNotNull(state.tv.model, state.tv.friendlyName).any { "shield" in it.lowercase() }
    // A step's extra line: a Shield's network debugging on step 2, or for TCL outside China the Google
    // TV menu on step 1 and its switch on step 2 (it is Android TV or Google TV, and the phone cannot
    // tell which before it connects).
    val extra = when {
        page == 1 && shield -> stringResource(R.string.family_atv_extra_shield)
        page == 0 && state.family == Families.TCL_GLOBAL -> stringResource(R.string.family_tcl_extra_gtv_step1)
        page == 1 && state.family == Families.TCL_GLOBAL -> stringResource(R.string.family_tcl_extra_gtv)
        else -> null
    }
    val note = stringResource(R.string.door_menu_names_vary)
    if (state.family == Families.TCL_ASK && state.phase == DoorPhase.STEPS) {
        // The TCL chip: which menus first, then the steps that go with them (never the 本机信息
        // ones for a TV with English menus).
        TclMenuChips(onPick = { chinese -> actions.intent(SetupIntent.ChangeFamily(Families.ofTclMenus(chinese))) })
    } else {
        StepCard(
            title = msg(R.string.door_step_cd, "current" to page + 1, "total" to 3),
            body = if (extra != null) steps[page] + " " + extra else steps[page],
            modifier = Modifier.padding(top = 20.dp),
            illustration = {
                when (page) {
                    0 -> TvMenuArt(highlight = 3, modifier = Modifier.width(240.dp), taps = 7)
                    1 -> TvMenuArt(highlight = 1, modifier = Modifier.width(240.dp), toggleOn = true)
                    else -> TvMenuArt(highlight = 1, modifier = Modifier.width(240.dp), toggleOn = true)
                }
            },
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PagerDots(3, page, Modifier.padding(start = 4.dp), description = msg(R.string.door_step_cd, "current" to page + 1, "total" to 3))
            Spacer(Modifier.weight(1f))
            if (page < 2) TertiaryButton(stringResource(R.string.door_next), { page++ })
        }
    }
    val stillWaiting = state.phase == DoorPhase.STEPS && state.waitedMs >= 180_000
    val status = when {
        state.phase == DoorPhase.CONNECTING -> stringResource(R.string.door_connecting)
        stillWaiting -> stringResource(R.string.door_still_waiting)
        else -> stringResource(R.string.door_waiting)
    }
    StatusLine(status, Modifier.padding(start = 4.dp, top = 12.dp))
    if (stillWaiting) TertiaryButton(stringResource(R.string.door_still_waiting_show_me), { page = 1 })
    Body(note, Modifier.padding(start = 4.dp, top = 16.dp))
    Body(stringResource(R.string.door_same_wifi), Modifier.padding(start = 4.dp, top = 8.dp))
    TertiaryButton(
        if (name != null) msg(R.string.door_change_model, "model" to name) else stringResource(R.string.door_change_model_generic), onBrands,
        Modifier.padding(top = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrandChips(onPick: (String) -> Unit) {
    Overline(stringResource(R.string.door_brand_prompt), Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
    val brands = listOf(
        "Xiaomi" to R.string.door_brand_xiaomi, "TCL" to R.string.door_brand_tcl, "Sony" to R.string.door_brand_sony,
        "Hisense" to R.string.door_brand_hisense, "Philips" to R.string.door_brand_philips, "Google" to R.string.door_brand_google,
        "Amazon" to R.string.door_brand_amazon, "Other" to R.string.door_brand_other,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        brands.forEach { (key, res) -> GlassChip(stringResource(res), onClick = { onPick(key) }) }
    }
}

// After the TCL brand chip: one question, two answers, in the brand row's chips.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TclMenuChips(onPick: (chinese: Boolean) -> Unit) {
    Overline(stringResource(R.string.door_tcl_menus_prompt), Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassChip(stringResource(R.string.door_tcl_menus_global), onClick = { onPick(false) })
        GlassChip(stringResource(R.string.door_tcl_menus_chinese), onClick = { onPick(true) })
    }
}

@Composable
private fun familySteps(family: String): List<String> = when (family) {
    Families.GTV -> listOf(stringResource(R.string.family_gtv_step1), stringResource(R.string.family_gtv_step2), stringResource(R.string.family_gtv_step3))
    Families.FIRE -> listOf(stringResource(R.string.family_fire_step1), stringResource(R.string.family_fire_step2), stringResource(R.string.family_fire_step3))
    Families.TCL -> listOf(stringResource(R.string.family_tcl_step1), stringResource(R.string.family_tcl_step2), stringResource(R.string.family_tcl_step3))
    Families.ATV, Families.TCL_GLOBAL -> listOf(stringResource(R.string.family_atv_step1), stringResource(R.string.family_atv_step2), stringResource(R.string.family_atv_step3))
    else -> listOf(stringResource(R.string.family_unknown_step1), stringResource(R.string.family_unknown_step2), stringResource(R.string.family_unknown_step3))
}

// S2c Look at the TV: the dialog mirror with the full fingerprint and the last two bytes
// highlighted; the screen is FLAG_SECURE (D35, set by the app for this phase).
@Composable
private fun AllowContent(state: SetupState.OpenDoor, extras: SetupExtras) {
    val fp = state.fingerprint.orEmpty()
    val tail = fingerprintTail(fp)
    ScreenTitle(stringResource(R.string.allow_title), Modifier.padding(top = 8.dp))
    val body = stringResource(R.string.allow_body)
    Lead(rememberEmphasis(body, stringResource(R.string.allow_body_emphasis_always), stringResource(R.string.allow_body_emphasis_ok)), Modifier.padding(top = 12.dp))
    AdbAllowMirror(fp, Modifier.padding(top = 24.dp), contentDescription = msg(R.string.allow_art_cd, "tail" to tail))
    if (tail.isNotEmpty()) {
        CodeMatchCard(stringResource(R.string.allow_code_label), stringResource(R.string.allow_code_caption), tail, Modifier.padding(top = 24.dp))
    }
    StatusLine(
        stringResource(if (state.waitedMs >= 45_000 || extras.allowDenied) R.string.allow_still_waiting else R.string.allow_waiting),
        Modifier.padding(start = 4.dp, top = 20.dp),
    )
}

// S2d first-time identity line for a TV with no Allow dialog (D38).
@Composable
private fun IdentityContent(extras: SetupExtras) {
    val id = extras.identity
    ScreenTitle(stringResource(R.string.identity_question), Modifier.padding(top = 8.dp))
    if (id != null) Lead(msg(R.string.identity_detail, "name" to (extras.tvName ?: id.model), "serial" to id.serialLast4), Modifier.padding(top = 12.dp))
    TvThumbnail(rememberPng(id?.screenshot), stringResource(R.string.identity_screenshot_cd), Modifier.padding(top = 20.dp))
    Body(stringResource(R.string.identity_body), Modifier.padding(start = 4.dp, top = 16.dp))
}

// UX 4.4: three paragraphs and "Ask the TV again"; after 3 retries, the restart paragraph.
@Composable
internal fun NotSeenContent(retries: Int, onAsk: () -> Unit) {
    SheetHeader(stringResource(R.string.notseen_title))
    if (retries >= 3) {
        Body(stringResource(R.string.notseen_restart), Modifier.padding(top = 12.dp))
        PrimaryButton(stringResource(R.string.notseen_restarted), onAsk, Modifier.padding(top = 20.dp))
    } else {
        Body(stringResource(R.string.notseen_hidden), Modifier.padding(top = 12.dp))
        Body(stringResource(R.string.notseen_cancelled), Modifier.padding(top = 12.dp))
        Body(stringResource(R.string.notseen_greyed), Modifier.padding(top = 12.dp))
        PrimaryButton(stringResource(R.string.notseen_ask_again), onAsk, Modifier.padding(top = 20.dp))
    }
}

// S2x: the 0.1 dead end for pairing-only TVs (D9).
@Composable
fun PairingScreen(state: SetupState.PairingNeeded, extras: SetupExtras, actions: SetupActions, modifier: Modifier = Modifier) {
    var steps by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(Glow.Tv, modifier, topBar = { CytTopBar(onBack = { actions.back() }) }) {
        // "Show me" opens a sheet, "Back" goes back: neither acts on the TV, so neither is guarded.
        // 0.4.6: "Report a problem" opens the GitHub form with how the TV was found (no TV read yet).
        val title = stringResource(R.string.pairing_title)
        StopCard(
            title, stringResource(R.string.pairing_body), Modifier.padding(top = 20.dp),
            primaryLabel = stringResource(R.string.pairing_show_steps), onPrimary = { steps = true },
            secondaryLabel = stringResource(R.string.action_back), onSecondary = { actions.back() },
            detailsLabel = stringResource(R.string.connect_report), onDetails = { actions.report(title) },
            primaryGuarded = false,
        )
    }
    if (steps) CytBottomSheet({ steps = false }) { PairingStepsContent() }
}

@Composable
internal fun PairingStepsContent() {
    SheetHeader(stringResource(R.string.pairing_steps_title))
    Body(stringResource(R.string.pairing_steps_intro), Modifier.padding(top = 12.dp))
    MonospaceBlock(
        listOf(stringResource(R.string.pairing_steps_cmd_pair), stringResource(R.string.pairing_steps_cmd_connect), stringResource(R.string.pairing_steps_cmd_tcpip)),
        Modifier.padding(top = 16.dp),
    )
    Body(stringResource(R.string.pairing_steps_note), Modifier.padding(top = 16.dp))
}

// S1c: numeric field, RFC 1918 only.
@Composable
fun AddressSheet(error: Boolean, actions: SetupActions) {
    var text by rememberSaveable { mutableStateOf("") }
    CytBottomSheet({ actions.back() }) {
        AddressContent(text, { text = it.filter { c -> c.isDigit() || c == '.' }.take(15) }, error, onConnect = { actions.intent(SetupIntent.EnterAddress(text)) })
    }
}

@Composable
internal fun AddressContent(text: String, onText: (String) -> Unit, error: Boolean, onConnect: () -> Unit) {
    SheetHeader(stringResource(R.string.address_title), subtitle = stringResource(R.string.address_body))
    CytTextField(
        text, onText, stringResource(R.string.address_field_label), Modifier.padding(top = 16.dp),
        error = if (error) stringResource(R.string.address_error) else null, keyboardType = KeyboardType.Decimal,
    )
    PrimaryButton(stringResource(R.string.address_connect), onConnect, Modifier.padding(top = 16.dp), enabled = text.count { it == '.' } == 3)
}
