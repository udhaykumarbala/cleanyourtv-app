package tv.cleanyour.core.flows

import java.time.Instant
import java.time.ZoneId
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.spec.WidgetsSettings
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.GlowChoice
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.LookExtras
import tv.cleanyour.core.spec.LookGates
import tv.cleanyour.core.spec.LookKeys
import tv.cleanyour.core.spec.Looks
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.Picture
import tv.cleanyour.core.spec.PictureSource
import tv.cleanyour.core.spec.StyleId
import tv.cleanyour.core.spec.Theme
import tv.cleanyour.core.spec.ThemeSource
import tv.cleanyour.core.store.Clock

// What a draft depends on besides the choice: the dock as the TV shows it (the waiting apps too)
// and the TV's other apps, the photos it can show (the kept one, one just made, the earlier look's)
// with their dims, the earlier look and what was last sent (the way back), the TV (LookTv), this
// TV's cards choice, the phone's Show launcher names, and whether the TV's own settings changed.
internal data class LookContext(
    val apps: List<AppChoice> = emptyList(),
    val others: List<AppChoice> = emptyList(),
    val mine: LookPicture.Of? = null,
    val new: LookPicture.Of? = null,
    val earlier: EarlierLook.Saved? = null,
    val sent: HomeChoice? = null,
    val tv: LookTv = LookTv(),
    val cards: Boolean = Cards.MANAGE_DEFAULT,
    val keyNames: Boolean = false,
    val tvChanged: Boolean = false,
    // The dock a send pushes (the saved one, which can still hold an app the TV no longer shows),
    // null for the dock as shown. The bytes, so the preview, the settings file and Copy, come from it.
    val pushDock: List<PackageName>? = null,
    // 0.4.7: the dock is the person's own or from before 0.4.7 (HomeChoice.dockChosen), so the bytes
    // keep fill_dock false; false, a dock the app picked, lets the TV fill free spaces (Look.docOf).
    val dockChosen: Boolean = true,
    // 0.5.0: the TV's Widgets as its file has them, for the previews (LookPreview.withWidgets); null draws the look's own.
    val widgets: WidgetsSettings? = null,
) {
    val dock: List<PackageName> get() = pushDock ?: apps.map { it.pkg }
}

// RFC-LOOK-SETUP-1 (0.4.0) and look2: the Look choice as both the Look sheet (HomeScreenMachine)
// and Setup's look step (SetupMachine) make it, so the same choice gives the same draft, the same
// preview and the same bytes wherever it is made. `edit` is the only place a LookDraft changes.
//
// look2 rules (spec 6 to 8, the prototype's rowsOf, chooseStyle and autoPick): a style change puts
// the style's own details back and says which ones changed (Keep mine puts back those that still
// apply); a picture change puts the picture's dim and sky back; names, movement and apps in a row
// belong to nobody. "Use {style}'s own settings" puts every detail back, with Movement the TV's
// default. Undo changes goes back to what was last sent; the earlier look fills the draft. Nothing
// here sends anything. 0.4.5: the home screen 3.0 switches (LookExtras) belong to nobody, like
// names: a style change keeps them (the rules take what acts in the new style); "Use {style}'s own
// settings" puts them back to the launcher's own; Undo changes and the earlier look carry them.
internal class LookDrafts(
    private val themes: ThemeSource,
    private val pictures: PictureSource,
    private val encoder: ConfigEncoder,
    private val clock: Clock,
) {
    fun theme(id: String?): Theme? = id?.let { themes.bundled().firstOrNull { t -> t.id == it } }

    // The look's own apps in a row.
    fun ownColumns(themeId: String?): Int = DockLayers.columnsOf(theme(themeId)?.doc)

    // The apps in a row to keep and send for `columns`: null when it is the look's own or not offered.
    fun override(themeId: String?, columns: Int?): Int? = columns?.takeIf { it in OFFERED && it != ownColumns(themeId) }

    // The gallery of the Picture row: the pictures offered today (a dated one up to its last day).
    fun pictureOptions(): List<PictureOption> {
        val today = clock.today()
        return gallery().filter { Looks.offered(it, today) }
            .map { PictureOption(it.id, it.name, it.wallpaper.path.takeIf { p -> p.startsWith("/") }, it.until, it.backgroundArgb) }
    }

    // The looks on offer today (a dated look up to its last day), with their own pictures as files.
    fun offered(): List<ThemeOption> {
        val today = clock.today()
        return themes.bundled().filter { Looks.offered(it.id, today) }
            .map { ThemeOption(it.id, it.name, it.description, it.wallpaper?.path?.takeIf { p -> p.startsWith("/") }) }
    }

    fun gallery(): List<Picture> = runCatching { pictures.pictures() }.getOrNull().orEmpty()

    private fun galleryPicture(id: String?): Picture? = id?.let { gallery().firstOrNull { p -> p.id == it } }

    // A dated picture (Diwali).
    fun datedPicture(id: String?): Boolean = galleryPicture(id)?.until != null

    // Section: a choice to its parts.

    // The parts a saved choice opens with. null: a TV this phone never customised (Classic, no
    // picture, 4 apps in a row, Setup's Default look). A legacy choice opens from the migration
    // table (spec 8.2), its picture and apps in a row kept; its Movement counts as unchosen. A dated
    // picture that has ended gives back the picture before it. `autoPick`: fill an unchosen Movement
    // for this TV's memory (spec 8.1). A style this TV cannot show is Classic.
    fun partsOf(choice: HomeChoice?, tv: LookTv, autoPick: Boolean = true): LookParts {
        val today = clock.today()
        var chosen = true
        val p = when {
            choice == null -> { chosen = false; LookParts(StyleId.CLASSIC, LookPicture.NONE_ID, DockLayers.DEFAULT_COLUMNS) }
            !choice.isNew -> {
                chosen = false
                val id = choice.lookOn(today)?.takeIf { it in LegacyLooks.table } ?: HomeScreenMachine.DEFAULT_LOOK
                val e = LegacyLooks.of(id)
                val picture = choice.picture.takeUnless { Looks.dated(id) || it == LookPicture.NEW_ID } ?: e.picture
                LookParts(e.style, picture, choice.columns?.takeIf { it in OFFERED } ?: e.columns, false, e.movement, e.lift, e.details,
                    legacyThemeId = id)
            }
            else -> {
                val movement = Movement.of(choice.movement)
                if (movement == null) chosen = false
                val picture = (choice.picture ?: LookPicture.NONE_ID).let { id ->
                    if (galleryPicture(id)?.let { !Looks.offered(it, today) } == true) choice.previousPicture ?: LookPicture.NONE_ID else id
                }
                LookParts(StyleId.of(choice.style) ?: StyleId.CLASSIC, picture, choice.columns?.takeIf { it in OFFERED } ?: DockLayers.DEFAULT_COLUMNS,
                    choice.names ?: false, movement ?: Movement.LIVELY, choice.lift ?: false, choice.details, extras = choice.extras)
            }
        }
        val styled = if (p.style in LookGates.styles(tv.versionCode)) p
            else p.copy(style = StyleId.CLASSIC, details = LookRules.withoutStyleDetails(p.details))
        // 0.4.5: a TV below home screen 3.0 has none of its switches (they come back with the update).
        val gated = if (LookGates.reads30(tv.versionCode)) styled else styled.copy(extras = LookExtras.DEFAULT)
        return if (autoPick && !chosen) LookRules.autoPick(gated, tv.ramMb) else gated
    }

    private fun partsOf(d: LookDraft) = LookParts(d.style, d.picture ?: LookPicture.NONE_ID, d.columns, d.names, d.movement, d.lift, d.details,
        d.movementPicked, d.legacyThemeId, d.extras)

    // A picture id as the rules and the push see it; an id this phone cannot show is no picture.
    fun pictureOf(id: String, ctx: LookContext): LookPictureInfo {
        fun photo(of: LookPicture.Of?) = of?.let { LookPictureInfo(id, it.ref, ownDim = (it.dim ?: OwnPicture.DEFAULT_DIM).coerceIn(LookRules.DIM)) }
        return when (id) {
            LookPicture.NONE_ID -> null
            LookPicture.MINE_ID -> photo(ctx.mine)
            LookPicture.NEW_ID -> photo(ctx.new)
            LookPicture.EARLIER_ID -> photo(ctx.earlier?.photo)
            else -> galleryPicture(id)?.let {
                LookPictureInfo(it.id, it.wallpaper, it.backgroundArgb, 0, it.sky, it.festival, it.until, it.accentColor)
            }
        } ?: LookPictureInfo.NONE
    }

    // Section: the draft.

    private var base: LookDraft? = null      // the draft as it opened: what was last sent (changed, Undo changes)
    private var pending: Pending? = null     // the note's action (Keep mine, Bring them back, Undo)
    private var notes = 0L

    private class Pending(val id: Long, val restore: (LookParts) -> LookParts)

    // The draft for a saved choice (OpenLook, Setup's look step), and the base Undo changes goes back to.
    fun open(saved: HomeChoice?, ctx: LookContext): LookDraft {
        base = null
        pending = null
        val d = fill(partsOf(saved, ctx.tv), ctx, null)
        base = d
        return d
    }

    // The same draft with what it depends on made again (a new photo staged, the apps loaded, a
    // kept draft reopened after a failed send).
    fun refresh(draft: LookDraft, ctx: LookContext): LookDraft = fill(partsOf(draft), ctx, draft.note)

    fun edit(draft: LookDraft, edit: LookEdit, given: LookContext): LookDraft {
        val ctx = if (edit is LookEdit.SetKeyNames) given.copy(keyNames = edit.on) else given
        val p = partsOf(draft)
        val pic = pictureOf(p.picture, ctx)
        val rows = LookRules.rows(p, pic, ctx.dock.size, ctx.tv)
        var note = draft.note
        fun touched(next: LookParts) = if (next == p) p else next.copy(legacyThemeId = null)
        fun detail(enabled: Boolean, change: LookDetails.() -> LookDetails) = if (enabled) touched(p.copy(details = p.details.change())) else p
        fun noted(kind: LookNoteKind, restore: ((LookParts) -> LookParts)?, style: StyleId? = null,
            own: List<DetailWord> = emptyList(), dropped: List<DetailWord> = emptyList()) {
            val id = ++notes
            note = LookNote(id, kind, style, own, dropped, action = restore != null)
            pending = restore?.let { Pending(id, it) }
        }
        val next: LookParts = when (edit) {
            is LookEdit.SelectStyle -> chooseStyle(p, rows, edit.style, ctx) { n, keep -> noted(LookNoteKind.STYLE_CHANGED, keep, n.style, n.own, n.dropped) }
            is LookEdit.SelectPicture -> if (edit.id != p.picture && available(edit.id, ctx))
                touched(p.copy(picture = edit.id, details = LookRules.withoutPictureDetails(p.details))) else p
            is LookEdit.SelectColumns -> if (edit.count in OFFERED) touched(p.copy(columns = edit.count)) else p
            is LookEdit.SetNames -> if (rows.names.enabled) touched(p.copy(names = edit.on)) else p
            is LookEdit.SetMovement -> touched(p.copy(movement = edit.movement, movementPicked = false))
            is LookEdit.SetLift -> if (rows.lift.enabled || !edit.on) touched(p.copy(lift = edit.on)) else p
            is LookEdit.SetBigName -> detail(rows.bigName.enabled) { copy(bigName = edit.on) }
            is LookEdit.SetGlow -> detail(rows.glow.enabled) { copy(glow = edit.glow) }
            is LookEdit.SetSpotColour -> detail(rows.spotColour.enabled) { copy(spotColour = edit.on) }
            is LookEdit.SetRing -> detail(rows.ring.enabled) { copy(ring = edit.on) }
            is LookEdit.SetBand -> detail(rows.band.enabled) { copy(band = edit.band) }
            is LookEdit.SetSeeThrough -> detail(rows.seeThrough.enabled && edit.percent in LookKeys.SEE_THROUGH) { copy(seeThrough = edit.percent) }
            is LookEdit.SetButtons -> detail(rows.buttons.enabled) { copy(buttons = edit.buttons) }
            is LookEdit.SetGreenWifi -> detail(rows.greenWifi.enabled) { copy(greenWifi = edit.on) }
            is LookEdit.SetDim -> detail(rows.dim.enabled) { copy(dim = edit.percent.coerceIn(LookRules.DIM)) }
            is LookEdit.SetSky -> detail(rows.sky.enabled) { copy(sky = edit.on) }
            is LookEdit.SetSkyStrength -> detail(rows.skyStrength.enabled) { copy(skyStrength = edit.percent.coerceIn(LookRules.SKY_STRENGTH)) }
            // The photo tint line's "Turn it off": the glow goes, the style stays.
            LookEdit.TurnGlowOff -> if (p.style == StyleId.SPOTLIGHT) detail(rows.spotColour.enabled) { copy(spotColour = false) }
                else detail(rows.glow.enabled) { copy(glow = GlowChoice.NONE) }
            LookEdit.ResetDetails -> if (rows.resettable == 0) p else {
                val before = p
                noted(LookNoteKind.RESET, { before })
                touched(p.copy(details = LookDetails(), movement = LookGates.defaultMovement(ctx.tv.ramMb), lift = false, movementPicked = false,
                    extras = LookExtras.DEFAULT))
            }
            LookEdit.UndoChanges -> base?.takeIf { draft.changed }?.let { b ->
                val before = p
                noted(LookNoteKind.UNDONE, { before })
                partsOf(b)
            } ?: p
            LookEdit.PutBackEarlier -> ctx.earlier?.let { e ->
                noted(LookNoteKind.EARLIER_CHOSEN, null)
                earlierParts(e, ctx)
            } ?: p
            is LookEdit.NoteAction -> pending?.takeIf { it.id == edit.id }?.let { a ->
                pending = null
                note = null
                touched(a.restore(p))
            } ?: p
            is LookEdit.NoteSeen -> {
                if (note?.id == edit.id) note = null
                if (pending?.id == edit.id) pending = null
                p
            }
            is LookEdit.SetKeyNames -> p
            is LookEdit.SetExtras -> if (LookGates.reads30(ctx.tv.versionCode)) touched(p.copy(extras = edit.extras)) else p
            is LookEdit.SetBandBack -> if (rows.band.enabled) touched(p.copy(details = p.details.copy(band = edit.band), extras = p.extras.copy(noBand = false))) else p
        }
        if (next == p && note == draft.note && ctx.keyNames == draft.keyNames) return draft
        return fill(next, ctx, note)
    }

    // 0.4.0, removed after look2 merges: a look tap of the 0.4.0 looks list (SelectLook, SetTheme),
    // the draft that look migrates to (its style, picture, apps in a row, movement and details).
    fun selectLegacy(draft: LookDraft, themeId: String, ctx: LookContext): LookDraft =
        if (themeId == draft.legacyThemeId) draft else fill(partsOf(HomeChoice(emptyList(), themeId), ctx.tv).copy(names = draft.names), ctx, null)

    // 0.4.0, removed after look2 merges: the picture of a 0.4.0 SelectPicture (null: the look's own).
    fun legacyPicture(draft: LookDraft, id: String?): String = id ?: LegacyLooks.of(draft.legacyThemeId).picture

    // The doc and the wallpaper sending the draft pushes, with the saved dock.
    fun docOf(draft: LookDraft, dock: List<PackageName>, cards: Boolean, ctx: LookContext): Pair<LauncherConfigDoc, BlobRef?> {
        val p = partsOf(draft)
        val pic = pictureOf(p.picture, ctx)
        return Look.docOf(p.copy(picture = pic.id), pic, dock, cards, ctx.tv, fillDock = !ctx.dockChosen)
    }

    // A style change (the prototype's chooseStyle): the new style's own details; the note names the
    // rows the person had changed, `own` the ones the new style has its own of (and Keep mine puts
    // back those that still apply in it), `dropped` the ones it has no choice for.
    private class StyleNote(val style: StyleId, val own: List<DetailWord>, val dropped: List<DetailWord>)

    private fun chooseStyle(p: LookParts, rows: LookRows, style: StyleId, ctx: LookContext,
        note: (StyleNote, ((LookParts) -> LookParts)?) -> Unit): LookParts {
        if (style == p.style || style !in LookGates.styles(ctx.tv.versionCode)) return p
        val own = LookKeys.own(p.style)
        val d = p.details
        val lost = buildList {
            if (rows.bigName.enabled && d.bigName != null && d.bigName != own.bigName) add(BIG_NAME)
            if (rows.glow.enabled && d.glow != null && d.glow != own.glow) add(GLOW)
            if (rows.spotColour.enabled && d.spotColour != null && d.spotColour != own.spotColour) add(SPOT_COLOUR)
            if (rows.ring.enabled && d.ring != null && d.ring != own.ring) add(RING)
            if (rows.band.enabled && d.band != null && d.band != own.band) add(BAND)
            if (rows.buttons.enabled && d.buttons != null && d.buttons != own.buttons) add(BUTTONS)
            if (rows.greenWifi.enabled && d.greenWifi != null && d.greenWifi != own.greenWifi) add(GREEN_WIFI)
            if (rows.seeThrough.enabled && d.seeThrough != null && d.seeThrough != LookKeys.SEE_THROUGH_DEFAULT) add(SEE_THROUGH)
        }
        val next = p.copy(style = style, details = LookRules.withoutStyleDetails(d), legacyThemeId = null)
        if (lost.isEmpty()) return next
        val probe = next.copy(details = restore(next.details, d, lost))
        val r1 = LookRules.rows(probe, pictureOf(probe.picture, ctx), ctx.dock.size, ctx.tv)
        val enabled = mapOf(BIG_NAME to r1.bigName.enabled, GLOW to r1.glow.enabled, SPOT_COLOUR to r1.spotColour.enabled,
            RING to r1.ring.enabled, BAND to r1.band.enabled, BUTTONS to r1.buttons.enabled, GREEN_WIFI to r1.greenWifi.enabled,
            SEE_THROUGH to r1.seeThrough.enabled)
        val keep = lost.filter { enabled[it] == true }
        val ownOf = lost.filter { it !in keep && (it == GLOW || it == SPOT_COLOUR) }
        val dropped = lost.filter { it !in keep && it !in ownOf }
        fun words(fields: List<String>) = fields.map(LookRules::word).distinct()
        note(StyleNote(style, words(keep + ownOf), words(dropped)),
            if (keep.isEmpty()) null else { cur -> cur.copy(details = restore(cur.details, d, keep)) })
        return next
    }

    private fun restore(into: LookDetails, from: LookDetails, fields: List<String>): LookDetails = fields.fold(into) { acc, f ->
        when (f) {
            BIG_NAME -> acc.copy(bigName = from.bigName)
            GLOW -> acc.copy(glow = from.glow)
            SPOT_COLOUR -> acc.copy(spotColour = from.spotColour)
            RING -> acc.copy(ring = from.ring)
            BAND -> acc.copy(band = from.band)
            BUTTONS -> acc.copy(buttons = from.buttons)
            GREEN_WIFI -> acc.copy(greenWifi = from.greenWifi)
            else -> acc.copy(seeThrough = from.seeThrough)
        }
    }

    // The earlier look as a draft: its photo comes back as EARLIER_ID unless it is still the kept
    // photo. Its Movement was chosen, so nothing is auto picked.
    private fun earlierParts(e: EarlierLook.Saved, ctx: LookContext): LookParts {
        val p = partsOf(e.choice, ctx.tv, autoPick = false).copy(movementPicked = false)
        if (p.picture != LookPicture.MINE_ID) return p
        val photo = e.photo ?: return p.copy(picture = LookPicture.NONE_ID)
        val mine = ctx.mine
        return if (mine != null && mine.ref.sha256 == photo.ref.sha256 && mine.dim == photo.dim) p else p.copy(picture = LookPicture.EARLIER_ID)
    }

    private fun available(id: String, ctx: LookContext): Boolean = when (id) {
        LookPicture.NONE_ID -> true
        LookPicture.MINE_ID -> ctx.mine != null
        LookPicture.NEW_ID -> ctx.new != null
        LookPicture.EARLIER_ID -> ctx.earlier?.photo != null
        else -> galleryPicture(id)?.let { Looks.offered(it, clock.today()) } == true
    }

    // Every derived field of the draft, from its parts: the rows, the bytes, the preview and the
    // five style cards (each with its style's own details, this TV, this picture), the way back.
    private fun fill(given: LookParts, ctx: LookContext, note: LookNote?): LookDraft {
        val pic = pictureOf(given.picture, ctx)
        val p = given.copy(picture = pic.id)
        val tv = ctx.tv
        val styles = LookGates.styles(tv.versionCode)
        val rows = LookRules.rows(p, pic, ctx.dock.size, tv)
        val dock = ctx.dock
        val (doc, wallpaper) = Look.docOf(p, pic, dock, ctx.cards, tv, fillDock = !ctx.dockChosen)
        val bytes = encoder.encode(doc)
        val cards = StyleId.entries.map { s ->
            val card = p.copy(style = s, details = LookRules.withoutStyleDetails(p.details))
            val (cd, cw) = Look.docOf(card, pic, dock, ctx.cards, tv, fillDock = !ctx.dockChosen)
            StyleCard(s, previewOf(runCatching { encoder.encode(cd) }.getOrNull(), cw, ctx), s in styles)
        }
        val b = base
        val full = bytes.decodeToString()
        val changed = b != null && (full != b.fullFile || p.picture != b.picture)
        val earlier = ctx.earlier
        val wayBack = when {
            changed -> WayBack.UNDO
            earlier != null && EarlierLook.differs(earlier, ctx.sent, ctx.mine) -> WayBack.EARLIER
            else -> WayBack.NONE
        }
        val legacy = p.legacyThemeId
        return LookDraft(
            themeId = legacy ?: HomeScreenMachine.DEFAULT_LOOK, picture = p.picture, ownPictureOnly = false,
            preview = previewOf(bytes, wallpaper, ctx), columns = p.columns,
            lookColumns = legacy?.let { LegacyLooks.of(it).columns } ?: p.columns,
            style = p.style, names = p.names, movement = p.movement, movementPicked = p.movementPicked, lift = p.lift, details = p.details,
            legacyThemeId = legacy, styleCards = cards, rows = rows, changed = changed,
            newPhoto = p.picture == LookPicture.NEW_ID || p.picture == LookPicture.EARLIER_ID,
            wayBack = wayBack, note = note, styles = styles, reads27 = LookGates.reads27(tv.versionCode), ramMb = tv.ramMb,
            keyNames = ctx.keyNames, settingsFile = encoder.encode(doc.copy(dockApps = null, moduleCards = null)).decodeToString(),
            fullFile = full, apps = ctx.apps.size, waiting = DockLayers.waiting(ctx.apps.size, p.columns),
            tvChanged = ctx.tvChanged,
            reads30 = LookGates.reads30(tv.versionCode),
            extras = if (LookGates.reads30(tv.versionCode)) p.extras else LookExtras.DEFAULT,
            actingExtras = LookRules.extras(p, rows, tv),
            offeredExtras = LookRules.extras(p.copy(extras = ALL_ON), rows, tv),
            unsent = ctx.sent == null,
        )
    }

    // The preview from the bytes the push would send (cards as they are do not change the picture).
    private fun previewOf(bytes: ByteArray?, wallpaper: BlobRef?, ctx: LookContext): LookPreview? = bytes?.let {
        runCatching {
            val now = Instant.ofEpochMilli(clock.nowMs()).atZone(ZoneId.systemDefault())
            LookPreview.of(it, wallpaper, ctx.apps, ctx.others, now.toLocalDate(), now.hour * 60 + now.minute, ctx.tv.hasInputs)
                .let { p -> ctx.widgets?.let { w -> p.withWidgets(w) } ?: p }
        }.getOrNull()
    }

    // Section: the draft to a saved choice, and a saved choice to its bytes.

    // The choice a send saves, in the new form, with `columns` always: `picture` is what the TV
    // shows after it (a photo made in the sheet or the earlier photo is "mine" once kept), `dim`
    // its photo's dim. A dated picture keeps the picture before it (previousPicture).
    fun choiceOf(draft: LookDraft, saved: HomeChoice?, dock: List<PackageName>, cards: Boolean?, picture: String, dim: Int?,
        dockChosen: Boolean = true): HomeChoice =
        HomeChoice(dock, themeId = null, cards = cards, picture = picture, dim = dim, columns = draft.columns,
            style = draft.style.id, movement = draft.movement.id, lift = draft.lift, names = draft.names, details = draft.details,
            previousPicture = previousPicture(saved, picture), extras = draft.extras, dockChosen = dockChosen)

    private fun previousPicture(saved: HomeChoice?, next: String): String? {
        if (!datedPicture(next)) return null
        val current = saved?.let { if (it.isNew) it.picture else partsOf(it, LookTv(), autoPick = false).picture }
        val earlier = saved?.previousPicture
        return when {
            current == next -> earlier
            datedPicture(current) -> earlier
            else -> current
        }
    }

    // A saved choice's doc and wallpaper, as every push of it sends them (Restore mine, a dock
    // change, the cards push): a legacy choice through the frozen 0.4.0 path, byte for byte; one in
    // the new form through its rows on this TV. `mine`: the photo kept for this TV.
    fun docOf(choice: HomeChoice, dock: List<PackageName>, cards: Boolean, mine: BlobRef?, tv: LookTv): Pair<LauncherConfigDoc, BlobRef?> {
        if (!choice.isNew) {
            val theme = theme(choice.lookOn(clock.today()))
            val picture = if (choice.picture == LookPicture.MINE_ID) mine?.let { OwnPicture.mine(it, choice.dim) } else LookPicture.of(choice.picture, pictures)
            return Look.docOf(theme, dock, cards, picture, override(theme?.id, choice.columns), fillDock = !choice.dockChosen)
        }
        val ctx = LookContext(mine = mine?.let { OwnPicture.mine(it, choice.dim) }, tv = tv)
        val p = partsOf(choice, tv)
        val pic = pictureOf(p.picture, ctx)
        return Look.docOf(p.copy(picture = pic.id), pic, dock, cards, tv, fillDock = !choice.dockChosen)
    }

    // A saved choice's preview (the Home page's Look row) from the bytes it sends.
    fun previewOf(choice: HomeChoice, dock: List<AppChoice>, others: List<AppChoice>, cards: Boolean, mine: BlobRef?, tv: LookTv,
        widgets: WidgetsSettings? = null): LookPreview? =
        runCatching { docOf(choice, dock.map { it.pkg }, cards, mine, tv) }.getOrNull()?.let { (doc, wallpaper) ->
            previewOf(runCatching { encoder.encode(doc) }.getOrNull(), wallpaper, LookContext(dock, others, tv = tv, widgets = widgets))
        }

    // A saved choice's style and picture as the Home page's Look row names them.
    fun styleOf(choice: HomeChoice?, tv: LookTv): StyleId? = choice?.let { partsOf(it, tv, autoPick = false).style }
    fun pictureIdOf(choice: HomeChoice?, tv: LookTv): String? = choice?.let { partsOf(it, tv, autoPick = false).picture }

    companion object {
        val OFFERED = DockLayers.MIN_OFFERED_COLUMNS..DockLayers.MAX_OFFERED_COLUMNS

        // Every 3.0 switch on, for offeredExtras.
        private val ALL_ON = LookExtras(oneRow = true, bandAll = true, noBand = true, hideGreeting = true, dateBelow = true, brightDate = true,
            wifiWarnings = true, tiles = tv.cleanyour.core.spec.TilesChoice.ICONS, highlight = tv.cleanyour.core.spec.HighlightChoice.RIM)

        private const val BIG_NAME = "bigName"
        private const val GLOW = "glow"
        private const val SPOT_COLOUR = "spotColour"
        private const val RING = "ring"
        private const val BAND = "band"
        private const val BUTTONS = "buttons"
        private const val GREEN_WIFI = "greenWifi"
        private const val SEE_THROUGH = "seeThrough"
    }
}
