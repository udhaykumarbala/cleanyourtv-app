package tv.cleanyour.core.probe

import tv.cleanyour.core.session.TvSession

// Not part of the frozen Prober (section 5.5), like LaunchableApps: the two secure settings the Home
// button helper stands on, as the TV has them now. `enabled` is accessibility_enabled ("1" on),
// `services` enabled_accessibility_services (the list our HomeKeyService is in while the helper is
// on). 0.4.8 (issues #2061, #1799, #1650, #947): the restart count reads them, as Home alone cannot
// tell a helper that works from one that is off. One read-only call, the SETTINGS stage's own script,
// so nothing new runs on the TV. Fails with AdbException or ProbeException like the Prober.
data class AccessibilityNow(val enabled: String?, val services: String?)

fun interface AccessibilityRead {
    suspend fun accessibility(session: TvSession): AccessibilityNow
}
