package tv.cleanyour.ui.design.component

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.core.flows.DetailWord
import tv.cleanyour.core.flows.LookDetails
import tv.cleanyour.core.flows.LookEdit
import tv.cleanyour.core.flows.LookNote
import tv.cleanyour.core.flows.LookNoteKind
import tv.cleanyour.core.flows.LookPreview
import tv.cleanyour.core.flows.LookRows
import tv.cleanyour.core.flows.StyleCard
import tv.cleanyour.core.flows.WayBack
import tv.cleanyour.core.spec.LookExtras
import tv.cleanyour.core.spec.LookGates
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.ui.design.R
import tv.cleanyour.ui.design.icon.CytIcons
import tv.cleanyour.ui.design.theme.CytTheme

// look2 Look editor (spec W2 to W6, W10 to W12; the owner's prototype): the Look sheet's three
// pages, shared by the Look sheet after Setup (footer "Put it on the TV" and the way back) and
// Setup's look step (footer Done), so both show the same thing in the same order with the same
// words. Look: Picture, Style, Apps in a row, Names under every app and the More options link;
// More options: Movement, Big name, Glow and dock, Top of the screen, Picture and "Use {style}'s
// own settings"; Advanced: Lift and zoom, the finer amounts and the settings file. The preview of
// the TV is pinned above the pages and drawn from the bytes the push would send. Every tap is a
// LookEdit for the caller's machine, and none of them sends: only the caller's footer does. Both
// callers make the state with the one mapping from the flows' Look choice (LookEditorFrom.kt).

enum class LookPage { MAIN, MORE, ADVANCED }

// A gallery picture of the Picture strip: its file on this phone (null when it has none), the day
// a dated one ends ("Until 10 Nov") and the colour the TV shows around it.
@Immutable
data class LookEditorPicture(val id: String, val name: String, val path: String?, val until: LocalDate? = null,
    val backgroundArgb: Int = 0xFF000000.toInt())

// What the editor shows, in the flows' own words (LookEditorFrom.kt makes it from LookDraft).
// `picture`: NONE, MINE, NEW, EARLIER or a gallery id. `apps`: the dock as the TV shows it plus
// the apps waiting for more apps in a row, `waiting` how many of them wait at `columns`.
// `minePath` the person's photo kept for this TV, `newPath` a photo just made here and not on the
// TV yet, `earlierPath` the earlier look's photo; `photoDim` the dim the crop starts from.
@Immutable
data class LookEditorState(
    val style: StyleId,
    val picture: String,
    val columns: Int,
    val preview: LookPreview? = null,
    val styles: List<StyleId> = StyleId.entries,
    val styleCards: List<StyleCard> = emptyList(),
    val reads27: Boolean = true,
    val pictures: List<LookEditorPicture> = emptyList(),
    val minePath: String? = null,
    val newPath: String? = null,
    val earlierPath: String? = null,
    val photoDim: Int = 30,
    val apps: Int = 0,
    val waiting: Int = 0,
    val minColumns: Int = 4,
    val maxColumns: Int = 8,
    val names: Boolean = false,
    val movement: Movement = Movement.LIVELY,
    val movementPicked: Boolean = false,
    val lift: Boolean = false,
    val details: LookDetails = LookDetails(),
    val rows: LookRows? = null,
    val changed: Boolean = false,
    val newPhoto: Boolean = false,
    val wayBack: WayBack = WayBack.NONE,
    val note: LookNote? = null,
    val ramMb: Int? = null,
    val keyNames: Boolean = false,
    val settingsFile: String = "",
    val fullFile: String = "",
    val tvChanged: Boolean = false,
    // Home screen 3.0: its rows show only with `reads30`; `extras` the person's choice of them.
    val reads30: Boolean = false,
    val extras: LookExtras = LookExtras.DEFAULT,
    // What of `extras` acts in this look, and what would act if it were on (LookDraft's, from LookRules).
    val actingExtras: LookExtras = LookExtras.DEFAULT,
    val offeredExtras: LookExtras = LookExtras.DEFAULT,
) {
    // A photo of the person's (kept, new or the earlier look's) rather than a gallery picture.
    val photo: Boolean get() = picture == MINE || picture == NEW || picture == EARLIER

    companion object {
        // The picture keys the flows use (LookPicture.NONE_ID, MINE_ID, NEW_ID, EARLIER_ID).
        const val NONE = "none"
        const val MINE = "mine"
        const val NEW = "new"
        const val EARLIER = "earlier"
    }
}

// Where the editor is: the page, the leave question and the preview's size on levels 2 and 3.
// Back (the header arrow, system Back and the predictive back gesture alike) goes up one page,
// then asks before leaving while something is not on the TV yet (W12), then lets the sheet close.
@Stable
class LookEditorNav {
    var page by mutableStateOf(LookPage.MAIN)
        private set
    var asking by mutableStateOf(false)
    var big by mutableStateOf(false)
    internal var unsent: () -> Boolean = { false }

    // One step back; true when the sheet may close now.
    fun back(): Boolean {
        when {
            asking -> asking = false
            page == LookPage.ADVANCED -> open(LookPage.MORE)
            page == LookPage.MORE -> open(LookPage.MAIN)
            else -> return close()
        }
        return false
    }

    // Close, swipe or a tap on the scrim: asks first while something is not on the TV yet.
    fun close(): Boolean {
        if (asking) return false
        if (unsent()) {
            asking = true
            return false
        }
        return true
    }

    fun open(p: LookPage) {
        page = p
        big = false
    }
}

@Composable
fun rememberLookEditorNav(): LookEditorNav = remember { LookEditorNav() }

// The editor in its own modal sheet: a swipe down, a tap on the scrim and system Back all reach
// LookEditorNav first, so Back walks the pages and anything unsent asks before the sheet closes.
// The swipe works on the Look page only. Setup passes `asksBeforeLeaving` false, and `photoAfter`,
// the photo crop's line on when the photo goes to the TV, in its own words.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookEditorSheet(
    state: LookEditorState,
    onEdit: (LookEdit) -> Unit,
    onPhoto: (ByteArray, Int) -> Unit,
    onClose: () -> Unit,
    footer: @Composable ColumnScope.(LookPage) -> Unit,
    photos: PhotoPicker? = null,
    pictures: Map<String, ImageBitmap>? = null,
    onUpdateHomeScreen: (() -> Unit)? = null,
    asksBeforeLeaving: Boolean = true,
    photoAfter: String? = null,
) {
    val nav = rememberLookEditorNav()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { v -> v != SheetValue.Hidden || nav.back() })
    CytBottomSheet(onClose, sheetState = sheet, sheetGesturesEnabled = nav.page == LookPage.MAIN && !nav.asking) {
        LookEditor(state, onEdit, onPhoto, onClose, footer, photos, pictures, onUpdateHomeScreen, nav = nav, asksBeforeLeaving = asksBeforeLeaving,
            photoAfter = photoAfter)
    }
}

// The editor itself: the header, the pinned preview, the page, the caller's footer, the snackbars
// of the flows' notes and the leave question. `onPhoto` gets the JPEG the crop made and its dim.
// `photos`: the system photo picker ("Choose a photo" is offered only with one). `pictures`: the
// pictures by file, given by the screenshot tests (the app reads the files). `onUpdateHomeScreen`:
// the older home screen banner's Update (none in Setup). `asksBeforeLeaving` false: closing loses
// nothing (Setup keeps the choice until it runs), so there is no leave question.
@Composable
fun LookEditor(
    state: LookEditorState,
    onEdit: (LookEdit) -> Unit,
    onPhoto: (ByteArray, Int) -> Unit,
    onClose: () -> Unit,
    footer: @Composable ColumnScope.(LookPage) -> Unit,
    photos: PhotoPicker? = null,
    pictures: Map<String, ImageBitmap>? = null,
    onUpdateHomeScreen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    nav: LookEditorNav = rememberLookEditorNav(),
    asksBeforeLeaving: Boolean = true,
    photoAfter: String? = null,
) {
    val unsent by rememberUpdatedState(asksBeforeLeaving && (state.changed || state.newPhoto))
    SideEffect { nav.unsent = { unsent } }
    val edit by rememberUpdatedState(onEdit)
    val snack = rememberUndoSnackbarState()
    val scrolls = remember { LookPage.entries.associateWith { ScrollState(0) } }
    val density = LocalDensity.current
    var footerPx by remember { mutableIntStateOf(0) }
    val footerHeight = with(density) { footerPx.toDp() }
    // W3, 12.2: the TV-changes line moves into the list at large text, or once the footer passes a
    // quarter of the window. It stays there while the sheet is open, so the footer cannot bounce
    // between the two heights.
    val windowPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var tallFooter by remember { mutableStateOf(false) }
    SideEffect { if (!tallFooter && windowPx > 0f && footerPx > windowPx * FOOTER_MAX_SHARE) tallFooter = true }
    val lineInList = tallFooter || density.fontScale >= TV_LINE_IN_LIST_FONT_SCALE
    var picked by remember { mutableStateOf<Uri?>(null) }

    LookNotes(state, snack, edit, nav)

    val pageTitle = stringResource(
        when (nav.page) { LookPage.MAIN -> R.string.cyt_look_title; LookPage.MORE -> R.string.cyt_look_more_title; LookPage.ADVANCED -> R.string.cyt_look_advanced },
    )
    CompositionLocalProvider(LocalTvLineInList provides lineInList) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            // In the sheet the page scrolls between the pinned parts; with no bound (a parent that
            // scrolls, as a test may give it) the page is laid out in full and the parent scrolls.
            val bounded = constraints.hasBoundedHeight
            // While the leave question is up, the sheet behind it cannot be reached (W12).
            Column(Modifier.fillMaxWidth().then(if (nav.asking) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier)) {
                LookHeader(nav, pageTitle, onClose = { if (nav.close()) onClose() })
                val collapse = with(density) { (scrolls.getValue(LookPage.MAIN).value / 140.dp.toPx()).coerceIn(0f, 1f) }
                PinnedPreview(
                    state.preview?.toTvLook(lookPicture(state.preview.picturePath, pictures, sample = 4)), previewDescription(state),
                    collapse = collapse, mainPage = nav.page == LookPage.MAIN, big = nav.big, footer = footerHeight,
                    onToggleBig = { nav.big = !nav.big },
                    toggleLabel = stringResource(if (nav.big) R.string.cyt_look_preview_smaller_cd else R.string.cyt_look_preview_bigger_cd),
                )
                SheetPages(nav.page, { it.ordinal }, if (bounded) Modifier.weight(1f, fill = false) else Modifier) { page ->
                    val pageScroll = if (bounded) Modifier.verticalScroll(scrolls.getValue(page)) else Modifier
                    Column(Modifier.fillMaxWidth().then(pageScroll).padding(bottom = 12.dp)) {
                        when (page) {
                            LookPage.MAIN -> LookMainPage(
                                state, edit, onMore = { nav.open(LookPage.MORE) },
                                onPick = photos?.let { p -> { p.pick { picked = it } } }, pictures = pictures, onUpdateHomeScreen = onUpdateHomeScreen,
                            )
                            LookPage.MORE -> LookMorePage(state, edit, onAdvanced = { nav.open(LookPage.ADVANCED) }, onUpdateHomeScreen = onUpdateHomeScreen)
                            LookPage.ADVANCED -> LookAdvancedPage(state, edit, onCopied = { line -> snack.note(line, null, null) })
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().onSizeChanged { footerPx = it.height }.padding(top = 8.dp)) { footer(nav.page) }
            }
            UndoSnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = footerHeight + 10.dp))
            if (nav.asking) {
                LeaveQuestion(
                    photoOnly = state.newPhoto,
                    onStay = { nav.asking = false },
                    onLeave = { nav.asking = false; onClose() },
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        picked?.let { uri ->
            PhotoCropDialog(uri, state.preview?.toTvLook(null, crop = true), state.photoDim,
                onDone = { jpeg, dim -> onPhoto(jpeg, dim); picked = null }, onCancel = { picked = null }, after = photoAfter)
        }
    }
}

// The share of the window the footer may take before the TV-changes line moves into the list.
private const val FOOTER_MAX_SHARE = 0.25f

// Whether the TV-changes line sits in the Look page's list (true) or under the footer's button.
internal val LocalTvLineInList = staticCompositionLocalOf { false }

// The header: Look with More options and Close; More options with Back and Advanced; Advanced
// with Back.
@Composable
private fun LookHeader(nav: LookEditorNav, title: String, onClose: () -> Unit) {
    when (nav.page) {
        LookPage.MAIN -> SheetPageHeader(title, focusKey = nav.page) {
            CytIconButton(CytIcons.Settings, stringResource(R.string.cyt_look_more_cd), { nav.open(LookPage.MORE) })
            CytIconButton(CytIcons.Close, stringResource(R.string.cyt_close), onClose)
        }
        LookPage.MORE -> SheetPageHeader(title, onBack = { nav.back() }, backLabel = stringResource(R.string.cyt_look_back_look), focusKey = nav.page) {
            CytIconButton(SheetIcons.Advanced, stringResource(R.string.cyt_look_advanced_cd), { nav.open(LookPage.ADVANCED) })
        }
        LookPage.ADVANCED -> SheetPageHeader(title, onBack = { nav.back() }, backLabel = stringResource(R.string.cyt_look_back_more), focusKey = nav.page)
    }
}

// W12: "Leave without sending?" over the sheet, low near the thumb as in the prototype. Keep
// choosing is the primary; Leave, outlined, closes the sheet and drops what was not sent. Nothing
// here writes to the TV, so neither is guarded.
@Composable
private fun LeaveQuestion(photoOnly: Boolean, onStay: () -> Unit, onLeave: () -> Unit, modifier: Modifier = Modifier) {
    val c = CytTheme.colors
    // The scrim: a tap on it is Keep choosing; not a control of its own for TalkBack.
    Box(modifier.background(c.scrim).pointerInput(Unit) { detectTapGestures { onStay() } })
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 16.dp).fillMaxWidth().background(c.surface3, CytTheme.shapes.large).padding(20.dp)) {
            SheetHeader(stringResource(R.string.cyt_look_leave_title))
            Text(
                stringResource(if (photoOnly) R.string.cyt_look_leave_body_photo else R.string.cyt_look_leave_body),
                Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp), color = c.textSecondary, style = CytTheme.type.bodyMedium,
            )
            ActionColumn(Modifier.padding(top = 20.dp)) {
                PrimaryButton(stringResource(R.string.cyt_look_leave_stay), onStay, guardObscured = false)
                OutlinedPill(stringResource(R.string.cyt_look_leave_go), onLeave, Modifier.fillMaxWidth().heightIn(min = 52.dp))
            }
        }
    }
}

// The flows' notes as snackbars (W10, Undo changes, Use own settings, the earlier look): each is
// shown once, its action (Keep mine, Bring them back, Undo) is a NoteAction, and NoteSeen follows
// when it leaves.
@Composable
private fun LookNotes(state: LookEditorState, snack: UndoSnackbarState, edit: (LookEdit) -> Unit, nav: LookEditorNav) {
    val note = state.note ?: return
    val text = noteText(note)
    val action = when (note.kind) {
        LookNoteKind.STYLE_CHANGED -> stringResource(R.string.cyt_look_keep_mine)
        LookNoteKind.UNDONE -> stringResource(R.string.cyt_look_undo_bring_back)
        LookNoteKind.RESET -> stringResource(R.string.cyt_undo)
        LookNoteKind.EARLIER_CHOSEN -> null
    }.takeIf { note.action }
    LaunchedEffect(note.id) {
        if (note.kind == LookNoteKind.EARLIER_CHOSEN) nav.open(LookPage.MAIN)
        snack.note(text, action, if (action != null) ({ edit(LookEdit.NoteAction(note.id)) }) else null, onGone = { edit(LookEdit.NoteSeen(note.id)) })
    }
}

@Composable
private fun noteText(note: LookNote): String = when (note.kind) {
    LookNoteKind.UNDONE -> stringResource(R.string.cyt_look_undo_done)
    LookNoteKind.RESET -> stringResource(R.string.cyt_look_reset_done)
    LookNoteKind.EARLIER_CHOSEN -> stringResource(R.string.cyt_look_earlier_chosen)
    LookNoteKind.STYLE_CHANGED -> {
        val name = styleName(note.style ?: StyleId.CLASSIC)
        val own = joinWords(note.own.map { detailWord(it) })
        val dropped = joinWords(note.dropped.map { detailWord(it) })
        when {
            own.isNotEmpty() && dropped.isNotEmpty() ->
                lookMsg(R.string.cyt_look_style_own, "style" to name, "details" to own) + " " + lookMsg(R.string.cyt_look_style_none, "details" to dropped)
            own.isNotEmpty() -> lookMsg(R.string.cyt_look_style_own, "style" to name, "details" to own)
            else -> lookMsg(R.string.cyt_look_style_none_alone, "style" to name, "details" to dropped)
        }
    }
}

@Composable
internal fun detailWord(w: DetailWord): String = stringResource(
    when (w) {
        DetailWord.BUTTONS -> R.string.cyt_look_word_buttons
        DetailWord.GLOW -> R.string.cyt_look_word_glow
        DetailWord.BIG_NAME -> R.string.cyt_look_word_big_name
        DetailWord.RING -> R.string.cyt_look_word_ring
        DetailWord.DOCK_BAND -> R.string.cyt_look_word_dock_band
        DetailWord.WIFI_COLOUR -> R.string.cyt_look_word_wifi
        DetailWord.SEE_THROUGH -> R.string.cyt_look_word_see_through
    },
)

// "a", "a and b", "a, b and c".
@Composable
internal fun joinWords(words: List<String>): String {
    val w = words.distinct()
    if (w.size < 2) return w.firstOrNull().orEmpty()
    var head = w.first()
    for (next in w.subList(1, w.size - 1)) head = lookMsg(R.string.cyt_look_join, "first" to head, "next" to next)
    return lookMsg(R.string.cyt_look_join_last, "first" to head, "last" to w.last())
}

// A style's name and its line, in the app's words.
@Composable
fun styleName(style: StyleId): String = stringResource(
    when (style) {
        StyleId.CLASSIC -> R.string.cyt_look_style_classic
        StyleId.GLASS -> R.string.cyt_look_style_glass
        StyleId.SPOTLIGHT -> R.string.cyt_look_style_spotlight
        StyleId.ALL_APPS -> R.string.cyt_look_style_all_apps
        StyleId.CALM -> R.string.cyt_look_style_calm
    },
)

@Composable
fun styleDescription(style: StyleId): String = stringResource(
    when (style) {
        StyleId.CLASSIC -> R.string.cyt_look_style_classic_desc
        StyleId.GLASS -> R.string.cyt_look_style_glass_desc
        StyleId.SPOTLIGHT -> R.string.cyt_look_style_spotlight_desc
        StyleId.ALL_APPS -> R.string.cyt_look_style_all_apps_desc
        StyleId.CALM -> R.string.cyt_look_style_calm_desc
    },
)

// A gallery picture's name in the app's words, from its id (BundledThemeSource's gallery);
// `fallback`, the name the flows gave, for a picture this list does not know.
@Composable
fun galleryPictureName(id: String, fallback: String): String = when (id) {
    "diwali" -> stringResource(R.string.cyt_picture_diwali)
    "aurora" -> stringResource(R.string.cyt_picture_aurora)
    "mist" -> stringResource(R.string.cyt_picture_mist)
    "glass" -> stringResource(R.string.cyt_picture_navy)
    "ruska" -> stringResource(R.string.cyt_picture_autumn_lake)
    "curtains" -> stringResource(R.string.cyt_picture_curtains)
    "mountains" -> stringResource(R.string.cyt_picture_mountains)
    "lamp" -> stringResource(R.string.cyt_picture_lamp)
    "neon" -> stringResource(R.string.cyt_picture_neon)
    "cobalt" -> stringResource(R.string.cyt_picture_cobalt)
    else -> fallback
}

// The picture in the preview's description: "the Mist picture", "your photo" or "no picture".
@Composable
internal fun pictureWords(state: LookEditorState): String = when {
    state.picture == LookEditorState.NONE -> stringResource(R.string.cyt_look_picture_words_none)
    state.photo -> stringResource(R.string.cyt_look_picture_words_photo)
    else -> state.pictures.firstOrNull { it.id == state.picture }?.let { lookMsg(R.string.cyt_look_picture_words, "name" to galleryPictureName(it.id, it.name)) }
        ?: stringResource(R.string.cyt_look_picture_words_none)
}

// "Preview of the TV's home screen: Glass, the Mist picture, 5 apps in a row.", then the home
// screen 3.0 extras that act in this look (previewExtraWords): "..., 5 apps in a row, every app in
// one row, the date under the clock."
@Composable
internal fun previewDescription(state: LookEditorState): String {
    val extras = previewExtraWords(state)
    val style = styleName(state.style)
    val picture = pictureWords(state)
    if (extras.isEmpty()) {
        return lookMsg(
            if (state.names) R.string.cyt_look_preview_cd_names else R.string.cyt_look_preview_cd,
            "style" to style, "picture" to picture, "count" to state.columns,
        )
    }
    return lookMsg(
        if (state.names) R.string.cyt_look_preview_cd_names_more else R.string.cyt_look_preview_cd_more,
        "style" to style, "picture" to picture, "count" to state.columns, "extras" to extras.joinToString(", "),
    )
}

// Copy: puts text on the phone's clipboard (Advanced's settings file).
@Composable
internal fun rememberCopy(): (String, String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { label, text -> context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text)) }
    }
}

// The memory in the words of the Quick and Lift lines: "1.5 GB", "1 GB".
@Composable
internal fun memoryWords(ramMb: Int): String {
    val g = LookGates.halfGb(ramMb)
    val text = if (g % 1.0 == 0.0) g.toInt().toString() else g.toString()
    return lookMsg(R.string.cyt_look_memory_size, "size" to text)
}

// A picture file as a small bitmap for a thumbnail, read off the main thread; null while it loads
// or when there is none. Screenshot tests hand theirs in by path.
@Composable
fun lookPicture(path: String?, given: Map<String, ImageBitmap>? = null, sample: Int = 8): ImageBitmap? {
    if (given != null) return path?.let { given[it] }
    val bitmap by produceState<ImageBitmap?>(null, path, sample) {
        value = path?.let { p ->
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return bitmap
}

// A supporting paragraph in body.medium, text.secondary unless the caller says otherwise.
@Composable
internal fun LookBody(text: String, modifier: Modifier = Modifier, color: Color = CytTheme.colors.textSecondary) {
    Text(text, modifier, color = color, style = CytTheme.type.bodyMedium)
}

// A line TalkBack reads again when it changes (the helper lines, the tint line).
internal fun Modifier.politeLive(): Modifier = this.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }

// The look editor's copy is ICU MessageFormat with named arguments, as the screens' copy is.
@Composable
internal fun lookMsg(id: Int, vararg args: Pair<String, Any>): String {
    val pattern = stringResource(id)
    if (args.isEmpty()) return pattern
    val locale = LocalConfiguration.current.locales[0]
    return remember(pattern, locale, args.toList()) {
        android.icu.text.MessageFormat(pattern, locale).format(args.toMap())
    }
}
