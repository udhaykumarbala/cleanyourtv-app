import CryptoKit
import Foundation

// Fire OS ignores `set-home-activity`, so on a Fire TV the launcher becomes the home screen through a small
// accessibility service (firetv-home-interceptor/) that opens it when Amazon's Home appears. Both APKs are
// bundled and pinned by SHA-256; nothing else is ever installed or removed by this feature.
enum TVHomeInterceptor {
    static let launcherPackage = "com.gathin.tvhome"
    static let interceptorPackage = "tv.cleanyour.homeinterceptor"
    static let service = "tv.cleanyour.homeinterceptor/.HomeService"
    static let packages: Set<String> = [launcherPackage, interceptorPackage]
    static let themeURI = "content://\(launcherPackage).theme"

    struct Bundled {
        let resource: String
        let sha256: String
    }

    static let launcher = Bundled(
        resource: "home-launcher",
        sha256: "31144f5a938223032dee573629b231b2041dad16068a0c344488128f5b4a1fee"
    )
    static let interceptor = Bundled(
        resource: "home-interceptor",
        sha256: "9f433a7b2921430038bb1fdfd4ff57ad41d414c335a8d56d4268311d86401c8c"
    )

    enum Failure: LocalizedError {
        case missing(String)
        case digestMismatch(String)

        var errorDescription: String? {
            switch self {
            case .missing(let name): "The bundled \(name) file is missing from the app."
            case .digestMismatch(let name): "The bundled \(name) file does not match its pinned digest."
            }
        }
    }

    static func load(_ item: Bundled, bundle: Bundle = .main) throws -> Data {
        guard let url = bundle.url(forResource: item.resource, withExtension: "apk"),
              let data = try? Data(contentsOf: url) else { throw Failure.missing(item.resource) }
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        guard digest == item.sha256 else { throw Failure.digestMismatch(item.resource) }
        return data
    }

    // Fire TV by the same facts the Android planner uses: Amazon as maker or brand, or an AFT model code.
    static func isFireTV(_ identity: TVIdentity, platform: String?) -> Bool {
        if platform?.caseInsensitiveCompare("FIRE_OS") == .orderedSame { return true }
        let amazon = { (value: String) in value.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare("amazon") == .orderedSame }
        return amazon(identity.manufacturer) || amazon(identity.brand)
            || identity.model.trimmingCharacters(in: .whitespaces).uppercased().hasPrefix("AFT")
    }

    static func services(in value: String?) -> [String] {
        value.flatMap { TVSetting.componentList($0) } ?? []
    }

    static func isEnabled(in value: String?) -> Bool { services(in: value).contains(service) }

    // Keeps every service the person already had; only ours is added or taken out.
    static func enabling(in value: String?) -> String {
        let current = services(in: value)
        return (current.contains(service) ? current : current + [service]).joined(separator: ":")
    }

    static func disabling(in value: String?) -> String {
        services(in: value).filter { $0 != service }.joined(separator: ":")
    }
}

struct TVHomeSwitchStatus: Equatable {
    let launcherInstalled: Bool
    let interceptorInstalled: Bool
    let serviceEnabled: Bool

    init(packages: Set<String>, services: String?) {
        launcherInstalled = packages.contains(TVHomeInterceptor.launcherPackage)
        interceptorInstalled = packages.contains(TVHomeInterceptor.interceptorPackage)
        serviceEnabled = TVHomeInterceptor.isEnabled(in: services)
    }

    var isActive: Bool { launcherInstalled && interceptorInstalled && serviceEnabled }
    var hasAnyPart: Bool { launcherInstalled || interceptorInstalled || serviceEnabled }
}
