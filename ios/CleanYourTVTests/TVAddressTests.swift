import XCTest
import CryptoKit
import Security
@testable import CleanYourTV

final class TVAddressTests: XCTestCase {
    func testAcceptsValidIPv4Addresses() {
        XCTAssertTrue(IPv4Address.isValid("192.168.1.42"))
        XCTAssertTrue(IPv4Address.isValid("0.0.0.0"))
        XCTAssertTrue(IPv4Address.isValid("255.255.255.255"))
    }

    func testRejectsMalformedOrOutOfRangeAddresses() {
        XCTAssertFalse(IPv4Address.isValid(""))
        XCTAssertFalse(IPv4Address.isValid("192.168.1"))
        XCTAssertFalse(IPv4Address.isValid("192.168.1.256"))
        XCTAssertFalse(IPv4Address.isValid("example.local"))
        XCTAssertFalse(IPv4Address.isValid("192.168..42"))
        XCTAssertFalse(IPv4Address.isValid("192.168.1.0000"))
    }

    func testAcceptsSupportedADBConnectionVersions() {
        XCTAssertTrue(ADBProtocol.supports(version: 0x0100_0000))
        XCTAssertTrue(ADBProtocol.supports(version: 0x0100_0001))
        XCTAssertFalse(ADBProtocol.supports(version: 0x00ff_ffff))
        XCTAssertFalse(ADBProtocol.supports(version: 0x0100_0002))
    }

    func testParsesTVIdentityAndCreatesStableIdentifier() throws {
        let properties = """
        [ro.serialno]: [ABCD1234]
        [ro.product.brand]: [Acme]
        [ro.product.manufacturer]: [Acme Corp]
        [ro.product.device]: [test_device]
        [ro.product.model]: [Test TV]
        [ro.build.version.sdk]: [34]
        [ro.build.version.release]: [14]
        [ro.build.fingerprint]: [acme/test/device:14/ABC123/456:user/release-keys]
        [ro.product.cpu.abilist]: [arm64-v8a,armeabi-v7a]
        [persist.sys.locale]: [en-US]
        [ro.adb.secure]: [1]
        """
        let identity = try TVIdentity.parse(properties: properties)
        let digest = SHA256.hash(data: Data("ABCD1234test_device".utf8))
        XCTAssertEqual(identity.model, "Test TV")
        XCTAssertEqual(identity.brand, "Acme")
        XCTAssertEqual(identity.manufacturer, "Acme Corp")
        XCTAssertEqual(identity.device, "test_device")
        XCTAssertEqual(identity.serialLast4, "1234")
        XCTAssertEqual(identity.sdk, 34)
        XCTAssertEqual(identity.release, "14")
        XCTAssertEqual(identity.fingerprint, "acme/test/device:14/ABC123/456:user/release-keys")
        XCTAssertEqual(identity.abis, ["arm64-v8a", "armeabi-v7a"])
        XCTAssertEqual(identity.locale, "en-US")
        XCTAssertEqual(identity.adbSecure, true)
        XCTAssertEqual(identity.tvID, digest.prefix(8).map { String(format: "%02x", $0) }.joined())
    }

    func testRejectsIncompleteTVIdentity() {
        XCTAssertThrowsError(try TVIdentity.parse(properties: """
        [ro.serialno]: [ABCD1234]
        [ro.product.device]: [test_device]
        [ro.build.version.sdk]: [invalid]
        """))
    }

    func testParsesHOMEResolutionAndRejectsMalformedComponents() throws {
        XCTAssertEqual(
            try TVConfigurationParser.homeComponent(from: """
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.example.launcher/.MainActivity
            """),
            "com.example.launcher/.MainActivity"
        )
        XCTAssertNil(try TVConfigurationParser.homeComponent(from: "No activity found"))
        XCTAssertThrowsError(try TVConfigurationParser.homeComponent(from: "com.example.launcher/Bad Class"))
    }

    func testReadsOnlyAllowlistedTVSettingsWithStrictValues() throws {
        XCTAssertEqual(
            try TVConfigurationParser.settingValue(from: "1\n", setting: .screensaverEnabled),
            "1"
        )
        XCTAssertEqual(
            try TVConfigurationParser.settingValue(from: "com.example/.Service\n", setting: .enabledAccessibilityServices),
            "com.example/.Service"
        )
        XCTAssertNil(
            try TVConfigurationParser.settingValue(from: "\n", setting: .enabledAccessibilityServices)
        )
        XCTAssertNil(try TVConfigurationParser.settingValue(from: "null\n", setting: .deviceName))
        XCTAssertEqual(
            try TVConfigurationParser.settingValue(from: "Gerardo’s Fire TV Stick 4K\n", setting: .deviceName),
            "Gerardo’s Fire TV Stick 4K"
        )
        XCTAssertThrowsError(try TVConfigurationParser.settingValue(from: "true\n", setting: .adbEnabled))
        XCTAssertThrowsError(try TVConfigurationParser.settingValue(from: "package:evil\n", setting: .screensaverComponents))
        XCTAssertThrowsError(try TVConfigurationParser.settingValue(from: "a\nb\n", setting: .deviceName))
        XCTAssertThrowsError(try TVConfigurationParser.settingValue(from: "Fire TV\tStick\n", setting: .deviceName))
        XCTAssertEqual(TVSetting.allCases.count, 7)
        XCTAssertEqual(
            Set(TVSetting.allCases.map { "\($0.namespace).\($0.key)" }),
            [
                "secure.screensaver_enabled",
                "secure.screensaver_components",
                "secure.enabled_accessibility_services",
                "secure.accessibility_enabled",
                "global.adb_enabled",
                "global.adb_wifi_enabled",
                "global.device_name"
            ]
        )
    }

    func testProfileResolutionPrefersExactMatchThenUsesBrandSureSet() throws {
        let identity = try TVIdentity.parse(properties: """
        [ro.serialno]: [ABCD1234]
        [ro.product.brand]: [Acme]
        [ro.product.manufacturer]: [Acme Corp]
        [ro.product.device]: [test_device]
        [ro.product.model]: [Test TV]
        [ro.build.version.sdk]: [34]
        [ro.build.fingerprint]: [acme/test/device:14/ABC123/456:user/release-keys]
        """)
        let group = TVCatalogGroup(id: "ads", title: "Ads", default: "DISABLE", packages: [])
        let exact = TVCatalogProfile(
            id: "exact-model",
            match: TVCatalogMatch(
                models: ["Test TV"],
                devices: [],
                brand: "Acme",
                sdkMin: 34,
                sdkMax: 34,
                fingerprintPrefix: nil
            ),
            platform: "ANDROID_TV",
            stockLaunchers: [],
            setupWraith: nil,
            quirks: [],
            groups: [group],
            neverDisable: []
        )
        let sureSet = TVCatalogSureSet(brand: "Acme", groups: [group], stockLaunchers: [])
        let catalog = TVProfileCatalog(profiles: [exact], sureSets: [sureSet])

        let exactResult = catalog.resolve(identity: identity)
        XCTAssertEqual(exactResult.kind, .exact)
        XCTAssertEqual(exactResult.title, "Exact Model")

        let unknownIdentity = TVIdentity(
            brand: "Acme",
            manufacturer: "Acme Corp",
            model: "Unlisted TV",
            device: "unknown_device",
            serialLast4: "1234",
            sdk: 34,
            release: "14",
            fingerprint: "unknown",
            abis: [],
            locale: "",
            adbSecure: true,
            tvID: "0123456789abcdef"
        )
        let brandResult = catalog.resolve(identity: unknownIdentity)
        XCTAssertEqual(brandResult.kind, .brandSureSet)
        XCTAssertEqual(brandResult.title, "Acme brand profile")
    }

    func testBundledProfileCatalogLoadsAndMatchesPinnedExample() throws {
        let identity = try TVIdentity.parse(properties: """
        [ro.serialno]: [ABCD1234]
        [ro.product.brand]: [Expressluck]
        [ro.product.manufacturer]: [Expressluck]
        [ro.product.device]: [dongnae]
        [ro.product.model]: [Example TV]
        [ro.build.version.sdk]: [34]
        [ro.build.fingerprint]: [example/fingerprint]
        """)
        let catalog = try TVProfileCatalog.loadBundled()
        let result = catalog.resolve(identity: identity)
        XCTAssertEqual(result.kind, .exact)
        XCTAssertFalse(result.groups.isEmpty)
    }

    func testPackageListParserMarksDisabledAppsWithoutChangingAnything() throws {
        let packages = try TVPackageListParser.parse(
            installed: """
            package:/system/app/Settings/Settings.apk=com.android.tv.settings
            package:/data/app/example/base.apk=com.example.stream
            package:/data/app/other/base.apk=com.example.other
            """,
            disabled: """
            package:com.example.other
            package:com.example.other
            """
        )
        XCTAssertEqual(packages, [
            TVPackage(name: "com.android.tv.settings", isDisabled: false, isSystem: nil, isPersistent: nil, isTVInput: false, requestsTVInputHardware: nil),
            TVPackage(name: "com.example.stream", isDisabled: false, isSystem: nil, isPersistent: nil, isTVInput: false, requestsTVInputHardware: nil),
            TVPackage(name: "com.example.other", isDisabled: true, isSystem: nil, isPersistent: nil, isTVInput: false, requestsTVInputHardware: nil)
        ])
    }

    func testPackageListParserRejectsUnexpectedAndInvalidRecords() {
        XCTAssertThrowsError(try TVPackageListParser.parse(installed: "garbage", disabled: ""))
        XCTAssertThrowsError(try TVPackageListParser.parse(installed: "package:not-a-package", disabled: ""))
        XCTAssertThrowsError(try TVPackageListParser.parse(
            installed: "package:com.example.app",
            disabled: "package:com.example.missing"
        ))
        XCTAssertThrowsError(try TVPackageListParser.parse(installed: "", disabled: ""))
    }

    func testParsesPackageFlagsHomeClaimantsAndTVInputServices() throws {
        let packageMetadata = try TVConfigurationParser.packageMetadata(from: """
          Package [com.example.persistent] (abc123):
            pkgFlags=[ SYSTEM PERSISTENT ]
            User 0: installed=true hidden=false enabled=0
          Package [com.example.app] (def456):
            pkgFlags=[ UPDATED_SYSTEM_APP ]
            User 0: installed=true hidden=false enabled=0
        """)
        XCTAssertEqual(packageMetadata["com.example.persistent"]?.isPersistent, true)
        XCTAssertEqual(packageMetadata["com.example.persistent"]?.isSystem, true)
        XCTAssertEqual(packageMetadata["com.example.persistent"]?.isInstalled, true)
        XCTAssertEqual(packageMetadata["com.example.app"]?.isPersistent, false)
        XCTAssertEqual(packageMetadata["com.example.app"]?.isSystem, false)

        XCTAssertEqual(
            try TVConfigurationParser.homeClaimants(from: """
            priority=0 preferredOrder=0 match=0x108000 isDefault=true
            com.example.launcher/.MainActivity
            """),
            ["com.example.launcher/.MainActivity"]
        )
        let input = try TVConfigurationParser.tvInputPackages(from: """
        TvInputInfo{id=com.example.input/.Service/Hdmi1, pkg=com.example.input, service=com.example.input/.Service}
        """)
        XCTAssertTrue(input.available)
        XCTAssertEqual(input.packages, ["com.example.input"])
        XCTAssertFalse(try TVConfigurationParser.tvInputPackages(from: "Can't find service: tv_input").available)
        XCTAssertFalse(try TVConfigurationParser.tvInputPackages(from: "").available)
        XCTAssertFalse(try TVConfigurationParser.tvInputPackages(from: "unrecognized service output").available)
        XCTAssertEqual(
            try TVConfigurationParser.requestsTVInputHardware(from: """
            requested permissions:
              android.permission.TV_INPUT_HARDWARE
            install permissions:
            """),
            true
        )
        XCTAssertEqual(
            try TVConfigurationParser.requestsTVInputHardware(from: """
            requested permissions:
              android.permission.INTERNET
            install permissions:
            """),
            false
        )
        XCTAssertNil(try TVConfigurationParser.requestsTVInputHardware(from: "Package [com.example.app]"))
    }

    func testSafetyAnalysisProtectsCriticalAndDeviceSpecificPackages() throws {
        let identity = try TVIdentity.parse(properties: """
        [ro.serialno]: [ABCD1234]
        [ro.product.brand]: [Acme]
        [ro.product.manufacturer]: [Acme]
        [ro.product.device]: [test_device]
        [ro.product.model]: [Test TV]
        [ro.build.version.sdk]: [34]
        [ro.build.fingerprint]: [acme/test/device]
        """)
        let entries = [
            TVCatalogEntry(name: "com.example.ads", note: "Sponsored rows"),
            TVCatalogEntry(name: "com.android.systemui", note: nil),
            TVCatalogEntry(name: "com.example.persistent", note: nil),
            TVCatalogEntry(name: "com.example.input", note: nil),
            TVCatalogEntry(name: "com.example.keyboard", note: nil),
            TVCatalogEntry(name: "com.example.screensaver", note: nil),
            TVCatalogEntry(name: "com.example.launcher", note: nil),
            TVCatalogEntry(name: "com.example.never", note: nil),
            TVCatalogEntry(name: "com.example.hardware", note: nil)
        ]
        let optionalEntry = TVCatalogEntry(name: "com.example.optional", note: nil)
        let groups = [
            TVCatalogGroup(id: "ads", title: "Ads", default: "DISABLE", packages: entries),
            TVCatalogGroup(id: "voice", title: "Voice", default: "ASK", packages: [optionalEntry])
        ]
        let resolution = TVProfileResolution(
            kind: .exact,
            profileID: "test-profile",
            title: "Test profile",
            detail: "Exact match",
            groups: groups,
            platform: "ANDROID_TV",
            stockLauncherPackages: ["com.google.android.tvlauncher"],
            setupWizard: nil,
            quirks: [],
            neverDisable: ["com.example.never"]
        )
        let allEntries = entries + [optionalEntry]
        let metadata = Dictionary(uniqueKeysWithValues: allEntries.map {
            ($0.name, TVPackageMetadata(isSystem: false, isPersistent: $0.name == "com.example.persistent", isInstalled: true))
        })
        let settings = [
            TVSettingValue(setting: .screensaverComponents, value: "com.example.screensaver/.Saver")
        ]
        let snapshot = TVSafetySnapshot(
            packages: allEntries.map {
                TVPackage(
                    name: $0.name,
                    isDisabled: false,
                    isSystem: false,
                    isPersistent: $0.name == "com.example.persistent",
                    isTVInput: $0.name == "com.example.input",
                    requestsTVInputHardware: $0.name == "com.example.hardware"
                )
            },
            configuration: TVConfigurationSnapshot(
                homeComponent: "com.example.launcher/.MainActivity",
                homeClaimants: ["com.example.launcher/.MainActivity"],
                settings: settings,
                inputPackages: ["com.example.input"],
                inputServiceAvailable: true,
                inputMethod: "com.example.keyboard/.IME",
                packageMetadata: metadata
            )
        )

        let report = TVSafetyAnalyzer.analyze(identity: identity, resolution: resolution, snapshot: snapshot)
        XCTAssertEqual(report.candidateCount, 1)
        XCTAssertEqual(report.protectedCount, 8)
        XCTAssertEqual(report.optionalCount, 1)
        XCTAssertEqual(report.rows.first(where: { $0.package == "com.example.ads" })?.status, .reviewCandidate)
        XCTAssertEqual(report.rows.first(where: { $0.package == "com.android.systemui" })?.status, .protected)
        XCTAssertEqual(report.rows.first(where: { $0.package == "com.example.never" })?.status, .protected)
        XCTAssertEqual(report.rows.first(where: { $0.package == "com.example.hardware" })?.status, .protected)
        XCTAssertTrue(report.rows.first(where: { $0.package == "com.example.hardware" })?.reason?.contains("TV_INPUT_HARDWARE") == true)
        XCTAssertEqual(report.rows.first(where: { $0.package == "com.example.optional" })?.status, .optional)
    }

    func testSafetyAnalysisBlocksCandidatesWhenRequiredFactsAreMissing() throws {
        let identity = try TVIdentity.parse(properties: """
        [ro.serialno]: [ABCD1234]
        [ro.product.brand]: [Acme]
        [ro.product.device]: [test_device]
        [ro.product.model]: [Test TV]
        [ro.build.version.sdk]: [34]
        """)
        let resolution = TVProfileResolution(
            kind: .exact,
            profileID: "test-profile",
            title: "Test profile",
            detail: "Exact match",
            groups: [TVCatalogGroup(id: "ads", title: "Ads", default: "DISABLE", packages: [
                TVCatalogEntry(name: "com.example.ads", note: nil)
            ])],
            platform: "ANDROID_TV",
            stockLauncherPackages: [],
            setupWizard: nil,
            quirks: [],
            neverDisable: []
        )
        let snapshot = TVSafetySnapshot(
            packages: [TVPackage(name: "com.example.ads", isDisabled: false, isSystem: nil, isPersistent: nil, isTVInput: false, requestsTVInputHardware: false)],
            configuration: TVConfigurationSnapshot(
                homeComponent: "com.google.android.tvlauncher/.Main",
                homeClaimants: [],
                settings: [],
                inputPackages: [],
                inputServiceAvailable: false,
                inputMethod: nil,
                packageMetadata: [:]
            )
        )
        let report = TVSafetyAnalyzer.analyze(identity: identity, resolution: resolution, snapshot: snapshot)
        XCTAssertEqual(report.candidateCount, 0)
        XCTAssertEqual(report.protectedCount, 1)
        XCTAssertFalse(report.incompleteReasons.isEmpty)
    }

    func testUnverifiedPackagePermissionProtectsOnlyThatPackage() throws {
        let identity = try TVIdentity.parse(properties: """
        [ro.serialno]: [ABCD1234]
        [ro.product.brand]: [Acme]
        [ro.product.device]: [test_device]
        [ro.product.model]: [Test TV]
        [ro.build.version.sdk]: [34]
        """)
        let resolution = TVProfileResolution(
            kind: .exact,
            profileID: "test-profile",
            title: "Test profile",
            detail: "Exact match",
            groups: [TVCatalogGroup(id: "apps", title: "Apps", default: "DISABLE", packages: [
                TVCatalogEntry(name: "com.example.verified", note: nil),
                TVCatalogEntry(name: "com.example.unverified", note: nil)
            ])],
            platform: "ANDROID_TV",
            stockLauncherPackages: [],
            setupWizard: nil,
            quirks: [],
            neverDisable: []
        )
        let snapshot = TVSafetySnapshot(
            packages: [
                TVPackage(name: "com.example.verified", isDisabled: false, isSystem: false, isPersistent: false, isTVInput: false, requestsTVInputHardware: false),
                TVPackage(name: "com.example.unverified", isDisabled: false, isSystem: false, isPersistent: false, isTVInput: false, requestsTVInputHardware: nil)
            ],
            configuration: TVConfigurationSnapshot(
                homeComponent: "com.example.launcher/.Main",
                homeClaimants: ["com.example.launcher/.Main"],
                settings: [],
                inputPackages: [],
                inputServiceAvailable: true,
                inputMethod: nil,
                packageMetadata: [
                    "com.example.verified": TVPackageMetadata(isSystem: false, isPersistent: false, isInstalled: true),
                    "com.example.unverified": TVPackageMetadata(isSystem: false, isPersistent: false, isInstalled: true)
                ]
            )
        )

        let report = TVSafetyAnalyzer.analyze(identity: identity, resolution: resolution, snapshot: snapshot)

        XCTAssertTrue(report.incompleteReasons.isEmpty)
        XCTAssertEqual(report.candidateCount, 1)
        XCTAssertEqual(report.protectedCount, 1)
        XCTAssertEqual(report.rows.first(where: { $0.package == "com.example.verified" })?.status, .reviewCandidate)
        XCTAssertEqual(report.rows.first(where: { $0.package == "com.example.unverified" })?.status, .protected)
    }

    func testSnapshotDigestIsStableAndChangesWhenTVStateChanges() throws {
        let identity = try TVIdentity.parse(properties: """
        [ro.serialno]: [ABCD1234]
        [ro.product.brand]: [Acme]
        [ro.product.device]: [test_device]
        [ro.product.model]: [Test TV]
        [ro.build.version.sdk]: [34]
        """)
        let resolution = TVProfileResolution(
            kind: .exact,
            profileID: "test-profile",
            title: "Test profile",
            detail: "Exact match",
            groups: [],
            platform: "ANDROID_TV",
            stockLauncherPackages: [],
            setupWizard: nil,
            quirks: [],
            neverDisable: []
        )
        let package = TVPackage(
            name: "com.example.app",
            isDisabled: false,
            isSystem: false,
            isPersistent: false,
            isTVInput: false,
            requestsTVInputHardware: false
        )
        let configuration = TVConfigurationSnapshot(
            homeComponent: "com.example.launcher/.Main",
            homeClaimants: ["com.example.launcher/.Main"],
            settings: [],
            inputPackages: ["com.example.input"],
            inputServiceAvailable: true,
            inputMethod: nil,
            packageMetadata: [
                "com.example.app": TVPackageMetadata(isSystem: false, isPersistent: false, isInstalled: true)
            ]
        )
        let initialSnapshot = TVSafetySnapshot(packages: [package], configuration: configuration)
        let initialDigest = TVSafetyAnalyzer.snapshotDigest(
            identity: identity,
            resolution: resolution,
            snapshot: initialSnapshot
        )
        XCTAssertEqual(
            initialDigest,
            TVSafetyAnalyzer.snapshotDigest(
                identity: identity,
                resolution: resolution,
                snapshot: initialSnapshot
            )
        )

        let changedSnapshot = TVSafetySnapshot(
            packages: [TVPackage(
                name: package.name,
                isDisabled: true,
                isSystem: package.isSystem,
                isPersistent: package.isPersistent,
                isTVInput: package.isTVInput,
                requestsTVInputHardware: package.requestsTVInputHardware
            )],
            configuration: configuration
        )
        let changedDigest = TVSafetyAnalyzer.snapshotDigest(
            identity: identity,
            resolution: resolution,
            snapshot: changedSnapshot
        )
        XCTAssertNotEqual(initialDigest, changedDigest)

        let report = TVSafetyReport(groups: [], incompleteReasons: [])
        let plan = TVReviewPlan(
            tvID: identity.tvID,
            profileID: resolution.profileID,
            profileTitle: resolution.title,
            snapshotDigest: initialDigest,
            report: report
        )
        XCTAssertFalse(plan.isStale(against: initialDigest))
        XCTAssertTrue(plan.isStale(against: changedDigest))
        XCTAssertTrue(plan.isStale(against: nil))
        XCTAssertNotEqual(plan.snapshotDigest, changedDigest)
    }

    func testReviewPlanDefaultsToCandidatesPersistsChoicesAndDetectsTampering() throws {
        let report = TVSafetyReport(
            groups: [
                TVAnalysisGroup(id: "ads", title: "Ads", rows: [
                    TVAnalysisRow(package: "com.example.ad", note: nil, status: .reviewCandidate, reason: nil),
                    TVAnalysisRow(package: "com.android.systemui", note: nil, status: .protected, reason: "Protected")
                ]),
                TVAnalysisGroup(id: "voice", title: "Voice", rows: [
                    TVAnalysisRow(package: "com.example.voice", note: nil, status: .optional, reason: nil)
                ])
            ],
            incompleteReasons: []
        )
        var plan = TVReviewPlan(
            tvID: "0123456789abcdef",
            profileID: "test-profile",
            profileTitle: "Test profile",
            snapshotDigest: String(repeating: "a", count: 64),
            report: report
        )
        XCTAssertEqual(Set(plan.items.map(\.package)), ["com.example.ad", "com.example.voice"])
        XCTAssertEqual(plan.selectedCount, 1)
        XCTAssertTrue(plan.isDigestValid)
        XCTAssertFalse(plan.exportText.contains("pm disable"))

        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("CleanYourTVTests-\(UUID().uuidString)", isDirectory: true)
        addTeardownBlock { try FileManager.default.removeItem(at: directory) }
        let store = TVReviewPlanStore(directoryURL: directory)
        try store.save(plan)

        try plan.setSelected(true, for: "com.example.voice")
        XCTAssertEqual(plan.selectedCount, 2)
        XCTAssertTrue(plan.isDigestValid)
        try store.save(plan)
        let restored = try XCTUnwrap(store.load(tvID: plan.tvID))
        XCTAssertEqual(restored.selectedCount, 2)
        XCTAssertEqual(restored.planDigest, plan.planDigest)

        let fileURL = directory.appendingPathComponent("review-plans.json")
        var encoded = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(contentsOf: fileURL)) as? [String: [String: Any]]
        )
        var storedPlan = try XCTUnwrap(encoded[plan.tvID])
        storedPlan["planDigest"] = String(repeating: "0", count: 64)
        encoded[plan.tvID] = storedPlan
        try JSONSerialization.data(withJSONObject: encoded, options: [.sortedKeys]).write(to: fileURL, options: .atomic)
        XCTAssertThrowsError(try store.load(tvID: plan.tvID))
    }

    func testRestorePointPersistsOriginalPackageStatesAndDetectsTampering() throws {
        var point = TVRestorePoint(
            tvID: "0123456789abcdef",
            profileID: "test-profile",
            sourcePlanDigest: String(repeating: "a", count: 64),
            items: [
                TVRestorePoint.Item(package: "com.example.enabled", wasDisabled: false),
                TVRestorePoint.Item(package: "com.example.disabled", wasDisabled: true)
            ]
        )
        XCTAssertTrue(point.isDigestValid)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("CleanYourTVRestoreTests-\(UUID().uuidString)", isDirectory: true)
        addTeardownBlock { try FileManager.default.removeItem(at: directory) }
        let store = TVRestorePointStore(directoryURL: directory)
        try store.save(point)

        let restored = try XCTUnwrap(store.load(tvID: point.tvID))
        XCTAssertEqual(restored.items.map(\.wasDisabled), [false, true])
        XCTAssertFalse(restored.isRestored)
        point.markRestored()
        XCTAssertTrue(point.isDigestValid)
        try store.save(point)
        XCTAssertTrue(try XCTUnwrap(store.load(tvID: point.tvID)).isRestored)

        let fileURL = directory.appendingPathComponent("restore-points.json")
        var encoded = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(contentsOf: fileURL)) as? [String: [String: Any]]
        )
        var storedPoint = try XCTUnwrap(encoded[point.tvID])
        storedPoint["digest"] = String(repeating: "0", count: 64)
        encoded[point.tvID] = storedPoint
        try JSONSerialization.data(withJSONObject: encoded, options: [.sortedKeys]).write(to: fileURL, options: .atomic)
        XCTAssertThrowsError(try store.load(tvID: point.tvID))
    }

    func testADBKeyEncodingAndRawAuthenticationSignature() throws {
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048
        ]
        var creationError: Unmanaged<CFError>?
        let privateKey = try XCTUnwrap(SecKeyCreateRandomKey(attributes as CFDictionary, &creationError))
        let publicKey = try XCTUnwrap(SecKeyCopyPublicKey(privateKey))
        var exportError: Unmanaged<CFError>?
        let der = try XCTUnwrap(SecKeyCopyExternalRepresentation(publicKey, &exportError) as Data?)
        let parts = try ADBPublicKeyCodec.rsaParts(fromDER: der)
        let adbKey = try ADBPublicKeyCodec.encode(modulus: parts.modulus, exponent: parts.exponent)
        XCTAssertEqual(adbKey.count, 524)
        XCTAssertEqual(readLE32(adbKey, 0), 64)
        let lowModulusWord = UInt32(parts.modulus[255])
            | (UInt32(parts.modulus[254]) << 8)
            | (UInt32(parts.modulus[253]) << 16)
            | (UInt32(parts.modulus[252]) << 24)
        XCTAssertEqual(
            readLE32(adbKey, 4) &* lowModulusWord,
            0 &- 1,
            "n0inv \(readLE32(adbKey, 4)), low modulus word \(lowModulusWord)"
        )

        let token = Data(repeating: 0x5A, count: 20)
        let digestInfo: [UInt8] = [
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        ]
        let signedInput = Data(digestInfo) + token
        var signingError: Unmanaged<CFError>?
        let signature = try XCTUnwrap(
            SecKeyCreateSignature(privateKey, .rsaSignatureRaw, signedInput as CFData, &signingError) as Data?
        )
        var verificationError: Unmanaged<CFError>?
        XCTAssertTrue(
            SecKeyVerifySignature(publicKey, .rsaSignatureRaw, signedInput as CFData, signature as CFData, &verificationError)
        )
        XCTAssertEqual(signature.count, 256)
    }

    private func readLE32(_ data: Data, _ offset: Int) -> UInt32 {
        UInt32(data[offset])
            | (UInt32(data[offset + 1]) << 8)
            | (UInt32(data[offset + 2]) << 16)
            | (UInt32(data[offset + 3]) << 24)
    }
}
