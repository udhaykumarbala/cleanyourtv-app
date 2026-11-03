import XCTest
@testable import CleanYourTV

final class TVLauncherLayoutTests: XCTestCase {
    func testNamesReadLikeTheAndroidApp() {
        XCTAssertEqual(TVAppNames.name(for: "com.netflix.ninja"), "Netflix")
        XCTAssertEqual(TVAppNames.fromPackage("com.lego.android.tvleanback"), "Lego")
        XCTAssertEqual(TVAppNames.fromPackage("com.android.traceur"), "Traceur")
        XCTAssertEqual(TVAppNames.fromPackage("com.miui.tv.analytics"), "Miui Analytics")
        XCTAssertEqual(TVAppNames.fromPackage("com.google.android.play.games"), "Play Games")
    }

    func testLaunchableAppsSkipNoiseAndOurOwnPackages() throws {
        let output = """
        28 activities found:
          Activity #0:
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
          com.netflix.ninja/.MainActivity
          com.netflix.ninja/.Other
          com.gathin.tvhome/.MainActivity
          tv.cleanyour.homeinterceptor/.ProbeActivity
          com.disney.disneyplus/com.disney.MainActivity
        """
        XCTAssertEqual(try TVLaunchableApps.parse(output), ["com.disney.disneyplus", "com.netflix.ninja"])
    }

    func testRowsAndRowsBelowDockFollowTheLauncher() {
        XCTAssertEqual(TVLauncherRows.rows(["a", "b", "c", "d", "e"], columns: 4), [["a", "b", "c", "d"], ["e"]])
        XCTAssertEqual(TVLauncherRows.rowsBelowDock(count: 0, columns: 4), 0)
        XCTAssertEqual(TVLauncherRows.rowsBelowDock(count: 4, columns: 4), 0)
        XCTAssertEqual(TVLauncherRows.rowsBelowDock(count: 5, columns: 4), 1)
        XCTAssertEqual(TVLauncherRows.rowsBelowDock(count: 12, columns: 4), 2)
        XCTAssertEqual(TVLauncherRows.trim(["a", "a", "b"], columns: 1), ["a", "b"])
        XCTAssertEqual(TVLauncherRows.trim((0..<20).map(String.init), columns: 4).count, 12)
    }

    func testApplyingKeepsEveryOtherKeyAndWritesTheDock() throws {
        let existing = Data(##"{"columns":4,"wallpaper":false,"background_color":"#000000"}"##.utf8)
        let config = try TVLauncherConfig(data: existing)
        let data = try config.applying(order: ["com.netflix.ninja", "com.amazon.firebat", "com.hbo.hbonow", "com.disney.disneyplus", "com.spotify.tv.android"])
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["dock_apps"] as? [String], ["com.netflix.ninja", "com.amazon.firebat", "com.hbo.hbonow", "com.disney.disneyplus", "com.spotify.tv.android"])
        XCTAssertEqual(json["rows_below_dock"] as? Int, 1)
        XCTAssertEqual(json["columns"] as? Int, 4)
        XCTAssertEqual(json["wallpaper"] as? Bool, false)
        XCTAssertEqual(json["background_color"] as? String, "#000000")
        XCTAssertNil(json["dock_title"])
    }

    func testThreeFullRowsTurnTheDockTitleOff() throws {
        let order = (0..<12).map { "com.example.app\($0)" }
        let data = try TVLauncherConfig(data: nil).applying(order: order)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["rows_below_dock"] as? Int, 2)
        XCTAssertEqual(json["dock_title"] as? Bool, false)
    }

    func testAnEmptyOrderLeavesDockAppsOut() throws {
        let config = try TVLauncherConfig(data: Data(#"{"dock_apps":["com.netflix.ninja"],"columns":5}"#.utf8))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: config.applying(order: [])) as? [String: Any])
        XCTAssertNil(json["dock_apps"])
        XCTAssertEqual(json["columns"] as? Int, 5)
    }

    func testRefusesInvalidPackagesAndUnreadableConfigs() {
        XCTAssertThrowsError(try TVLauncherConfig(data: nil).applying(order: ["bad name; rm -rf"]))
        XCTAssertThrowsError(try TVLauncherConfig(data: Data("[1,2]".utf8)))
        XCTAssertThrowsError(try TVLauncherConfig.fromReadOutput("Error: something else"))
    }

    func testReadOutputWithNoFileMeansNoConfig() throws {
        let missing = "Error while accessing provider:com.gathin.tvhome.theme\njava.io.FileNotFoundException: open failed: ENOENT"
        XCTAssertNil(try TVLauncherConfig.fromReadOutput(missing).original)
        let present = try TVLauncherConfig.fromReadOutput(#"{"columns":6,"dock_apps":["com.netflix.ninja","bad name"]}"#)
        XCTAssertEqual(present.columns, 6)
        XCTAssertEqual(present.dockApps, ["com.netflix.ninja"])
    }

    func testBackupStoreRoundTripsAndRemoves() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = TVLauncherBackupStore(directoryURL: dir)
        XCTAssertNil(try store.load(tvID: "tv1"))
        let original = Data(#"{"columns":4}"#.utf8)
        try store.save(TVLauncherBackup(config: original, savedAt: Date()), tvID: "tv1")
        try store.save(TVLauncherBackup(config: nil, savedAt: Date()), tvID: "tv2")
        XCTAssertEqual(try store.load(tvID: "tv1")?.config, original)
        XCTAssertNotNil(try store.load(tvID: "tv2"))
        XCTAssertNil(try store.load(tvID: "tv2")?.config)
        try store.remove(tvID: "tv1")
        XCTAssertNil(try store.load(tvID: "tv1"))
    }
}
