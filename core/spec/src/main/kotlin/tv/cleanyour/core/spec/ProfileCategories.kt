package tv.cleanyour.core.spec

import tv.cleanyour.core.model.Component
import tv.cleanyour.core.model.GroupDefault
import tv.cleanyour.core.model.PackageGroup
import tv.cleanyour.core.model.PackageName

// The fixed set of plan groups every converted profile uses, in the order the plan shows them
// (UX 2, "rules behind the numbers"). Groups whose category is always a choice (updates, voice,
// casting, streaming, screensaver, accessibility) default to ASK, so Simple keeps them (D22 for
// updates); `home` packages are KEEP because the takeover section, not the debloat, handles them.
enum class ProfileCategory(val id: String, private val title: String, private val askTitle: String?, val alwaysAsk: Boolean) {
    ADS("ads", "Adverts and sponsored rows", "More promoted content", false),
    TRACKING("tracking", "Tracking and analytics", "More tracking services", false),
    PRELOADS("preloads", "Apps nobody asked for", "Apps you may not use", false),
    BRAND("brand", "%s extras", "More %s features", false),
    DEMO("demo", "Shop and demo mode", "Shop and demo mode", false),
    OTHER("other", "Other background services", "More background services", false),
    STREAMING("streaming", "Streaming apps you may not use", null, true),
    VOICE("voice", "Voice assistant", null, true),
    CASTING("casting", "Casting and mirroring", null, true),
    SCREENSAVER("screensaver", "Photo screensaver", null, true),
    ACCESSIBILITY("accessibility", "Accessibility helpers", null, true),
    UPDATES("updates", "Software updates", null, true),
    HOME("home", "Needed for the new home screen", null, false);

    fun default(ask: Boolean): GroupDefault = when {
        this == HOME -> GroupDefault.KEEP
        alwaysAsk || ask -> GroupDefault.ASK
        else -> GroupDefault.DISABLE
    }

    // Group id: the category, plus "-optional" for the owner's-choice half of a disable category.
    fun groupId(ask: Boolean): String = if (ask && !alwaysAsk && this != HOME) "$id-optional" else id

    fun title(ask: Boolean, brand: String): String {
        val t = if (ask && askTitle != null) askTitle else title
        return if ("%s" in t) t.format(brand) else t
    }

    companion object {
        fun of(id: String): ProfileCategory = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown category $id")

        // Fallback when a group title has no curated entry in profile-meta.json: keyword rules on
        // the upstream title, most specific first.
        fun guess(title: String): Pair<ProfileCategory, Boolean> {
            val t = title.lowercase()
            val ask = listOf("personal choice", "by choice", "owner's choice", "optional", "comment out", "skip to keep", "re-enable the ones you use", "off by choice")
                .any { it in t }
            val c = when {
                listOf("stock home", "stock launcher", "stock google tv launcher", "stock android tv launcher", "home-screen claimants", "setup wizard", "only after", "custom launcher")
                    .any { it in t } -> HOME
                listOf("firmware update", "ota", "software update", "updater").any { it in t } -> UPDATES
                listOf("telemetry", "analytics", "tracking", "metrics", "acr", "content recognition", "feedback").any { it in t } -> TRACKING
                listOf("advert", " ads", "ads,", "ads ", "sponsored", "promo", "recommendation", "content rows", "patchwall", "auto-install", "fast channels").any { it in t } || t.startsWith("ads") -> ADS
                listOf("demo", "store mode", "retail", "hotel", "shop").any { it in t } -> DEMO
                listOf("streaming").any { it in t } -> STREAMING
                listOf("voice", "assistant", "alexa").any { it in t } -> VOICE
                listOf("cast", "mirroring", "miracast", "airplay").any { it in t } -> CASTING
                listOf("screensaver", "ambient").any { it in t } -> SCREENSAVER
                listOf("preload", "apps", "games").any { it in t } -> PRELOADS
                listOf("background", "services", "extras", "vendor", "nagware").any { it in t } -> BRAND
                else -> OTHER
            }
            return c to ask
        }

        // Issue #111: packages that are always the owner's choice (an ask row, kept on by default),
        // whatever group a list files them in, with the reason the row gives. PatchWall: the Mi TV
        // 4i's owner lost the remote's PatchWall button, as upstream's xiaomi-mitv-axso2 notes; on a
        // TV where the app keeps the TV's own home screen, PatchWall is still how that owner browses.
        // This only refuses more: a list's off becomes a row the owner can still choose in Customise.
        val OWNERS_CHOICE: Map<PackageName, String> = mapOf(
            PackageName("com.mitv.tvhome.atv") to "Off, the remote's PatchWall button stops working while the TV's own home screen is in use.",
        )
        fun isOwnersChoice(pkg: PackageName): Boolean = pkg in OWNERS_CHOICE

        // D22: a firmware updater is a "Software updates" row wherever upstream filed it. The second
        // line (0.3.7) holds the updaters upstream v2.7.0 lists name: CVTE, TCL, Sber, Fire TV, the TCL
        // remote and peripherals updater and Sony's camera firmware. The b06ab21 lists name no new one.
        private val UPDATER_SEGMENT = Regex("^(update|updates|updater|updateservice|upgrade|remoteupgrade|ota|fota|usbota|systemupdate|otaupdate|" +
            "systemupgrade|versionupdateapp|rcfupdater|easyupgrade|updateperipheral|fwupdate)$", RegexOption.IGNORE_CASE)
        fun isUpdater(pkg: PackageName): Boolean = pkg.value.split('.').drop(1).any { UPDATER_SEGMENT.matches(it) }

        // A software updater by its name, or by its place in a profile's "Software updates" group:
        // the rows the Google Play build always keeps on (the planner's keepUpdatesOn, and the
        // Switched off screen, which then offers no Off pill for them).
        fun isUpdater(pkg: PackageName, groups: List<PackageGroup>): Boolean =
            isUpdater(pkg) || groups.any { g -> g.id == UPDATES.id && g.packages.any { it.name == pkg } }
    }
}

// Stock home screens we know by component. Stock launcher detection is an allowlist, never
// "whatever answers HOME" (PLAN 6.5, issue #85). tvlauncher is verified on the reference TV's
// capture; the other class names follow the platform builds and should be matched on the package
// as well when a TV reports a different class.
object KnownLaunchers {
    val TVLAUNCHER = Component(PackageName("com.google.android.tvlauncher"), ".MainActivity")
    val LEANBACK = Component(PackageName("com.google.android.leanbacklauncher"), ".MainActivity")
    val LAUNCHERX = Component(PackageName("com.google.android.apps.tv.launcherx"), ".home.HomeActivity")
    val FIRE_TV = Component(PackageName("com.amazon.tv.launcher"), ".ui.HomeActivity_vNext")

    val byPackage: Map<PackageName, Component> = listOf(TVLAUNCHER, LEANBACK, LAUNCHERX, FIRE_TV).associateBy { it.pkg }

    // What an unknown model may hand HOME back to (ProfileResolution.None and the sure sets).
    val generic: List<Component> = listOf(TVLAUNCHER, LEANBACK, LAUNCHERX)

    val SETUPWRAITH = PackageName("com.google.android.tungsten.setupwraith")
}
