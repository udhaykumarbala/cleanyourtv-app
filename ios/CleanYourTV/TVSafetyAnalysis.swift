import Foundation
import CryptoKit

enum TVAnalysisStatus: String, Codable, Equatable {
    case alreadyDisabled
    case protected
    case optional
    case reviewCandidate

    var title: String {
        switch self {
        case .alreadyDisabled: "Already off"
        case .protected: "Kept safe"
        case .optional: "Optional · kept by default"
        case .reviewCandidate: "Review candidate"
        }
    }
}

struct TVAnalysisRow: Identifiable {
    let package: String
    let note: String?
    let status: TVAnalysisStatus
    let reason: String?

    var id: String { package }
}

struct TVAnalysisGroup: Identifiable {
    let id: String
    let title: String
    let rows: [TVAnalysisRow]
}

struct TVSafetyReport {
    let groups: [TVAnalysisGroup]
    let incompleteReasons: [String]

    var rows: [TVAnalysisRow] { groups.flatMap(\.rows) }
    var candidateCount: Int { rows.filter { $0.status == .reviewCandidate }.count }
    var protectedCount: Int { rows.filter { $0.status == .protected }.count }
    var optionalCount: Int { rows.filter { $0.status == .optional }.count }
    var alreadyDisabledCount: Int { rows.filter { $0.status == .alreadyDisabled }.count }
}

enum TVSafetyAnalyzer {
    private static let policyVersion = "safety-analysis-v1"

    private static let exactProtections: [String: String] = [
        "android": "Android framework",
        "com.android.systemui": "Android system interface",
        "com.android.settings": "TV Settings",
        "com.android.tv.settings": "TV Settings",
        "com.android.packageinstaller": "App installer",
        "com.google.android.packageinstaller": "App installer",
        "com.android.permissioncontroller": "Permission manager",
        "com.google.android.permissioncontroller": "Permission manager",
        "com.android.providers.settings": "System settings storage",
        "com.android.providers.tv": "TV channels and input storage",
        "com.android.providers.media": "Media storage",
        "com.android.providers.media.module": "Media storage",
        "com.google.android.providers.media.module": "Media storage",
        "com.android.shell": "ADB shell used by this connection",
        "com.android.adbd": "ADB service used by this connection",
        "com.android.keychain": "Certificate storage",
        "com.android.se": "Secure element service",
        "com.android.location.fused": "Core location service",
        "com.android.bluetooth": "Remote pairing and Bluetooth",
        "com.google.android.tv.btservices": "Remote pairing and Bluetooth",
        "com.droidlogic.ble": "Remote pairing and Bluetooth",
        "com.droidlogic": "TV chipset service",
        "com.droidlogic.tvinput": "TV input service",
        "com.droidlogic.tv.settings": "TV Settings",
        "com.droidlogic.overlay": "TV chipset service",
        "com.google.android.gms": "Google Play services",
        "com.google.android.gsf": "Google Play services framework",
        "com.android.vending": "Google Play Store"
    ]

    private static let protectedPrefixes: [(String, String)] = [
        ("com.android.networkstack", "TV network connectivity"),
        ("com.android.wifi.", "TV Wi-Fi connectivity"),
        ("com.android.providers.media.", "Media storage")
    ]

    private static let knownStockLaunchers: Set<String> = [
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
        "com.google.android.apps.tv.launcherx",
        "com.amazon.tv.launcher",
        "com.amazon.firehomestarter"
    ]

    static func analyze(
        identity: TVIdentity,
        resolution: TVProfileResolution,
        snapshot: TVSafetySnapshot
    ) -> TVSafetyReport {
        let packages = Dictionary(snapshot.packages.map { ($0.name, $0) }, uniquingKeysWith: { first, _ in first })
        let configuration = snapshot.configuration
        let settings = Dictionary(
            configuration.settings.map { ($0.setting, $0.value) },
            uniquingKeysWith: { first, _ in first }
        )
        let homePackages = Set(
            ([configuration.homeComponent].compactMap { $0 } + configuration.homeClaimants)
                .compactMap(packageName(from:))
        )
        let imePackage = configuration.inputMethod.flatMap(packageName(from:))
        let screensaverSetting = settings[.screensaverComponents] ?? nil
        let screensaverPackages = Set(
            TVSetting.componentList(screensaverSetting ?? "")?.compactMap(packageName(from:)) ?? []
        )
        let fireOS = resolution.platform == "FIRE_OS"
            || identity.brand.caseInsensitiveCompare("amazon") == .orderedSame
            || identity.manufacturer.caseInsensitiveCompare("amazon") == .orderedSame
            || identity.model.uppercased().hasPrefix("AFT")
            || packages["com.amazon.tv.launcher"] != nil
            || packages["com.amazon.firehomestarter"] != nil
        let stockLaunchers = knownStockLaunchers.union(resolution.stockLauncherPackages)

        var incompleteReasons: [String] = []
        if !configuration.inputServiceAvailable {
            incompleteReasons.append("The TV input service could not be read.")
        }
        if configuration.homeComponent == nil || configuration.homeClaimants.isEmpty {
            incompleteReasons.append("The current HOME app and its claimants could not be fully confirmed.")
        }

        var seen = Set<String>()
        let groups = resolution.groups.compactMap { group -> TVAnalysisGroup? in
            let rows = group.packages.compactMap { entry -> TVAnalysisRow? in
                guard seen.insert(entry.name).inserted,
                      let package = packages[entry.name] else { return nil }

                if package.isDisabled {
                    return TVAnalysisRow(package: entry.name, note: entry.note, status: .alreadyDisabled, reason: nil)
                }
                if group.default == "KEEP" {
                    return TVAnalysisRow(
                        package: entry.name,
                        note: entry.note,
                        status: .protected,
                        reason: "This profile keeps this package enabled."
                    )
                }
                if let reason = protectedReason(
                    package: package,
                    profile: resolution,
                    homePackages: homePackages,
                    imePackage: imePackage,
                    screensaverPackages: screensaverPackages,
                    stockLaunchers: stockLaunchers,
                    fireOS: fireOS
                ) {
                    return TVAnalysisRow(package: entry.name, note: entry.note, status: .protected, reason: reason)
                }
                if let reason = incompleteStateReason(package: package, configuration: configuration) {
                    return TVAnalysisRow(package: entry.name, note: entry.note, status: .protected, reason: reason)
                }
                if !configuration.inputServiceAvailable {
                    return TVAnalysisRow(
                        package: entry.name,
                        note: entry.note,
                        status: .protected,
                        reason: "Safety check incomplete: TV input roles are unknown."
                    )
                }
                if group.default != "DISABLE" {
                    return TVAnalysisRow(package: entry.name, note: entry.note, status: .optional, reason: nil)
                }
                if !incompleteReasons.isEmpty {
                    return TVAnalysisRow(
                        package: entry.name,
                        note: entry.note,
                        status: .protected,
                        reason: "Safety snapshot incomplete; no change is recommended."
                    )
                }
                return TVAnalysisRow(package: entry.name, note: entry.note, status: .reviewCandidate, reason: nil)
            }
            guard !rows.isEmpty else { return nil }
            return TVAnalysisGroup(id: group.id, title: group.title, rows: rows)
        }
        return TVSafetyReport(groups: groups, incompleteReasons: incompleteReasons)
    }

    static func snapshotDigest(
        identity: TVIdentity,
        resolution: TVProfileResolution,
        snapshot: TVSafetySnapshot
    ) -> String {
        let profileKind: String
        switch resolution.kind {
        case .exact: profileKind = "exact"
        case .brandSureSet: profileKind = "brand-sure-set"
        case .generic: profileKind = "generic"
        }
        var fields = [
            policyVersion,
            identity.tvID,
            identity.brand,
            identity.manufacturer,
            identity.model,
            identity.device,
            String(identity.sdk),
            identity.release,
            identity.fingerprint,
            identity.abis.joined(separator: ","),
            identity.locale,
            optionalFlag(identity.adbSecure),
            profileKind,
            resolution.profileID,
            resolution.title,
            resolution.detail,
            resolution.platform ?? "",
            resolution.setupWizard ?? ""
        ]
        fields.append(contentsOf: resolution.quirks.sorted())
        fields.append(contentsOf: resolution.stockLauncherPackages.sorted())
        fields.append(contentsOf: resolution.neverDisable.sorted())
        for group in resolution.groups.sorted(by: { $0.id < $1.id }) {
            fields += [group.id, group.title, group.default]
            for entry in group.packages.sorted(by: { $0.name < $1.name }) {
                fields += [entry.name, entry.note ?? ""]
            }
        }
        for package in snapshot.packages.sorted(by: { $0.name < $1.name }) {
            fields += [
                package.name,
                package.isDisabled ? "1" : "0",
                optionalFlag(package.isSystem),
                optionalFlag(package.isPersistent),
                package.isTVInput ? "1" : "0",
                optionalFlag(package.requestsTVInputHardware)
            ]
        }
        let configuration = snapshot.configuration
        fields.append(configuration.homeComponent ?? "")
        fields += configuration.homeClaimants.sorted()
        fields += configuration.inputPackages.sorted()
        fields.append(configuration.inputServiceAvailable ? "inputs:available" : "inputs:missing")
        fields.append(configuration.inputMethod ?? "")
        for setting in configuration.settings.sorted(by: { $0.setting.rawValue < $1.setting.rawValue }) {
            fields += [setting.setting.rawValue, setting.value ?? "<unset>"]
        }
        for (name, metadata) in configuration.packageMetadata.sorted(by: { $0.key < $1.key }) {
            fields += [
                name,
                optionalFlag(metadata.isSystem),
                optionalFlag(metadata.isPersistent),
                optionalFlag(metadata.isInstalled)
            ]
        }
        let canonical = Data(fields.map { "\($0.utf8.count):\($0)" }.joined(separator: "|").utf8)
        return SHA256.hash(data: canonical).map { String(format: "%02x", $0) }.joined()
    }

    private static func optionalFlag(_ value: Bool?) -> String {
        switch value {
        case .some(true): "1"
        case .some(false): "0"
        case .none: "?"
        }
    }

    private static func protectedReason(
        package: TVPackage,
        profile: TVProfileResolution,
        homePackages: Set<String>,
        imePackage: String?,
        screensaverPackages: Set<String>,
        stockLaunchers: Set<String>,
        fireOS: Bool
    ) -> String? {
        let name = package.name
        if let label = exactProtections[name] { return "Protected: \(label)." }
        if let prefix = protectedPrefixes.first(where: { name.hasPrefix($0.0) }) {
            return "Protected: \(prefix.1)."
        }
        if name == "com.google.android.tungsten.setupwraith" || name == profile.setupWizard {
            return "Protected: first-run setup wizard."
        }
        if package.isPersistent == true { return "Protected: Android marks this service persistent." }
        if package.isTVInput { return "Protected: provides a TV input." }
        if profile.neverDisable.contains(name) { return "Protected by this TV profile." }
        if imePackage == name { return "Protected: active TV keyboard." }
        if screensaverPackages.contains(name) { return "Protected: selected screensaver." }
        if fireOS && (name == "com.amazon.tv.launcher" || name == "com.amazon.firehomestarter") {
            return "Protected: Fire TV's own HOME app."
        }
        if stockLaunchers.contains(name) { return "Protected: known stock HOME app." }
        if homePackages.contains(name) { return "Protected: currently resolves or responds to HOME." }
        return nil
    }

    private static func incompleteStateReason(
        package: TVPackage,
        configuration: TVConfigurationSnapshot
    ) -> String? {
        guard let permissionRequest = package.requestsTVInputHardware else {
            return "Safety check incomplete: TV_INPUT_HARDWARE permission could not be verified."
        }
        if permissionRequest {
            return "Protected: requests the TV_INPUT_HARDWARE permission."
        }
        guard let metadata = configuration.packageMetadata[package.name],
              metadata.isInstalled == true,
              metadata.isPersistent != nil else {
            return "Safety check incomplete: Android package flags are unavailable."
        }
        return nil
    }

    private static func packageName(from component: String) -> String? {
        guard let separator = component.firstIndex(of: "/") else { return nil }
        return String(component[..<separator])
    }
}
