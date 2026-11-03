package tv.cleanyour.core.flows

import java.time.LocalDate
import java.time.format.DateTimeParseException
import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.spec.AppColours
import tv.cleanyour.core.spec.DecodeResult
import tv.cleanyour.core.spec.EffectiveConfig
import tv.cleanyour.core.spec.GlowStyle
import tv.cleanyour.core.spec.HighlightChoice
import tv.cleanyour.core.spec.LauncherConfigCodec
import tv.cleanyour.core.spec.LauncherConfigDoc
import tv.cleanyour.core.spec.LauncherKeys
import tv.cleanyour.core.spec.LookExtrasKeys
import tv.cleanyour.core.spec.TilesChoice
import tv.cleanyour.core.spec.Movement
import tv.cleanyour.core.spec.WidgetsSettings

// 0.4.0: the phone's picture of the TV's home screen for the Look sheet's choice, read back from
// the config.json bytes the push would send: encoded, then decoded and filled in with the
// launcher's own defaults as the TV reads it (LauncherConfigCodec.effective). So the preview never
// shows a key the TV would not get. `picturePath`: the wallpaper.jpg the push sends, as a file on
// this phone, when the TV shows one. `festive`: a festival is on for `today` (the diya, bell and
// marigold pills, the accent focus and the greeting). `dock`: the person's own app names, the dock
// and the rows under it in order. `minuteOfDay` is for the sky (sky_intensity above 0).
// `fillDock` (0.4.0, fill_dock): false, as every push writes it, leaves a dock space with no app empty
// on the TV, so the preview draws it as empty band.
// look2 (launcher 2.7 keys, read from the same bytes through LauncherKeys.look): `movement` from
// the four movement keys, `lift` (advanced_effects with animations on), `others` (the TV's other
// apps after the dock list: the All apps grid and the row peeking under the dock), `focusArgb`
// (the focused app's colour, AppColours, for the glow and the ring), `today` (the header's date),
// `names` (always_show_names), `glow` (NONE without ambient_glow; on the cinematic shelf it is the
// colour flood), `ring` (the focused tile's ring in the app's colour: app_glow, or the cinematic
// dock without cinematic_white_focus), `band` (NONE without the dock row), `bandOpacity`
// (dock_opacity), `evenPadding`, `buttons`, `roundInputs`, `wifiGreen`, `inputs` (the TV has an
// Inputs pill to show), `dots`, `scrim` and `art` (the cinematic shelf's dots, shade and art).
// 0.4.5 (home screen 3.0, read from the same bytes through LookExtrasKeys.shown, so only what the TV
// shows): `oneRow` (single_row with the dock row), `bandAll` (the band behind every app), `band`
// NONE for dock_band false too, `greetingShown` (show_greeting), `dateBelow` (date_below_clock),
// `brightDate` (header_white), `iconTiles` (uniform_tiles), `highlight` (glass_style), and
// `dockArgb` and `othersArgb`, each app's colour (AppColours) in `dock` and `others` order, for the
// icon cards. The internet warnings have nothing to draw while online.
data class LookPreview(
    val backgroundArgb: Int,
    val picturePath: String?,
    val dim: Int,
    val sky: Int,
    val columns: Int,
    val rowsBelowDock: Int,
    val dockRow: Boolean,
    val dockTitle: Boolean,
    val fillDock: Boolean,
    val cinematic: Boolean,
    val festive: Boolean,
    val accentArgb: Int,
    val greeting: String,
    val motion: LookMotion,
    val dock: List<String>,
    val minuteOfDay: Int,
    val movement: Movement = Movement.LIVELY,
    val lift: Boolean = false,
    val others: List<String> = emptyList(),
    val focusArgb: Int = 0xFF8A8A8A.toInt(),
    val today: LocalDate = LocalDate.of(2026, 10, 26),
    val names: Boolean = false,
    val glow: PreviewGlow = PreviewGlow.RADIAL,
    val ring: Boolean = false,
    val band: PreviewBand = PreviewBand.SOLID,
    val bandOpacity: Int = 100,
    val evenPadding: Boolean = false,
    val buttons: PreviewButtons = PreviewButtons.LABELLED,
    val roundInputs: Boolean = false,
    val wifiGreen: Boolean = false,
    val inputs: Boolean = true,
    val dots: Boolean = true,
    val scrim: Int = 100,
    val art: Int = 50,
    val oneRow: Boolean = false,
    val bandAll: Boolean = false,
    val greetingShown: Boolean = true,
    val dateBelow: Boolean = false,
    val brightDate: Boolean = false,
    val iconTiles: Boolean = false,
    val highlight: HighlightChoice = HighlightChoice.GLASS,
    val dockArgb: List<Int> = emptyList(),
    val othersArgb: List<Int> = emptyList(),
) {
    companion object {
        // 0.4.0, removed after look2 merges: the dock as names only.
        fun of(bytes: ByteArray, wallpaper: BlobRef?, dock: List<String>, today: LocalDate, minuteOfDay: Int): LookPreview {
            val doc = decode(bytes)
            return of040(LauncherConfigCodec.effective(doc, LauncherConfigCodec.builtInDefaults), wallpaper, dock, today, minuteOfDay)
        }

        private fun decode(bytes: ByteArray): LauncherConfigDoc = (LauncherConfigCodec.decode(bytes) as? DecodeResult.Ok)?.doc ?: LauncherConfigDoc()

        // The 0.4.0 fields, from the decoded bytes.
        private fun of040(e: EffectiveConfig, wallpaper: BlobRef?, dock: List<String>, today: LocalDate, minuteOfDay: Int): LookPreview {
            val festive = e.festival.isNotBlank() && (e.festivalUntil.isBlank() || lastDay(e.festivalUntil)?.let { !today.isAfter(it) } == true)
            val name = e.festival.trim().lowercase()
            return LookPreview(
                backgroundArgb = e.backgroundColorArgb,
                picturePath = wallpaper?.path?.takeIf { e.wallpaper && it.startsWith("/") },
                dim = e.wallpaperDim, sky = e.skyIntensity,
                columns = e.columns, rowsBelowDock = e.rowsBelowDock, dockRow = e.dockRow, dockTitle = e.dockTitle,
                fillDock = e.fillDock, cinematic = e.cinematicHome, festive = festive,
                accentArgb = if (festive) (e.accentColorArgb.takeIf { it != 0 } ?: DIYA_GOLD) else 0,
                greeting = if (!festive) "" else e.greeting.ifBlank { BUILT_IN_GREETINGS[name].orEmpty() },
                motion = when {
                    !e.animations -> LookMotion.OFF
                    e.reducedMotion -> LookMotion.CALM
                    e.advancedEffects -> LookMotion.CRISP
                    else -> LookMotion.BOUNCY
                },
                dock = dock, minuteOfDay = minuteOfDay,
            )
        }

        // look2: the dock and the TV's other apps with their packages (for their names and the
        // focused app's colour), and whether the TV shows an Inputs pill. Every other field is read
        // from the bytes: the codec's effective config for the modelled keys and LauncherKeys.look
        // for the carried 2.7 ones, so a key the TV would not get never shows. The dock is the
        // bytes' dock_apps (the given dock when they have none), named from the given apps; the
        // others are the rest of the given apps the bytes do not hide, in order. A dock app that is
        // not among the given apps (gone or switched off) is left out, as the TV skips it
        // (DockLayers.decode).
        fun of(bytes: ByteArray, wallpaper: BlobRef?, dock: List<AppChoice>, others: List<AppChoice>, today: LocalDate,
            minuteOfDay: Int, hasInputs: Boolean): LookPreview {
            val doc = decode(bytes)
            val e = LauncherConfigCodec.effective(doc, LauncherConfigCodec.builtInDefaults)
            val k = LauncherKeys.look(doc)
            val x = LookExtrasKeys.shown(doc)
            val apps = (dock + others).distinctBy { it.pkg }
            val names = apps.associate { it.pkg.value to it.name }
            val docked = doc.dockApps?.map { it.value }?.let { d -> if (names.isEmpty()) d else d.filter { it in names } } ?: dock.map { it.pkg.value }
            val hidden = e.hiddenApps.toSet()
            val rest = apps.filter { it.pkg.value !in docked && it.pkg.value !in hidden }
            val focused = docked.firstOrNull() ?: rest.firstOrNull()?.pkg?.value
            val base = of040(e, wallpaper, emptyList(), today, minuteOfDay)
            return base.copy(
                dock = docked.map { names[it] ?: it },
                others = rest.map { it.name },
                movement = when {
                    !e.animations -> Movement.OFF
                    e.reducedMotion -> Movement.QUICK
                    k.focusOvershoot -> Movement.LIVELY
                    else -> Movement.SMOOTH
                },
                lift = e.animations && e.advancedEffects,
                focusArgb = AppColours.of(focused),
                today = today,
                names = k.alwaysShowNames,
                glow = when {
                    !k.ambientGlow -> PreviewGlow.NONE
                    k.glowStyle == GlowStyle.HORIZON -> PreviewGlow.HORIZON
                    else -> PreviewGlow.RADIAL
                },
                ring = k.appGlow || e.cinematicHome && !k.cinematicWhiteFocus,
                band = when {
                    !e.dockRow || x.noBand -> PreviewBand.NONE
                    k.dockGlass -> PreviewBand.GLASS
                    e.cinematicHome || k.dockOpacity < 100 -> PreviewBand.SEE_THROUGH
                    else -> PreviewBand.SOLID
                },
                bandOpacity = k.dockOpacity,
                evenPadding = k.dockEvenPadding,
                buttons = when {
                    k.topMenu -> PreviewButtons.MENU
                    k.iconOnlyButtons -> PreviewButtons.ROUND
                    else -> PreviewButtons.LABELLED
                },
                roundInputs = k.iconOnlyInputs,
                wifiGreen = k.wifiStatusColor,
                inputs = hasInputs,
                dots = k.cinematicDots,
                scrim = k.cinematicScrim,
                art = k.cinematicArt,
                oneRow = x.oneRow,
                bandAll = x.bandAll,
                greetingShown = !x.hideGreeting,
                dateBelow = x.dateBelow,
                brightDate = x.brightDate,
                iconTiles = x.tiles == TilesChoice.ICONS,
                highlight = x.highlight,
                dockArgb = docked.map { AppColours.of(it) },
                othersArgb = rest.map { AppColours.of(it.pkg.value) },
            )
        }

        private fun lastDay(s: String): LocalDate? = try { LocalDate.parse(s.trim()) } catch (_: DateTimeParseException) { null }

        // The launcher's own gold for a festival without accent_color, and the greetings it
        // carries for the festivals it knows.
        const val DIYA_GOLD = 0xFFFFB23C.toInt()
        private val BUILT_IN_GREETINGS = mapOf("diwali" to "Happy Diwali", "dussehra" to "Happy Dussehra")
    }
}

// 0.5.0: the preview with the TV's Widgets as its file has them (null: not in the file, so the look's own value
// stands in, as LookCarry.merge sends it): the buttons' look, the green Wi-Fi, the Inputs button, the greeting,
// and the date's place and brightness. The hub passes its draft, so a change shows before it is sent.
fun LookPreview.withWidgets(w: WidgetsSettings): LookPreview = copy(
    buttons = when {
        w.topMenu ?: (buttons == PreviewButtons.MENU) -> PreviewButtons.MENU
        w.iconOnlyButtons ?: (buttons == PreviewButtons.ROUND) -> PreviewButtons.ROUND
        else -> PreviewButtons.LABELLED
    },
    roundInputs = w.iconOnlyInputs ?: roundInputs,
    wifiGreen = w.wifiStatusColor ?: wifiGreen,
    inputs = inputs && w.showInputsPill != false,
    greetingShown = w.showGreeting ?: greetingShown,
    dateBelow = w.dateBelowClock ?: dateBelow,
    brightDate = w.headerWhite ?: brightDate,
)

// look2: the glow (ambient_glow and glow_style), the dock band (dock_glass and dock_opacity, NONE
// without the dock row) and the buttons at the top (icon_only_buttons and top_menu), as drawn.
enum class PreviewGlow { NONE, RADIAL, HORIZON }

enum class PreviewBand { SOLID, SEE_THROUGH, GLASS, NONE }

enum class PreviewButtons { LABELLED, ROUND, MENU }

// 0.4.0, removed after look2 merges (look2 reads Movement and lift instead).
// How the TV moves: OFF (animations false), CALM (reduced motion), CRISP (advanced effects: lift,
// zoom and a stiffer spring) or BOUNCY (the launcher's default spring).
enum class LookMotion { OFF, CALM, CRISP, BOUNCY }
