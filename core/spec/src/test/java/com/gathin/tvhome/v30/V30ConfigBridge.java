package com.gathin.tvhome.v30;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tv.cleanyour.core.spec.upstream.UpstreamConfig;

// Runs the bundled launcher's own Config.load: launcher 3.3 (upstream main d7b0d4a5, versionCode
// 12) since 0.4.9-beta1, 3.0 (b06ab21, versionCode 9) before (RFC-UP300-1). The package keeps its
// v30 name: 3.1 to 3.3 only added keys (CARRIED_33) to the 3.0 reader. Config.java and the
// classes it calls are vendored in this package, the submodule's files with only the package line changed (LookDifferentialTest checks
// them). Every resource id in R is answered from config.xml by its name. `privateDir` plays the
// app's private files dir, where the launcher keeps a copy of the last config.json it read; `prefs`
// plays launcher_settings (the choices made on the TV), null for an empty one.
public final class V30ConfigBridge {
    private V30ConfigBridge() {}

    public static UpstreamConfig load(File dir, File privateDir, Map<String, Object> values, SharedPreferences prefs) {
        Config c = Config.load(new Context(dir, privateDir, resources(values), prefs));
        List<Integer> keycodes = new ArrayList<>();
        for (int k : c.settingsKeycodes) keycodes.add(k);
        Festival f = c.festival;
        UpstreamConfig.V24 v24 = new UpstreamConfig.V24(c.fillDock, c.animations, c.advancedEffects, c.greetingName,
                c.reducedMotion, c.dockTitle, c.dockRow, c.reorderButton, c.showStats, c.appLibrary, c.skyIntensity,
                keycodes, c.settingsKeyPanel, f.name, f.until == null ? "" : f.until.toString(), f.greeting, f.accent);
        List<Integer> inputKeycodes = new ArrayList<>();
        for (int k : c.inputKeycodes) inputKeycodes.add(k);
        List<String> races = new ArrayList<>();
        for (RaceWeekend.Race r : c.raceCalendar) {
            StringBuilder b = new StringBuilder().append(r.round).append('|').append(r.name).append('|').append(r.place).append('|');
            for (int i = 0; i < r.sessions.size(); i++) {
                if (i > 0) b.append(';');
                b.append(r.sessions.get(i).name).append('@').append(r.sessions.get(i).start);
            }
            races.add(b.toString());
        }
        UpstreamConfig.V25 v25 = new UpstreamConfig.V25(new HashMap<>(c.launchActions), new HashMap<>(c.appLabels),
                new HashMap<>(c.appBanners), inputKeycodes, new ArrayList<>(c.appKeys), c.cinematicHome,
                c.wallpaperRotationMinutes, c.wallpaperDim, races);
        UpstreamConfig.V27 v27 = new UpstreamConfig.V27(c.ambientGlow, c.glowStyle, c.appGlow, c.dockGlass, c.dockOpacity,
                c.dockEvenPadding, c.alwaysShowNames, c.iconOnlyButtons, c.iconOnlyInputs, c.topMenu, c.wifiStatusColor,
                c.focusOvershoot, c.cinematicDots, c.cinematicWhiteFocus, c.cinematicArt, c.cinematicScrim);
        UpstreamConfig.V30 v30 = new UpstreamConfig.V30(new ArrayList<>(c.inputSources), c.lunarDate, c.lunarUtcOffset,
                c.openOnStartChoice, c.showLibraryPill, c.libraryOkOpens, c.uniformTiles, c.consumeVolumeKeys, c.powerOnHome,
                c.homeLongPressHome, c.powerOnHomeAlways, c.dockBand, c.wifiStatusWarnings, c.dateBelowClock, c.vpnPill,
                c.locale, new ArrayList<>(c.topBarApps), c.showGreeting, c.headerWhite, c.clockWallpaper, c.clockWallpaperDim,
                c.clockGlow, c.singleRow, c.dockBandAll, c.glassStyle);
        return new UpstreamConfig(c.dockApps, c.hiddenApps, c.liveTvPackage, c.liveTvAction, c.sourcePanelService,
                c.sourcePanelAction, c.inputAction, c.columns, c.rowsBelowDock, c.shamsiDate, c.moduleCards, c.vpnApp,
                c.showHomeSettingsShortcut, c.backgroundColor, c.wallpaper != null, c.overlayApplied, v24, v25, v27, v30);
    }

    // Each R id to the config.xml value of the same name; a name config.xml lacks stays out, so
    // reading it fails as a missing resource would.
    static Resources resources(Map<String, Object> v) {
        Map<Integer, Object> m = new HashMap<>();
        for (Class<?> type : R.class.getDeclaredClasses()) {
            for (Field field : type.getDeclaredFields()) {
                Object value = v.get(field.getName());
                if (value == null) continue;
                try {
                    m.put(field.getInt(null), value);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return new Resources(m);
    }
}
