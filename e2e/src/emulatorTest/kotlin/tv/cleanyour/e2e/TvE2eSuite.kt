package tv.cleanyour.e2e

import org.junit.runner.RunWith
import org.junit.runners.Suite
import tv.cleanyour.e2e.scenarios.S00HarnessSmoke
import tv.cleanyour.e2e.scenarios.S01ConnectAndIdentity
import tv.cleanyour.e2e.scenarios.S02ProbeAndBaseline
import tv.cleanyour.e2e.scenarios.S03DebloatAndUndo
import tv.cleanyour.e2e.scenarios.S04PushAndInstall
import tv.cleanyour.e2e.scenarios.S05TakeoverWithCrashingLauncher
import tv.cleanyour.e2e.scenarios.S06ThemePush
import tv.cleanyour.e2e.scenarios.S07RebootAndReconnect
import tv.cleanyour.e2e.scenarios.S08DebugOff
import tv.cleanyour.e2e.scenarios.S09CurtainSetup
import tv.cleanyour.e2e.scenarios.S10HomeHelper
import tv.cleanyour.e2e.scenarios.S11SettingsPush

// The TV e2e scenarios of PRODUCT-SPEC 7.1 in the order one emulator session runs them. Order
// matters: S07 reboots the TV and S08 switches debugging off for good, so both come last.
// `./gradlew :e2e:emulatorTest` runs exactly this suite.
@RunWith(Suite::class)
@Suite.SuiteClasses(
    S00HarnessSmoke::class,
    S01ConnectAndIdentity::class,
    S02ProbeAndBaseline::class,
    S03DebloatAndUndo::class,
    S04PushAndInstall::class,
    S05TakeoverWithCrashingLauncher::class,
    S06ThemePush::class,
    S11SettingsPush::class,   // 0.5.0 Make it yours: the settings document without the wallpaper step
    S09CurtainSetup::class,   // RFC-CURTAIN-1; before the reboot and debug_off, which end the session's TV
    S10HomeHelper::class,     // RFC-HELPER-1 (test build): the Home button helper
    S07RebootAndReconnect::class,
    S08DebugOff::class,
)
class TvE2eSuite
