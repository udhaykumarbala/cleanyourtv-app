package tv.cleanyour.core.flows

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.LauncherState
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.spec.DecodeResult
import tv.cleanyour.core.spec.DockLayers
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.LookKeys
import tv.cleanyour.core.spec.Looks
import tv.cleanyour.core.spec.PictureSource
import tv.cleanyour.core.spec.Theme
import tv.cleanyour.core.store.BlobStore

// The dock and the look as the one config.json the launcher reads: the theme's doc (or the
// launcher's defaults) with dock_apps the dock and its rows in order and rows_below_dock the rows
// under the dock that hold an app (DockLayers), encoded and stored in the BlobStore. Setup's plan
// (RFC-DOCK-1), Manage's ApplyTheme and the Modules add (RFC-CARDS-1) all push what this makes, so
// one choice is the same bytes wherever it is sent from. The wallpaper is the theme's own, when it
// has one. module_cards is always written: `cards` is this TV's cards choice (Cards).
// 0.4.0 `picture`: null keeps the look's own picture; LookPicture.None shows none (wallpaper false,
// and the push removes wallpaper.jpg); LookPicture.Of shows that one, so wallpaper is forced true
// (a look like Quiet black says false, and the picture would be pushed and not shown), the single
// picture rather than a rotation, and its dim when it has one. A dated look (a festival) keeps its own.
// 0.4.0 `columns`: the person's apps in a row (HomeChoice.columns), null for the look's own. It goes
// into the look's doc before the dock is cut (DockLayers.applyTo), so rows_below_dock and dock_title
// follow it. A dated look takes it too: apps in a row is not the picture.
// 0.4.0 fill_dock was false in every push: a dock space the person left free stays empty on the TV
// instead of taking the first app of the grid (launcher DockSlots.empty), so the dock holds only the
// apps they picked, and the Look sheet's preview draws the space as empty band.
// 0.4.7 `fillDock`: true for a dock the app picked in a Setup (HomeChoice.dockChosen false). Such a
// dock (DockRanking) often holds fewer apps than a row, and an app the TV skips
// (not installed, switched off) leaves a space too: there the launcher's own behaviour comes back,
// and the next apps fill the spaces instead of leaving the dock one app long (#1166). The default
// false keeps the frozen bytes for the tests outside the flows (LookDocs, the goldens).
// look2: a choice in the new form (HomeChoice.style) is built from scratch by LookKeys.build from
// its resolved LookSpec and PictureKeys (LookRules), spec 7.1; a legacy choice (style null) keeps
// the frozen 0.4.0 path (LegacyLook) byte for byte until the next send.
// 0.5.0 F0: `bytes`, the file as encoded, kept for the TV once the push reached it (PushedSettings).
internal class Look(val doc: LauncherConfigDoc, val digest: Sha256, val config: BlobRef, val wallpaper: BlobRef?, val bytes: ByteArray) {
    companion object {
        // RFC-HELPER-1: `carry` adds what this TV needs (LookCarry: the helper's keys, the Live TV keys).
        suspend fun encode(encoder: ConfigEncoder, blobs: BlobStore, theme: Theme?, dock: List<PackageName>, cards: Boolean, picture: LookPicture? = null,
            columns: Int? = null, carry: (LauncherConfigDoc) -> LauncherConfigDoc = { it }, fillDock: Boolean = false): Look {
            val (doc, wallpaper) = docOf(theme, dock, cards, picture, columns, fillDock)
            return encode(encoder, blobs, carry(doc), wallpaper)
        }

        // A doc and its wallpaper as the push sends them, encoded and stored.
        suspend fun encode(encoder: ConfigEncoder, blobs: BlobStore, doc: LauncherConfigDoc, wallpaper: BlobRef?): Look {
            val bytes = encoder.encode(doc)
            return Look(doc, sha256Of(bytes), blobs.putBytes(bytes), wallpaper, bytes)
        }

        // The doc and the wallpaper a 0.4.0 choice pushes, without storing anything (LegacyLook).
        fun docOf(theme: Theme?, dock: List<PackageName>, cards: Boolean, picture: LookPicture?, columns: Int? = null,
            fillDock: Boolean = false): Pair<LauncherConfigDoc, BlobRef?> =
            LegacyLook.docOf(theme, dock, cards, picture, columns, fillDock)

        // look2: the doc and the wallpaper of a choice in the new form: its rows resolved on this TV,
        // then LookKeys.build (style keys, details, picture, movement, columns, fill_dock false, the
        // dock and its rows, module_cards, and the 3.0 switches that act here, LookRules.extras), with
        // fill_dock true for a dock the app picked (`fillDock`, 0.4.7).
        fun docOf(parts: LookParts, picture: LookPictureInfo, dock: List<PackageName>, cards: Boolean, tv: LookTv,
            fillDock: Boolean = false): Pair<LauncherConfigDoc, BlobRef?> {
            val rows = LookRules.rows(parts, picture, DockLayers.trim(dock, parts.columns).size, tv)
            val doc = LookKeys.build(LookRules.spec(parts, rows), LookRules.pictureKeys(picture, rows), parts.columns, dock, cards,
                LookRules.extras(parts, rows, tv))
            return (if (fillDock) doc.copy(fillDock = true) else doc) to picture.wallpaper
        }
    }
}

// The 0.4.0 path, frozen: a saved 0.4.0 look sends exactly these bytes until the person sends a
// new look (spec 8.2, C16), so neither the TV nor the drift check sees a change nobody made. The
// legacy-looks fixtures (LookMigrationTest) hold it to the bytes 0.4.0 sent.
internal object LegacyLook {
    fun docOf(theme: Theme?, dock: List<PackageName>, cards: Boolean, picture: LookPicture?, columns: Int? = null,
        fillDock: Boolean = false): Pair<LauncherConfigDoc, BlobRef?> {
        val base = DockLayers.applyTo(theme?.doc ?: LauncherConfigDoc(), dock, columns).copy(moduleCards = cards, fillDock = fillDock)
        return when (val p = picture.takeUnless { Looks.dated(theme?.id) }) {
            null -> base to theme?.wallpaper
            LookPicture.None -> base.copy(wallpaper = false, wallpaperRotationMinutes = 0) to null
            is LookPicture.Of -> base.copy(wallpaper = true, wallpaperRotationMinutes = 0, wallpaperDim = p.dim ?: base.wallpaperDim) to p.ref
        }
    }
}

// The public face of Look.docOf, for the tests outside this module (the Look sheet's screenshot
// goldens and the emulator byte dump), so they draw and send exactly the doc a push makes.
object LookDocs {
    fun of(theme: Theme?, dock: List<PackageName>, cards: Boolean, picture: LookPicture? = null, columns: Int? = null): Pair<LauncherConfigDoc, BlobRef?> =
        Look.docOf(theme, dock, cards, picture, columns)
}

// The picture a look shows instead of its own (HomeChoice.picture).
sealed interface LookPicture {
    data object None : LookPicture
    data class Of(val ref: BlobRef, val dim: Int? = null) : LookPicture

    companion object {
        const val NONE_ID = "none"
        const val MINE_ID = "mine"     // the person's own photo for this TV (OwnPicture), resolved by HomeScreenMachine
        const val NEW_ID = "new"       // a photo just made in the open Look sheet, not on the TV yet; never saved as a choice
        const val EARLIER_ID = "earlier"   // look2: the earlier look's photo (EarlierLook), until sent; never saved as a choice

        // HomeChoice.picture to the picture: null (the look's own) and an id the gallery no longer
        // has keep the look's own.
        fun of(id: String?, pictures: PictureSource): LookPicture? = when (id) {
            null -> null
            NONE_ID -> None
            else -> pictures.pictures().firstOrNull { it.id == id }?.let { Of(it.wallpaper) }
        }
    }
}

// The launcher's module cards (module_cards). With them on, the launcher asks on the TV about every
// installed app that declares a card ("New card from ... Keep hidden / Show card"), so they stay
// off until the person asks for a card in Cards and modules; a first Setup never turns them on.
// The choice is per TV and kept in HomePrefs, and a kept choice wins. Without one, the TV's own
// overlay decides when it says module_cards outright, whoever wrote it (this phone, an older
// version, another phone). Otherwise (no overlay, unreadable, or the key left out) the caller's
// default: Setup off (cards are something to add later), Manage on (what the launcher does on
// that TV right now, its built-in default), so a dock or theme change never takes a card away.
internal object Cards {
    const val SETUP_DEFAULT = false
    const val MANAGE_DEFAULT = true

    fun of(saved: Boolean?, overlay: ByteArray?, default: Boolean): Boolean {
        if (saved != null) return saved
        val doc = overlay?.let { (LauncherConfigCodec.decode(it) as? DecodeResult.Ok)?.doc }
        return doc?.moduleCards ?: default
    }

    // The cards choice for a TV in Manage, from what is kept and the TV's overlay (the last stored
    // snapshot's, unless a fresher one is given).
    suspend fun current(d: ManageDeps, tvId: TvId, choice: HomeChoice?, overlay: ByteArray? = null): Boolean =
        of(choice?.cards, overlay ?: d.snapshots.latest(tvId)?.launcherOverlay, MANAGE_DEFAULT)
}

// D15: the digest of the config.json Manage just pushed, so the next connect can warn on drift.
// 0.5.0 F0: and the file itself, which the next push starts from until the TV is read again.
internal suspend fun rememberPushedLook(d: ManageDeps, tvId: TvId, look: Look) {
    attempt { PushedSettings.remember(d.documents, tvId, look.bytes) }
    val digest = look.digest
    val tv = d.tv(tvId) ?: return
    val pin = d.launcherPin.bundled()
    val l = tv.launcher ?: LauncherState(pin.versionCode, pin.signer, null, null)
    d.tvs.upsert(tv.copy(launcher = l.copy(overlayDigest = digest, ourOverlayDigest = digest)))
}
