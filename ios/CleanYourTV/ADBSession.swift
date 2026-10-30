import CryptoKit
import CoreFoundation
import Foundation
import Network
import Security

struct TVIdentity {
    let brand: String
    let manufacturer: String
    let model: String
    let device: String
    let serialLast4: String
    let sdk: Int
    let release: String
    let fingerprint: String
    let abis: [String]
    let locale: String
    let adbSecure: Bool?
    let tvID: String

    static func parse(properties: String) throws -> TVIdentity {
        guard properties.utf8.count <= 256 * 1024 else {
            throw ADBSessionError.protocolViolation("getprop output exceeds the size limit")
        }
        var values: [String: String] = [:]
        for (index, line) in properties.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
            guard line.utf8.count <= 8192 else {
                throw ADBSessionError.protocolViolation("getprop line \(index + 1) exceeds the size limit")
            }
            guard line.first == "[", let keyEnd = line.range(of: "]: ["), line.last == "]" else { continue }
            let key = String(line[line.index(after: line.startIndex)..<keyEnd.lowerBound])
            let valueStart = keyEnd.upperBound
            let value = String(line[valueStart..<line.index(before: line.endIndex)])
            guard !key.isEmpty, key.utf8.count <= 256, value.utf8.count <= 4096 else {
                throw ADBSessionError.protocolViolation("getprop contains an oversized property")
            }
            values[key] = value
        }
        guard let serial = values["ro.serialno"], !serial.isEmpty,
              let device = values["ro.product.device"], !device.isEmpty,
              let sdk = Int(values["ro.build.version.sdk"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""),
              sdk > 0 else {
            throw ADBSessionError.invalidIdentity
        }
        let adbSecure: Bool?
        switch values["ro.adb.secure"] {
        case "1": adbSecure = true
        case "0": adbSecure = false
        default: adbSecure = nil
        }
        let locale = values["persist.sys.locale"] ?? ""
        let hash = SHA256.hash(data: Data((serial + device).utf8))
        return TVIdentity(
            brand: values["ro.product.brand"] ?? "",
            manufacturer: values["ro.product.manufacturer"] ?? "",
            model: values["ro.product.model"] ?? "",
            device: device,
            serialLast4: String(serial.suffix(4)),
            sdk: sdk,
            release: values["ro.build.version.release"] ?? "",
            fingerprint: values["ro.build.fingerprint"] ?? "",
            abis: (values["ro.product.cpu.abilist"] ?? "").split(separator: ",").map(String.init),
            locale: locale.isEmpty ? (values["ro.product.locale"] ?? "") : locale,
            adbSecure: adbSecure,
            tvID: hash.prefix(8).map { String(format: "%02x", $0) }.joined()
        )
    }
}

enum TVSetting: String, CaseIterable, Identifiable {
    case screensaverEnabled
    case screensaverComponents
    case enabledAccessibilityServices
    case accessibilityEnabled
    case adbEnabled
    case adbWifiEnabled
    case deviceName

    var id: String { rawValue }

    var namespace: String {
        switch self {
        case .screensaverEnabled, .screensaverComponents, .enabledAccessibilityServices, .accessibilityEnabled:
            "secure"
        case .adbEnabled, .adbWifiEnabled, .deviceName:
            "global"
        }
    }

    var key: String {
        switch self {
        case .screensaverEnabled: "screensaver_enabled"
        case .screensaverComponents: "screensaver_components"
        case .enabledAccessibilityServices: "enabled_accessibility_services"
        case .accessibilityEnabled: "accessibility_enabled"
        case .adbEnabled: "adb_enabled"
        case .adbWifiEnabled: "adb_wifi_enabled"
        case .deviceName: "device_name"
        }
    }

    var title: String {
        switch self {
        case .screensaverEnabled: "Screensaver enabled"
        case .screensaverComponents: "Screensaver app"
        case .enabledAccessibilityServices: "Accessibility services"
        case .accessibilityEnabled: "Accessibility enabled"
        case .adbEnabled: "ADB enabled"
        case .adbWifiEnabled: "ADB over Wi-Fi enabled"
        case .deviceName: "TV name"
        }
    }

    func accepts(_ value: String) -> Bool {
        switch self {
        case .screensaverEnabled, .accessibilityEnabled, .adbEnabled, .adbWifiEnabled:
            value == "0" || value == "1"
        case .screensaverComponents, .enabledAccessibilityServices:
            value.isEmpty || Self.isComponentList(value)
        case .deviceName:
            (1...256).contains(value.utf8.count)
                && value.unicodeScalars.allSatisfy { scalar in
                    switch scalar.properties.generalCategory {
                    case .control, .format, .lineSeparator, .paragraphSeparator, .surrogate, .privateUse, .unassigned:
                        false
                    default:
                        true
                    }
                }
        }
    }

    private static func isComponentList(_ value: String) -> Bool {
        let components = value.split(separator: ":", omittingEmptySubsequences: false)
        guard !components.isEmpty, components.count <= 16 else { return false }
        return components.allSatisfy { isComponent(String($0)) }
    }

    static func componentList(_ value: String) -> [String]? {
        guard value.isEmpty || isComponentList(value) else { return nil }
        if value.isEmpty { return [] }
        return value.split(separator: ":").map(String.init)
    }

    static func isComponent(_ value: String) -> Bool {
        let pieces = value.split(separator: "/", omittingEmptySubsequences: false)
        guard pieces.count == 2 else { return false }
        let package = String(pieces[0])
        let className = String(pieces[1])
        let segments = package.split(separator: ".", omittingEmptySubsequences: false)
        guard package.utf8.count <= 255, className.utf8.count <= 200,
              package == "android" || (
            (2...16).contains(segments.count)
                && segments.allSatisfy { segment in
                    guard let first = segment.first, first.isASCII, first.isLetter, segment.count <= 64 else { return false }
                    return segment.dropFirst().allSatisfy {
                        $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_")
                    }
                }
        ) else { return false }
        let classBody = className.first == "." ? className.dropFirst() : Substring(className)
        guard let first = classBody.first, first.isASCII, first.isLetter || first == "_" else { return false }
        return classBody.count <= 200 && classBody.dropFirst().allSatisfy {
            $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_" || $0 == "$" || $0 == ".")
        }
    }
}

struct TVSettingValue: Identifiable, Hashable {
    let setting: TVSetting
    let value: String?
    var id: TVSetting { setting }
}

struct TVConfigurationSnapshot {
    let homeComponent: String?
    let homeClaimants: [String]
    let settings: [TVSettingValue]
    let inputPackages: Set<String>
    let inputServiceAvailable: Bool
    let inputMethod: String?
    let packageMetadata: [String: TVPackageMetadata]
}

struct TVPackageMetadata {
    let isSystem: Bool?
    let isPersistent: Bool?
    let isInstalled: Bool?
}

enum TVConfigurationParser {
    static func homeComponent(from output: String) throws -> String? {
        guard output.utf8.count <= 64 * 1024 else {
            throw ADBSessionError.protocolViolation("HOME resolver output exceeds the size limit")
        }
        let lines = output.split(separator: "\n", omittingEmptySubsequences: false)
        guard lines.allSatisfy({ $0.utf8.count <= 8192 }) else {
            throw ADBSessionError.protocolViolation("HOME resolver line exceeds the size limit")
        }
        var resolved: String?
        for line in lines {
            let candidate = line.trimmingCharacters(in: .whitespacesAndNewlines)
            if candidate.isEmpty || candidate.hasPrefix("priority=") || candidate == "No activity found" { continue }
            guard TVSetting.isComponent(candidate) else {
                throw ADBSessionError.protocolViolation("HOME resolver returned an invalid component")
            }
            resolved = candidate
        }
        return resolved
    }

    static func homeClaimants(from output: String) throws -> [String] {
        guard output.utf8.count <= 64 * 1024 else {
            throw ADBSessionError.protocolViolation("HOME claimant output exceeds the size limit")
        }
        let lines = output.split(separator: "\n", omittingEmptySubsequences: false)
        guard lines.allSatisfy({ $0.utf8.count <= 8192 }) else {
            throw ADBSessionError.protocolViolation("HOME claimant line exceeds the size limit")
        }
        var hasPriority = false
        var claimants: [String] = []
        for line in lines {
            let raw = String(line).trimmingCharacters(in: .whitespacesAndNewlines)
            let first = raw.split(separator: " ", maxSplits: 1).first.map(String.init) ?? ""
            if first.hasPrefix("priority="), Int(first.dropFirst("priority=".count)) != nil {
                hasPriority = true
                continue
            }
            guard hasPriority else { continue }
            hasPriority = false
            guard TVSetting.isComponent(raw) else {
                throw ADBSessionError.protocolViolation("HOME claimant output contains an invalid component")
            }
            if !claimants.contains(raw) { claimants.append(raw) }
        }
        return claimants
    }

    static func packageMetadata(from output: String) throws -> [String: TVPackageMetadata] {
        guard output.utf8.count <= 4 * 1024 * 1024 else {
            throw ADBSessionError.protocolViolation("package state output exceeds the size limit")
        }
        var result: [String: TVPackageMetadata] = [:]
        var currentName: String?
        var system: Bool?
        var persistent: Bool?
        var installed: Bool?

        func saveCurrent() {
            guard let currentName, result[currentName] == nil else { return }
            result[currentName] = TVPackageMetadata(
                isSystem: system,
                isPersistent: persistent,
                isInstalled: installed
            )
        }

        for (index, rawLine) in output.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
            guard rawLine.utf8.count <= 8192 else {
                throw ADBSessionError.protocolViolation("package state line \(index + 1) exceeds the size limit")
            }
            let line = String(rawLine)
            if line.hasPrefix("Hidden system packages:") { break }
            if let marker = line.range(of: "Package ["), line.hasPrefix(" "),
               let end = line[marker.upperBound...].range(of: "] (") {
                saveCurrent()
                let name = String(line[marker.upperBound..<end.lowerBound])
                guard TVPackageListParser.isValidPackageName(name) else {
                    throw ADBSessionError.protocolViolation("package state contains an invalid package name")
                }
                currentName = name
                system = nil
                persistent = nil
                installed = nil
                continue
            }
            guard currentName != nil else { continue }
            if let marker = line.range(of: "pkgFlags=["), let end = line[marker.upperBound...].firstIndex(of: "]") {
                let flags = Set(line[marker.upperBound..<end].split(whereSeparator: \.isWhitespace).map(String.init))
                system = flags.contains("SYSTEM")
                persistent = flags.contains("PERSISTENT")
            }
            if line.trimmingCharacters(in: .whitespaces).hasPrefix("User 0:") {
                for field in line.split(whereSeparator: \.isWhitespace) {
                    let pair = field.split(separator: "=", maxSplits: 1)
                    guard pair.count == 2 else { continue }
                    if pair[0] == "installed" {
                        if pair[1] == "true" { installed = true }
                        else if pair[1] == "false" { installed = false }
                    }
                }
            }
        }
        saveCurrent()
        return result
    }

    static func requestsTVInputHardware(from output: String) throws -> Bool? {
        guard output.utf8.count <= 256 * 1024 else {
            throw ADBSessionError.protocolViolation("package permission output exceeds the size limit")
        }
        let lines = output.split(separator: "\n", omittingEmptySubsequences: false)
        guard lines.allSatisfy({ $0.utf8.count <= 8192 }) else {
            throw ADBSessionError.protocolViolation("package permission line exceeds the size limit")
        }
        var inRequestedPermissions = false
        var sawRequestedPermissions = false
        for rawLine in lines {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line == "requested permissions:" {
                inRequestedPermissions = true
                sawRequestedPermissions = true
            } else if line.hasSuffix("permissions:")
                || line.hasPrefix("User ")
                || line.hasPrefix("Package [") {
                inRequestedPermissions = false
            } else if inRequestedPermissions
                        && (line == "android.permission.TV_INPUT_HARDWARE"
                            || line.hasPrefix("android.permission.TV_INPUT_HARDWARE:")) {
                return true
            }
        }
        return sawRequestedPermissions ? false : nil
    }

    static func tvInputPackages(from output: String) throws -> (packages: Set<String>, available: Bool) {
        guard output.utf8.count <= 2 * 1024 * 1024 else {
            throw ADBSessionError.protocolViolation("TV input output exceeds the size limit")
        }
        let lines = output.split(separator: "\n", omittingEmptySubsequences: false)
        guard lines.allSatisfy({ $0.utf8.count <= 8192 }) else {
            throw ADBSessionError.protocolViolation("TV input line exceeds the size limit")
        }
        if output.contains("Can't find service") || output.contains("does not exist") {
            return ([], false)
        }

        var packages = Set<String>()
        for line in lines {
            var cursor = line.startIndex
            while let start = line.range(of: "TvInputInfo{id=", range: cursor..<line.endIndex) {
                guard let packageMarker = line.range(of: ", pkg=", range: start.upperBound..<line.endIndex),
                      let serviceMarker = line.range(of: ", service=", range: packageMarker.upperBound..<line.endIndex) else {
                    throw ADBSessionError.protocolViolation("TV input record is malformed")
                }
                let packageName = String(line[packageMarker.upperBound..<serviceMarker.lowerBound])
                guard TVPackageListParser.isValidPackageName(packageName) else {
                    throw ADBSessionError.protocolViolation("TV input record contains an invalid package name")
                }
                packages.insert(packageName)
                cursor = serviceMarker.upperBound
            }
        }
        return (packages, !packages.isEmpty)
    }

    static func settingValue(from output: String, setting: TVSetting) throws -> String? {
        guard output.utf8.count <= 32 * 1024 else {
            throw ADBSessionError.protocolViolation("\(setting.key) output exceeds the size limit")
        }
        let lines = output.split(separator: "\n", omittingEmptySubsequences: false)
        guard lines.count <= 2, lines.allSatisfy({ $0.utf8.count <= 8192 }) else {
            throw ADBSessionError.protocolViolation("\(setting.key) returned an invalid value")
        }
        let value = output.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty || value == "null" { return nil }
        guard setting.accepts(value) else {
            throw ADBSessionError.protocolViolation("\(setting.key) returned a value outside its safe format")
        }
        return value
    }
}

struct TVPackage: Identifiable, Hashable {
    let name: String
    let isDisabled: Bool
    let isSystem: Bool?
    let isPersistent: Bool?
    let isTVInput: Bool
    let requestsTVInputHardware: Bool?

    var id: String { name }
}

struct TVSafetySnapshot {
    let packages: [TVPackage]
    let configuration: TVConfigurationSnapshot
}

enum TVPackageListParser {
    static func parse(installed: String, disabled: String) throws -> [TVPackage] {
        let all = try packageNames(installed, allowEmpty: false)
        let off = Set(try packageNames(disabled, allowEmpty: true))
        guard off.isSubset(of: Set(all)) else {
            throw ADBSessionError.protocolViolation("disabled package list does not match installed packages")
        }
        return all.map {
            TVPackage(
                name: $0,
                isDisabled: off.contains($0),
                isSystem: nil,
                isPersistent: nil,
                isTVInput: false,
                requestsTVInputHardware: nil
            )
        }
    }

    private static func packageNames(_ text: String, allowEmpty: Bool) throws -> [String] {
        var names: [String] = []
        var seen = Set<String>()
        for (index, rawLine) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
            let line = String(rawLine).trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }
            guard line.utf8.count <= 8192, line.hasPrefix("package:") else {
                throw ADBSessionError.protocolViolation("unexpected package list line \(index + 1)")
            }
            let body = line.dropFirst("package:".count)
            let name: String
            if let equals = body.lastIndex(of: "="), body.first == "/" {
                name = String(body[body.index(after: equals)...])
            } else {
                name = String(body)
            }
            guard isValidPackageName(name) else {
                throw ADBSessionError.protocolViolation("invalid package name in TV response")
            }
            if seen.insert(name).inserted { names.append(name) }
        }
        if !allowEmpty, names.isEmpty {
            throw ADBSessionError.protocolViolation("TV returned no installed packages")
        }
        return names
    }

    static func isValidPackageName(_ value: String) -> Bool {
        if value == "android" { return true }
        let segments = value.split(separator: ".", omittingEmptySubsequences: false)
        guard (2...16).contains(segments.count), value.utf8.count <= 255 else { return false }
        return segments.allSatisfy { segment in
            guard let first = segment.first, first.isASCII, first.isLetter,
                  segment.count <= 64 else { return false }
            return segment.dropFirst().allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "_") }
        }
    }
}

private enum ADBReadCommand {
    case properties
    case packages
    case disabledPackages
    case home
    case homeClaimants
    case tvInputs
    case inputMethod
    case packageFlags
    case packagePermission(String)
    case setting(TVSetting)
    case launchableApps
    case launcherConfig

    var shellService: String {
        switch self {
        case .properties: "getprop"
        case .packages: "pm list packages -f"
        case .disabledPackages: "pm list packages -d"
        case .home: "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        case .homeClaimants: "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        case .tvInputs: "dumpsys tv_input"
        case .inputMethod: "settings get secure default_input_method"
        case .packageFlags: "dumpsys package packages | grep -E '^(Packages:|Hidden system packages:)|^ +(Package \\[|codePath=|versionCode=|pkgFlags=|User 0: )'"
        case .packagePermission(let package): "dumpsys package \(package)"
        case .setting(let setting): "settings get \(setting.namespace) \(setting.key)"
        case .launchableApps: "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER"
        // `content read` prints a FileNotFoundException instead of failing when there is no config.json yet.
        case .launcherConfig: "content read --uri \(TVHomeInterceptor.themeURI)/config.json 2>&1; true"
        }
    }

    var outputLimit: Int {
        switch self {
        case .properties: 256 * 1024
        case .packages, .disabledPackages: 1024 * 1024
        case .home: 64 * 1024
        case .homeClaimants: 64 * 1024
        case .tvInputs: 2 * 1024 * 1024
        case .inputMethod: 32 * 1024
        case .packageFlags: 4 * 1024 * 1024
        case .packagePermission: 256 * 1024
        case .setting: 32 * 1024
        case .launchableApps: 1024 * 1024
        case .launcherConfig: 128 * 1024
        }
    }

    var description: String {
        switch self {
        case .properties: "identity"
        case .packages: "installed packages"
        case .disabledPackages: "disabled packages"
        case .home: "current HOME app"
        case .homeClaimants: "HOME app list"
        case .tvInputs: "TV input services"
        case .inputMethod: "keyboard service"
        case .packageFlags: "package safety flags"
        case .packagePermission: "package permission safety check"
        case .setting(let setting): "\(setting.key) setting"
        case .launchableApps: "launchable apps"
        case .launcherConfig: "launcher layout"
        }
    }
}

private enum ADBPackageMutation {
    case disableUser(String)
    case enableUser(String)

    var package: String {
        switch self {
        case .disableUser(let package), .enableUser(let package): package
        }
    }

    var shellService: String {
        switch self {
        case .disableUser(let package): "pm disable-user --user 0 \(package)"
        case .enableUser(let package): "pm enable --user 0 \(package)"
        }
    }

    var description: String {
        switch self {
        case .disableUser: "disable package"
        case .enableUser: "enable package"
        }
    }
}

enum ADBSessionError: LocalizedError {
    case keychain(OSStatus)
    case keyGeneration(String)
    case network(String)
    case timeout
    case protocolViolation(String)
    case authenticationDenied
    case tlsPairingRequired
    case shellV2Unavailable
    case invalidIdentity
    case shellFailed(Int)

    var errorDescription: String? {
        switch self {
        case .keychain(let status): "Could not read the ADB key from iOS Keychain (status \(status))."
        case .keyGeneration(let reason): "Could not create an ADB key: \(reason)"
        case .network(let reason): "ADB connection failed: \(reason)"
        case .timeout: "The TV did not respond in time."
        case .protocolViolation(let reason): "The TV sent an invalid ADB response: \(reason)"
        case .authenticationDenied: "The TV rejected this ADB key. Check the TV's debugging authorization."
        case .tlsPairingRequired: "This TV requires Android Wireless Debugging pairing, which is not supported yet."
        case .shellV2Unavailable: "This TV does not support the shell protocol required by Clean Your TV."
        case .invalidIdentity: "The TV did not provide the serial number, device name and Android API needed to identify it."
        case .shellFailed(let code): "A read-only command failed on the TV (exit \(code))."
        }
    }
}

enum ADBProtocol {
    static let minimumVersion: UInt32 = 0x0100_0000
    static let maximumVersion: UInt32 = 0x0100_0001
    static let version: UInt32 = 0x0100_0000

    static func supports(version: UInt32) -> Bool {
        (minimumVersion...maximumVersion).contains(version)
    }

    static func checksum(for payload: Data) -> UInt32 {
        return payload.reduce(UInt32(0)) { $0 &+ UInt32($1) }
    }
}

private final class OneShotGate: @unchecked Sendable {
    private let lock = NSLock()
    private var hasResumed = false

    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !hasResumed else { return false }
        hasResumed = true
        return true
    }
}

private struct ADBKeyPair {
    let privateKey: SecKey
    let publicKeyLine: String
    let fingerprint: String

    static func loadOrCreate() throws -> ADBKeyPair {
        let tag = Data("tv.cleanyour.app.ios.adb-key.v1".utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag,
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecReturnRef as String: true
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        let key: SecKey
        if status == errSecSuccess, let item {
            guard CFGetTypeID(item) == SecKeyGetTypeID() else {
                throw ADBSessionError.keychain(errSecDecode)
            }
            key = item as! SecKey
        } else if status == errSecItemNotFound {
            let attributes: [String: Any] = [
                kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
                kSecAttrKeySizeInBits as String: 2048,
                kSecPrivateKeyAttrs as String: [
                    kSecAttrIsPermanent as String: true,
                    kSecAttrApplicationTag as String: tag,
                    kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                ]
            ]
            var error: Unmanaged<CFError>?
            guard let generated = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
                throw ADBSessionError.keyGeneration(error?.takeRetainedValue().localizedDescription ?? "unknown error")
            }
            key = generated
        } else {
            throw ADBSessionError.keychain(status == errSecSuccess ? errSecDecode : status)
        }
        guard let publicKey = SecKeyCopyPublicKey(key) else {
            throw ADBSessionError.keyGeneration("the public key could not be derived")
        }
        var error: Unmanaged<CFError>?
        guard let representation = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else {
            throw ADBSessionError.keyGeneration(error?.takeRetainedValue().localizedDescription ?? "public-key export failed")
        }
        let parts = try ADBPublicKeyCodec.rsaParts(fromDER: representation)
        let structure = try ADBPublicKeyCodec.encode(modulus: parts.modulus, exponent: parts.exponent)
        let line = structure.base64EncodedString() + " cleanyourtv"
        let fingerprint = Insecure.MD5.hash(data: structure)
            .map { String(format: "%02X", $0) }
            .joined(separator: ":")
        return ADBKeyPair(privateKey: key, publicKeyLine: line, fingerprint: fingerprint)
    }

    func sign(_ token: Data) throws -> Data {
        guard token.count == 20 else {
            throw ADBSessionError.protocolViolation("ADB auth token must be 20 bytes")
        }
        let digestInfo: [UInt8] = [
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        ]
        let input = Data(digestInfo) + token
        guard SecKeyIsAlgorithmSupported(privateKey, .sign, .rsaSignatureRaw) else {
            throw ADBSessionError.keyGeneration("RSA raw signing is unavailable")
        }
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(privateKey, .rsaSignatureRaw, input as CFData, &error) as Data? else {
            throw ADBSessionError.keyGeneration(error?.takeRetainedValue().localizedDescription ?? "RSA signing failed")
        }
        guard signature.count == 256 else {
            throw ADBSessionError.keyGeneration("the ADB signature had an unexpected size")
        }
        return signature
    }
}

enum ADBPublicKeyCodec {
    struct RSAParts {
        let modulus: Data
        let exponent: UInt32
    }

    static func rsaParts(fromDER data: Data) throws -> RSAParts {
        let bytes = [UInt8](data)
        var cursor = 0
        guard readByte(bytes, &cursor) == 0x30 else { throw malformedKey() }
        _ = try readLength(bytes, &cursor)
        guard readByte(bytes, &cursor) == 0x02 else { throw malformedKey() }
        let modulusLength = try readLength(bytes, &cursor)
        guard modulusLength <= bytes.count - cursor else { throw malformedKey() }
        var modulus = Array(bytes[cursor..<(cursor + modulusLength)])
        cursor += modulusLength
        while modulus.first == 0 { modulus.removeFirst() }
        guard modulus.count == 256, modulus[0] & 0x80 != 0,
              readByte(bytes, &cursor) == 0x02 else { throw malformedKey() }
        let exponentLength = try readLength(bytes, &cursor)
        guard exponentLength > 0, exponentLength <= 4, exponentLength <= bytes.count - cursor else {
            throw malformedKey()
        }
        let exponent = bytes[cursor..<(cursor + exponentLength)].reduce(UInt32(0)) {
            ($0 << 8) | UInt32($1)
        }
        guard exponent == 3 || exponent == 65_537 else { throw malformedKey() }
        return RSAParts(modulus: Data(modulus), exponent: exponent)
    }

    static func encode(modulus: Data, exponent: UInt32) throws -> Data {
        let n = [UInt8](modulus)
        guard n.count == 256, n[0] & 0x80 != 0, n[255] & 1 == 1,
              exponent == 3 || exponent == 65_537 else { throw malformedKey() }

        let n0 = UInt32(n[255])
            | (UInt32(n[254]) << 8)
            | (UInt32(n[253]) << 16)
            | (UInt32(n[252]) << 24)
        var inverse: UInt32 = 1
        for _ in 0..<5 { inverse = inverse &* (2 &- n0 &* inverse) }
        let n0inv = 0 &- inverse

        var rr = [UInt8](repeating: 0, count: 256)
        rr[255] = 1
        for _ in 0..<4096 {
            var carry: UInt16 = 0
            for i in stride(from: 255, through: 0, by: -1) {
                let value = UInt16(rr[i]) * 2 + carry
                rr[i] = UInt8(truncatingIfNeeded: value)
                carry = value >> 8
            }
            if carry != 0 || rr.lexicographicallyPrecedes(n) == false {
                var borrow: Int16 = 0
                for i in stride(from: 255, through: 0, by: -1) {
                    let value = Int16(rr[i]) - Int16(n[i]) - borrow
                    rr[i] = UInt8(truncatingIfNeeded: value)
                    borrow = value < 0 ? 1 : 0
                }
                if carry != 0, borrow == 0 {
                    throw ADBSessionError.keyGeneration("RSA Montgomery reduction overflow")
                }
            }
        }

        var result = Data()
        appendLE32(64, to: &result)
        appendLE32(n0inv, to: &result)
        for word in wordsLE(n) { appendLE32(word, to: &result) }
        for word in wordsLE(rr) { appendLE32(word, to: &result) }
        appendLE32(exponent, to: &result)
        guard result.count == 524 else { throw malformedKey() }
        return result
    }

    private static func wordsLE(_ bytes: [UInt8]) -> [UInt32] {
        (0..<64).map { word in
            let offset = 256 - (word + 1) * 4
            return UInt32(bytes[offset + 3])
                | (UInt32(bytes[offset + 2]) << 8)
                | (UInt32(bytes[offset + 1]) << 16)
                | (UInt32(bytes[offset]) << 24)
        }
    }

    private static func appendLE32(_ value: UInt32, to data: inout Data) {
        data.append(UInt8(truncatingIfNeeded: value))
        data.append(UInt8(truncatingIfNeeded: value >> 8))
        data.append(UInt8(truncatingIfNeeded: value >> 16))
        data.append(UInt8(truncatingIfNeeded: value >> 24))
    }

    private static func readByte(_ bytes: [UInt8], _ cursor: inout Int) -> UInt8? {
        guard cursor < bytes.count else { return nil }
        defer { cursor += 1 }
        return bytes[cursor]
    }

    private static func readLength(_ bytes: [UInt8], _ cursor: inout Int) throws -> Int {
        guard let first = readByte(bytes, &cursor) else { throw malformedKey() }
        if first & 0x80 == 0 { return Int(first) }
        let count = Int(first & 0x7f)
        guard count > 0, count <= 2, count <= bytes.count - cursor else { throw malformedKey() }
        var length = 0
        for _ in 0..<count {
            guard let next = readByte(bytes, &cursor) else { throw malformedKey() }
            length = (length << 8) | Int(next)
        }
        return length
    }

    private static func malformedKey() -> ADBSessionError {
        .keyGeneration("the RSA public key is malformed")
    }
}

final class ADBTVConnection {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "tv.cleanyour.app.ios.adb")
    private var nextStreamID: UInt32 = 1
    private var closedStreamIDs = Set<UInt32>()
    private var features = Set<String>()
    private var maxPayload = 0

    private init(connection: NWConnection) {
        self.connection = connection
    }

    static func open(
        endpoint: NWEndpoint,
        onAwaitingApproval: @escaping @MainActor @Sendable (String) -> Void
    ) async throws -> (ADBTVConnection, TVIdentity) {
        let key = try ADBKeyPair.loadOrCreate()
        let socket = NWConnection(to: endpoint, using: .tcp)
        let client = ADBTVConnection(connection: socket)
        return try await withTaskCancellationHandler {
            do {
                try await client.start()
                try await client.handshake(key: key, onAwaitingApproval: onAwaitingApproval)
                let identity = try await client.readIdentity()
                return (client, identity)
            } catch {
                socket.cancel()
                throw error
            }
        } onCancel: {
            socket.cancel()
        }
    }

    func close() {
        connection.cancel()
    }

    private func start() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let gate = OneShotGate()
            let timeout = DispatchWorkItem { [connection] in
                guard gate.claim() else { return }
                connection.cancel()
                continuation.resume(throwing: ADBSessionError.timeout)
            }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    guard gate.claim() else { return }
                    timeout.cancel()
                    continuation.resume()
                case .failed(let error):
                    guard gate.claim() else { return }
                    timeout.cancel()
                    continuation.resume(throwing: ADBSessionError.network(error.localizedDescription))
                case .cancelled:
                    guard gate.claim() else { return }
                    timeout.cancel()
                    continuation.resume(throwing: ADBSessionError.network("connection cancelled"))
                default:
                    break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + 8, execute: timeout)
        }
    }

    private func handshake(
        key: ADBKeyPair,
        onAwaitingApproval: @escaping @MainActor @Sendable (String) -> Void
    ) async throws {
        try await sendFrame(command: "CNXN", arg0: ADBProtocol.version, arg1: 1024 * 1024, payload: Data("host::\0".utf8))
        var signatureSent = false
        var keySent = false
        while true {
            let frame = try await readFrame(timeout: keySent ? 120 : 5)
            switch frame.command {
            case "STLS":
                throw ADBSessionError.tlsPairingRequired
            case "AUTH":
                guard frame.arg0 == 1, frame.payload.count == 20 else {
                    throw ADBSessionError.protocolViolation("invalid AUTH token")
                }
                if !signatureSent {
                    try await sendFrame(command: "AUTH", arg0: 2, arg1: 0, payload: try key.sign(frame.payload))
                    signatureSent = true
                } else if !keySent {
                    try await sendFrame(command: "AUTH", arg0: 3, arg1: 0, payload: Data((key.publicKeyLine + "\0").utf8))
                    keySent = true
                    await onAwaitingApproval(key.fingerprint)
                } else {
                    throw ADBSessionError.authenticationDenied
                }
            case "CNXN":
                guard ADBProtocol.supports(version: frame.arg0), frame.arg1 >= 4096 else {
                    throw ADBSessionError.protocolViolation(
                        "unsupported connection banner (version \(String(frame.arg0, radix: 16)), max payload \(frame.arg1))"
                    )
                }
                let banner = String(decoding: frame.payload.prefix(4096), as: UTF8.self)
                    .trimmingCharacters(in: CharacterSet(charactersIn: "\0"))
                guard banner.hasPrefix("device::") else {
                    throw ADBSessionError.protocolViolation("the peer is not an Android device")
                }
                let fields = banner.dropFirst("device::".count)
                    .split(separator: ";")
                    .compactMap { field -> (String, String)? in
                        let pair = field.split(separator: "=", maxSplits: 1)
                        guard pair.count == 2 else { return nil }
                        return (String(pair[0]), String(pair[1]))
                    }
                features = Set(fields.first(where: { $0.0 == "features" })?.1.split(separator: ",").map(String.init) ?? [])
                maxPayload = Int(min(frame.arg1, 1024 * 1024))
                guard features.contains("shell_v2") else { throw ADBSessionError.shellV2Unavailable }
                return
            default:
                throw ADBSessionError.protocolViolation("unexpected \(frame.command) during authentication")
            }
        }
    }

    private func readIdentity() async throws -> TVIdentity {
        let output = try await runReadOnly(.properties)
        return try TVIdentity.parse(properties: String(decoding: output, as: UTF8.self))
    }

    func readPackageInventory() async throws -> [TVPackage] {
        let installed = try await runReadOnly(.packages)
        let disabled = try await runReadOnly(.disabledPackages)
        return try TVPackageListParser.parse(
            installed: String(decoding: installed, as: UTF8.self),
            disabled: String(decoding: disabled, as: UTF8.self)
        )
    }

    func setPackageDisabled(_ package: String, disabled: Bool) async throws {
        guard TVPackageListParser.isValidPackageName(package) else {
            throw ADBSessionError.protocolViolation("invalid package name for a reversible package change")
        }
        let mutation: ADBPackageMutation = disabled ? .disableUser(package) : .enableUser(package)
        _ = try await runShell(
            service: mutation.shellService,
            description: mutation.description,
            outputLimit: 32 * 1024
        )
    }

    func readConfigurationSnapshot() async throws -> TVConfigurationSnapshot {
        let homeOutput = try await runReadOnly(.home)
        let home = try TVConfigurationParser.homeComponent(from: String(decoding: homeOutput, as: UTF8.self))
        let claimantOutput = try await runReadOnly(.homeClaimants)
        let claimants = try TVConfigurationParser.homeClaimants(from: String(decoding: claimantOutput, as: UTF8.self))
        var settings: [TVSettingValue] = []
        for setting in TVSetting.allCases {
            let output = try await runReadOnly(.setting(setting))
            let value = try TVConfigurationParser.settingValue(
                from: String(decoding: output, as: UTF8.self),
                setting: setting
            )
            settings.append(TVSettingValue(setting: setting, value: value))
        }
        let inputOutput = try await runReadOnly(.tvInputs)
        let inputs = try TVConfigurationParser.tvInputPackages(from: String(decoding: inputOutput, as: UTF8.self))
        let inputMethodOutput = try await runReadOnly(.inputMethod)
        let inputMethodValue = try TVConfigurationParser.settingValue(
            from: String(decoding: inputMethodOutput, as: UTF8.self),
            setting: .screensaverComponents
        )
        let inputMethod = try inputMethodValue.map { value -> String in
            guard let components = TVSetting.componentList(value), components.count == 1 else {
                throw ADBSessionError.protocolViolation("default_input_method returned an invalid component")
            }
            return components[0]
        }
        let packageFlagsOutput = try await runReadOnly(.packageFlags)
        let packageMetadata = try TVConfigurationParser.packageMetadata(
            from: String(decoding: packageFlagsOutput, as: UTF8.self)
        )
        return TVConfigurationSnapshot(
            homeComponent: home,
            homeClaimants: claimants,
            settings: settings,
            inputPackages: inputs.packages,
            inputServiceAvailable: inputs.available,
            inputMethod: inputMethod,
            packageMetadata: packageMetadata
        )
    }

    func readSafetySnapshot(
        packages: [TVPackage],
        candidatePackageNames: Set<String>
    ) async throws -> TVSafetySnapshot {
        let configuration = try await readConfigurationSnapshot()
        let enabledCandidates = packages.filter {
            candidatePackageNames.contains($0.name) && !$0.isDisabled
        }.map(\.name).sorted()
        let namesToInspect = enabledCandidates.prefix(64)
        var tvInputPermissions: [String: Bool?] = [:]
        for package in namesToInspect {
            guard TVPackageListParser.isValidPackageName(package) else {
                throw ADBSessionError.protocolViolation("profile catalog contains an invalid package name")
            }
            let output = try await runReadOnly(.packagePermission(package))
            tvInputPermissions[package] = try TVConfigurationParser.requestsTVInputHardware(
                from: String(decoding: output, as: UTF8.self)
            )
        }
        let enrichedPackages = packages.map { package in
            let metadata = configuration.packageMetadata[package.name]
            return TVPackage(
                name: package.name,
                isDisabled: package.isDisabled,
                isSystem: metadata?.isSystem,
                isPersistent: metadata?.isPersistent,
                isTVInput: configuration.inputPackages.contains(package.name),
                requestsTVInputHardware: tvInputPermissions[package.name] ?? nil
            )
        }
        return TVSafetySnapshot(packages: enrichedPackages, configuration: configuration)
    }

    // Installs one of the two bundled sideloaded APKs, streamed on stdin as `adb install` does.
    func installBundledAPK(_ apk: Data) async throws {
        guard !apk.isEmpty, apk.count <= 16 * 1024 * 1024 else {
            throw ADBSessionError.protocolViolation("the bundled APK has an invalid size")
        }
        let output = try await runShell(
            service: "cmd package install -r -S \(apk.count)",
            description: "app install",
            outputLimit: 32 * 1024,
            input: apk
        )
        guard String(decoding: output, as: UTF8.self).contains("Success") else {
            throw ADBSessionError.protocolViolation("the TV did not report a successful install")
        }
    }

    func uninstallBundledPackage(_ package: String) async throws {
        guard TVHomeInterceptor.packages.contains(package) else {
            throw ADBSessionError.protocolViolation("only the bundled home screen packages can be removed")
        }
        _ = try await runShell(service: "pm uninstall --user 0 \(package)", description: "app removal", outputLimit: 32 * 1024)
    }

    func writeAccessibilityServices(_ value: String) async throws {
        guard TVSetting.enabledAccessibilityServices.accepts(value) else {
            throw ADBSessionError.protocolViolation("invalid accessibility service list")
        }
        // Single quotes: the list was validated as package/class components joined by colons.
        let quoted = value.isEmpty ? "''" : "'\(value)'"
        _ = try await runShell(service: "settings put secure enabled_accessibility_services \(quoted)", description: "accessibility services setting", outputLimit: 32 * 1024)
        if !value.isEmpty {
            _ = try await runShell(service: "settings put secure accessibility_enabled 1", description: "accessibility switch", outputLimit: 32 * 1024)
        }
    }

    func readSetting(_ setting: TVSetting) async throws -> String? {
        let data = try await runReadOnly(.setting(setting))
        return try TVConfigurationParser.settingValue(from: String(decoding: data, as: UTF8.self), setting: setting)
    }

    func readLaunchableApps() async throws -> [String] {
        try TVLaunchableApps.parse(String(decoding: try await runReadOnly(.launchableApps), as: UTF8.self))
    }

    func readLauncherConfig() async throws -> TVLauncherConfig {
        try TVLauncherConfig.fromReadOutput(String(decoding: try await runReadOnly(.launcherConfig), as: UTF8.self))
    }

    // Writes (or, for nil, removes) the launcher's config.json through its ThemeProvider, marks a new
    // theme revision so the launcher drops its on-TV choices, and restarts the launcher to read it.
    func writeLauncherConfig(_ data: Data?) async throws {
        let uri = TVHomeInterceptor.themeURI
        if let data {
            guard data.count <= TVLauncherConfig.maxBytes else {
                throw ADBSessionError.protocolViolation("the launcher config is too large")
            }
            _ = try await runShell(service: "content write --uri \(uri)/config.json", description: "launcher layout write", outputLimit: 32 * 1024, input: data)
        } else {
            _ = try await runShell(service: "content delete --uri \(uri)/config.json", description: "launcher layout removal", outputLimit: 32 * 1024)
        }
        let revision = Data(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().utf8)
        _ = try await runShell(service: "content write --uri \(uri)/theme_revision", description: "launcher revision write", outputLimit: 32 * 1024, input: revision)
        _ = try await runShell(
            service: "am force-stop \(TVHomeInterceptor.launcherPackage); am start -n \(TVHomeInterceptor.launcherPackage)/.MainActivity >/dev/null",
            description: "launcher restart",
            outputLimit: 32 * 1024
        )
    }

    private func runReadOnly(_ command: ADBReadCommand) async throws -> Data {
        try await runShell(
            service: command.shellService,
            description: command.description,
            outputLimit: command.outputLimit
        )
    }

    private func runShell(service: String, description: String, outputLimit: Int, input: Data? = nil) async throws -> Data {
        guard nextStreamID <= UInt32.max - 2 else {
            throw ADBSessionError.protocolViolation("ADB stream identifiers are exhausted")
        }
        let streamID = nextStreamID
        nextStreamID += 2
        var remoteStreamID: UInt32 = 0
        let payload = Data(("shell,v2,raw:" + service + "\0").utf8)
        try await sendFrame(command: "OPEN", arg0: streamID, arg1: 0, payload: payload)
        while true {
            let frame = try await readFrame(timeout: 30)
            switch frame.command {
            case "OKAY" where frame.arg1 == streamID && frame.arg0 != 0:
                remoteStreamID = frame.arg0
                break
            case "CLSE" where frame.arg1 == streamID:
                throw ADBSessionError.protocolViolation("the TV refused the \(description) request")
            case "CLSE" where closedStreamIDs.contains(frame.arg1):
                continue
            default:
                throw ADBSessionError.protocolViolation(
                    "unexpected \(frame.command) while opening \(description) (arg0 \(frame.arg0), arg1 \(frame.arg1), stream \(streamID))"
                )
            }
            if remoteStreamID != 0 { break }
        }

        var output = Data()
        var packets = Data()
        var exitCode: Int?
        let frameTimeout: TimeInterval = input == nil ? 30 : 90

        // Handles one frame of this shell stream; true when it was the TV's OKAY for our stream.
        func handle(_ frame: (command: String, arg0: UInt32, arg1: UInt32, payload: Data)) async throws -> Bool {
            switch frame.command {
            case "OKAY" where frame.arg1 == streamID:
                return true
            case "WRTE" where frame.arg1 == streamID && frame.arg0 == remoteStreamID:
                guard exitCode == nil else {
                    throw ADBSessionError.protocolViolation("shell sent data after its exit packet")
                }
                guard !frame.payload.isEmpty, frame.payload.count <= maxPayload else {
                    throw ADBSessionError.protocolViolation("shell frame exceeds the negotiated size")
                }
                try await sendFrame(command: "OKAY", arg0: streamID, arg1: remoteStreamID, payload: Data())
                packets.append(frame.payload)
                try consumeShellPackets(&packets, output: &output, exitCode: &exitCode, cap: outputLimit)
                return false
            case "CLSE" where closedStreamIDs.contains(frame.arg1):
                return false
            case "CLSE" where frame.arg0 == remoteStreamID && frame.arg1 == streamID:
                throw ADBSessionError.protocolViolation("the TV closed the \(description) shell before its exit packet")
            default:
                throw ADBSessionError.protocolViolation("unexpected \(frame.command) in the \(description) shell")
            }
        }

        if let input {
            // Shell v2 stdin packets (id 0), one WRTE at a time, each waiting for the TV's OKAY; id 4 closes stdin.
            guard maxPayload > 8 else { throw ADBSessionError.protocolViolation("the TV negotiated an unusable payload size") }
            let chunk = min(maxPayload - 5, 16 * 1024)
            var packetsToSend: [Data] = []
            var offset = 0
            while offset < input.count {
                let end = min(offset + chunk, input.count)
                var packet = Data([0])
                packet.append(littleEndian32(UInt32(end - offset)))
                packet.append(input[offset..<end])
                packetsToSend.append(packet)
                offset = end
            }
            var closeStdin = Data([4])
            closeStdin.append(littleEndian32(0))
            packetsToSend.append(closeStdin)
            for packet in packetsToSend {
                try await sendFrame(command: "WRTE", arg0: streamID, arg1: remoteStreamID, payload: packet)
                var acknowledged = false
                while !acknowledged {
                    acknowledged = try await handle(try await readFrame(timeout: frameTimeout))
                }
            }
        }
        while exitCode == nil {
            _ = try await handle(try await readFrame(timeout: frameTimeout))
        }
        guard packets.isEmpty else {
            throw ADBSessionError.protocolViolation("trailing bytes after the shell exit packet")
        }
        guard let exitCode else { throw ADBSessionError.protocolViolation("the \(description) shell ended without an exit packet") }
        closedStreamIDs.insert(streamID)
        try await sendFrame(command: "CLSE", arg0: streamID, arg1: remoteStreamID, payload: Data())
        guard exitCode == 0 else { throw ADBSessionError.shellFailed(exitCode) }
        return output
    }

    private func consumeShellPackets(_ packets: inout Data, output: inout Data, exitCode: inout Int?, cap: Int) throws {
        var cursor = 0
        while packets.count - cursor >= 5 {
            guard exitCode == nil else {
                throw ADBSessionError.protocolViolation("shell sent data after its exit packet")
            }
            let id = packets[cursor]
            let length = readLE32(packets, cursor + 1)
            guard length <= 64 * 1024 else {
                throw ADBSessionError.protocolViolation("shell packet is too large")
            }
            guard packets.count - cursor - 5 >= Int(length) else { break }
            let start = cursor + 5
            let end = start + Int(length)
            switch id {
            case 1:
                guard output.count + Int(length) <= cap else {
                    throw ADBSessionError.protocolViolation("shell output exceeds the size limit")
                }
                output.append(packets[start..<end])
            case 2:
                break
            case 3:
                guard length == 1 else {
                    throw ADBSessionError.protocolViolation("invalid shell exit packet")
                }
                exitCode = Int(packets[start])
            default:
                throw ADBSessionError.protocolViolation("unsupported shell packet type")
            }
            cursor = end
        }
        if cursor > 0 { packets.removeSubrange(0..<cursor) }
    }

    private func sendFrame(command: String, arg0: UInt32, arg1: UInt32, payload: Data) async throws {
        guard command.utf8.count == 4, payload.count <= 256 * 1024 else {
            throw ADBSessionError.protocolViolation("invalid outgoing frame")
        }
        let commandValue = command.utf8.enumerated().reduce(UInt32(0)) {
            $0 | (UInt32($1.element) << UInt32($1.offset * 8))
        }
        var frame = Data()
        frame.append(littleEndian32(commandValue))
        frame.append(littleEndian32(arg0))
        frame.append(littleEndian32(arg1))
        frame.append(littleEndian32(UInt32(payload.count)))
        let checksum = ADBProtocol.checksum(for: payload)
        frame.append(littleEndian32(checksum))
        frame.append(littleEndian32(~commandValue))
        frame.append(payload)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: frame, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: ADBSessionError.network(error.localizedDescription)) }
                else { continuation.resume() }
            })
        }
    }

    private func readFrame(timeout: TimeInterval = 30) async throws -> (command: String, arg0: UInt32, arg1: UInt32, payload: Data) {
        let header = try await receiveExactly(24, timeout: timeout)
        let commandValue = readLE32(header, 0)
        let arg0 = readLE32(header, 4)
        let arg1 = readLE32(header, 8)
        let length = readLE32(header, 12)
        let checksum = readLE32(header, 16)
        guard readLE32(header, 20) == ~commandValue else {
            throw ADBSessionError.protocolViolation("bad frame magic")
        }
        let commands: [UInt32: String] = [
            0x434E5953: "SYNC", 0x4E584E43: "CNXN", 0x48545541: "AUTH",
            0x4E45504F: "OPEN", 0x59414B4F: "OKAY", 0x45534C43: "CLSE",
            0x45545257: "WRTE", 0x534C5453: "STLS"
        ]
        guard let command = commands[commandValue] else {
            throw ADBSessionError.protocolViolation("unknown command")
        }
        let cap: UInt32 = command == "WRTE" ? 1024 * 1024 : (command == "OKAY" || command == "CLSE" || command == "STLS" ? 0 : 256 * 1024)
        guard length <= cap else { throw ADBSessionError.protocolViolation("\(command) payload exceeds its limit") }
        let payload = length == 0 ? Data() : try await receiveExactly(Int(length), timeout: timeout)
        let expectedChecksum = ADBProtocol.checksum(for: payload)
        guard checksum == expectedChecksum else {
            throw ADBSessionError.protocolViolation("bad \(command) checksum")
        }
        return (command, arg0, arg1, payload)
    }

    private func receiveExactly(_ count: Int, timeout: TimeInterval) async throws -> Data {
        var result = Data()
        while result.count < count {
            let remaining = count - result.count
            let chunk = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
                let gate = OneShotGate()
                let timeoutWork = DispatchWorkItem { [connection] in
                    guard gate.claim() else { return }
                    connection.cancel()
                    continuation.resume(throwing: ADBSessionError.timeout)
                }
                connection.receive(minimumIncompleteLength: 1, maximumLength: remaining) { data, _, complete, error in
                    guard gate.claim() else { return }
                    timeoutWork.cancel()
                    if let error {
                        continuation.resume(throwing: ADBSessionError.network(error.localizedDescription))
                    } else if let data, !data.isEmpty {
                        continuation.resume(returning: data)
                    } else if complete {
                        continuation.resume(throwing: ADBSessionError.network("the TV closed the connection"))
                    } else {
                        continuation.resume(returning: Data())
                    }
                }
                queue.asyncAfter(deadline: .now() + timeout, execute: timeoutWork)
            }
            guard !chunk.isEmpty else {
                throw ADBSessionError.network("the TV sent an empty ADB frame")
            }
            result.append(chunk)
        }
        return result
    }
}

private func littleEndian32(_ value: UInt32) -> Data {
    Data([
        UInt8(truncatingIfNeeded: value),
        UInt8(truncatingIfNeeded: value >> 8),
        UInt8(truncatingIfNeeded: value >> 16),
        UInt8(truncatingIfNeeded: value >> 24)
    ])
}

private func readLE32(_ data: Data, _ offset: Int) -> UInt32 {
    UInt32(data[offset])
        | (UInt32(data[offset + 1]) << 8)
        | (UInt32(data[offset + 2]) << 16)
        | (UInt32(data[offset + 3]) << 24)
}
