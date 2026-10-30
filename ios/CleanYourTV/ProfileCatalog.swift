import Foundation

struct TVCatalogEntry: Decodable {
    let name: String
    let note: String?
}

struct TVCatalogGroup: Decodable, Identifiable {
    let id: String
    let title: String
    let `default`: String
    let packages: [TVCatalogEntry]
}

struct TVCatalogMatch: Decodable {
    let models: [String]
    let devices: [String]
    let brand: String?
    let sdkMin: Int?
    let sdkMax: Int?
    let fingerprintPrefix: String?
}

struct TVCatalogProfile: Decodable, Identifiable {
    let id: String
    let match: TVCatalogMatch
    let platform: String
    let stockLaunchers: [String]
    let setupWraith: String?
    let quirks: [String]
    let groups: [TVCatalogGroup]
    let neverDisable: [String]
}

struct TVCatalogSureSet: Decodable {
    let brand: String
    let groups: [TVCatalogGroup]
    let stockLaunchers: [String]
}

struct TVProfileResolution {
    enum Kind: Equatable {
        case exact
        case brandSureSet
        case generic
    }

    let kind: Kind
    let profileID: String
    let title: String
    let detail: String
    let groups: [TVCatalogGroup]
    let platform: String?
    let stockLauncherPackages: Set<String>
    let setupWizard: String?
    let quirks: Set<String>
    let neverDisable: Set<String>
}

struct TVProfileCatalog {
    private struct BundleDocument: Decodable {
        let schema: Int
        let profiles: [TVCatalogProfile]
        let sureSets: [TVCatalogSureSet]
    }

    private let profiles: [TVCatalogProfile]
    private let sureSets: [TVCatalogSureSet]

    init(profiles: [TVCatalogProfile], sureSets: [TVCatalogSureSet]) {
        self.profiles = profiles
        self.sureSets = sureSets
    }

    static func loadBundled() throws -> TVProfileCatalog {
        guard let url = Bundle.main.url(forResource: "profiles", withExtension: "json") else {
            throw TVProfileCatalogError.catalogMissing
        }
        let data = try Data(contentsOf: url)
        guard !data.isEmpty, data.count <= 4 * 1024 * 1024 else {
            throw TVProfileCatalogError.catalogSize
        }
        let document = try JSONDecoder().decode(BundleDocument.self, from: data)
        guard document.schema == 1,
              !document.profiles.isEmpty,
              document.profiles.count <= 500,
              Set(document.profiles.map(\.id)).count == document.profiles.count else {
            throw TVProfileCatalogError.catalogInvalid
        }
        return TVProfileCatalog(profiles: document.profiles, sureSets: document.sureSets)
    }

    func resolve(identity: TVIdentity) -> TVProfileResolution {
        let exact = profiles.compactMap { profile -> (score: Int, profile: TVCatalogProfile)? in
            let modelMatches = profile.match.models.contains { $0.caseInsensitiveCompare(identity.model) == .orderedSame }
            let deviceMatches = profile.match.devices.contains { $0.caseInsensitiveCompare(identity.device) == .orderedSame }
            guard modelMatches || deviceMatches else { return nil }

            if let brand = profile.match.brand,
               !matchesBrand(brand, identity: identity) { return nil }
            if let sdkMin = profile.match.sdkMin, identity.sdk < sdkMin { return nil }
            if let sdkMax = profile.match.sdkMax, identity.sdk > sdkMax { return nil }
            if let prefix = profile.match.fingerprintPrefix,
               !identity.fingerprint.hasPrefix(prefix) { return nil }

            return ((modelMatches ? 2 : 0) + (deviceMatches ? 1 : 0), profile)
        }
        .sorted {
            if $0.score != $1.score { return $0.score > $1.score }
            let leftSpecificity = $0.profile.match.fingerprintPrefix?.count ?? 0
            let rightSpecificity = $1.profile.match.fingerprintPrefix?.count ?? 0
            if leftSpecificity != rightSpecificity { return leftSpecificity > rightSpecificity }
            return $0.profile.id < $1.profile.id
        }
        .first?.profile

        if let exact {
            return TVProfileResolution(
                kind: .exact,
                profileID: exact.id,
                title: exact.id.replacingOccurrences(of: "-", with: " ").capitalized,
                detail: "Exact model or device match from the community catalog.",
                groups: exact.groups,
                platform: exact.platform,
                stockLauncherPackages: Set(exact.stockLaunchers.compactMap { $0.split(separator: "/", maxSplits: 1).first.map(String.init) }),
                setupWizard: exact.setupWraith,
                quirks: Set(exact.quirks),
                neverDisable: Set(exact.neverDisable)
            )
        }

        if let sureSet = sureSets.first(where: { matchesBrand($0.brand, identity: identity) }) {
            return TVProfileResolution(
                kind: .brandSureSet,
                profileID: "brand:\(sureSet.brand.lowercased())",
                title: "\(sureSet.brand) brand profile",
                detail: "Conservative brand-wide set; no exact model profile matched.",
                groups: sureSet.groups,
                platform: nil,
                stockLauncherPackages: Set(sureSet.stockLaunchers.compactMap { $0.split(separator: "/", maxSplits: 1).first.map(String.init) }),
                setupWizard: nil,
                quirks: [],
                neverDisable: []
            )
        }

        return TVProfileResolution(
            kind: .generic,
            profileID: "generic",
            title: "No matching TV profile",
            detail: "The catalog has no exact model or brand-wide profile for this TV.",
            groups: [],
            platform: nil,
            stockLauncherPackages: ["com.google.android.tvlauncher", "com.google.android.leanbacklauncher", "com.google.android.apps.tv.launcherx"],
            setupWizard: nil,
            quirks: [],
            neverDisable: []
        )
    }

    private func matchesBrand(_ brand: String, identity: TVIdentity) -> Bool {
        [identity.brand, identity.manufacturer]
            .contains { $0.caseInsensitiveCompare(brand) == .orderedSame }
    }
}

enum TVProfileCatalogError: LocalizedError {
    case catalogMissing
    case catalogSize
    case catalogInvalid

    var errorDescription: String? {
        switch self {
        case .catalogMissing: "The bundled TV profile catalog is missing."
        case .catalogSize: "The bundled TV profile catalog has an invalid size."
        case .catalogInvalid: "The bundled TV profile catalog is invalid."
        }
    }
}
