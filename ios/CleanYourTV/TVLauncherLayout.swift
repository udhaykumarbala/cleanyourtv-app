import Foundation

// Which apps the Clean Your TV launcher shows first. The launcher reads `config.json` from its own
// files directory; the shell cannot reach Android/data on Android 11, so the file goes through the
// launcher's ThemeProvider (`content write`), as tools/theme.sh does. Only `dock_apps`,
// `rows_below_dock` and, on a full three-row layout, `dock_title` are ever changed: every other key
// of the existing file (theme, wallpaper, columns) is kept.

// Port of core/spec AppNames.fromPackage: the read-only probe has no app labels.
enum TVAppNames {
    private static let known: [String: String] = [
        "com.netflix.ninja": "Netflix",
        "com.amazon.firetv.youtube": "YouTube",
        "com.amazon.firebat": "Prime Video",
        "com.disney.disneyplus": "Disney+",
        "com.apple.atve.amazon.appletv": "Apple TV",
        "com.hbo.hbonow": "HBO Max",
        "com.spotify.tv.android": "Spotify",
        "com.crunchyroll.crunchyroid": "Crunchyroll",
        "com.amazon.whasettings": "Amazon Settings",
        "tv.twitch.android.viewer": "Twitch",
        "tv.pluto.android": "Pluto TV",
    ]
    private static let generic: Set<String> = [
        "com", "org", "net", "io", "co", "android", "androidtv", "google", "gms", "tv", "atv", "gtv", "leanback",
        "app", "apps", "application", "mobile", "phone", "client", "main", "release", "prod", "free", "ui", "launcher",
        "smarttv", "tvapp", "ott", "x86", "arm", "example",
    ]
    private static let affixes = ["android", "leanback", "atv", "tv"]

    static func name(for package: String) -> String { known[package] ?? fromPackage(package) }

    static func fromPackage(_ package: String) -> String {
        let all = package.split(separator: ".").map(String.init)
        guard let lastSegment = all.last else { return package }
        let segments = all.count > 1 ? Array(all.dropFirst()) : all
        let meaningful = segments.map(trimAffixes).filter { $0.count >= 2 && !generic.contains($0.lowercased()) }
        guard let first = meaningful.first else { return words(lastSegment) }
        let last = meaningful.count > 1 && meaningful.last!.count >= 3 ? meaningful.last : nil
        return [first, last].compactMap { $0 }.map(words).joined(separator: " ")
    }

    private static func trimAffixes(_ segment: String) -> String {
        var value = segment
        var changed = true
        while changed {
            changed = false
            let low = value.lowercased()
            if generic.contains(low) { return "" }
            for affix in affixes where low.count >= affix.count + 4 {
                if low.hasPrefix(affix) { value = String(value.dropFirst(affix.count)); changed = true; break }
                if low.hasSuffix(affix) { value = String(value.dropLast(affix.count)); changed = true; break }
            }
        }
        return value.trimmingCharacters(in: CharacterSet(charactersIn: "_-"))
    }

    private static func words(_ value: String) -> String {
        let spaced = value.replacingOccurrences(of: "([a-z0-9])([A-Z])", with: "$1 $2", options: .regularExpression)
        return spaced.split(whereSeparator: { "_- ".contains($0) })
            .map { $0.prefix(1).uppercased() + $0.dropFirst() }
            .joined(separator: " ")
    }
}

enum TVLaunchableApps {
    // `cmd package query-activities --brief` mixes component lines with "priority=…" and "Activity #n:" lines.
    static func parse(_ output: String) throws -> [String] {
        guard output.utf8.count <= 1024 * 1024 else {
            throw ADBSessionError.protocolViolation("launchable app list exceeds the size limit")
        }
        var packages = Set<String>()
        for line in output.split(separator: "\n") {
            let text = line.trimmingCharacters(in: .whitespaces)
            guard text.utf8.count <= 512, TVSetting.isComponent(text),
                  let package = text.split(separator: "/", maxSplits: 1).first.map(String.init),
                  !TVHomeInterceptor.packages.contains(package) else { continue }
            packages.insert(package)
        }
        return packages.sorted()
    }
}

// The dock and the rows under it as the launcher builds them (core/spec DockLayers): the first row is
// the dock, rows_below_dock more rows stay in view, three rows at most.
enum TVLauncherRows {
    static let maxRows = 3
    static let defaultColumns = 4

    static func capacity(columns: Int) -> Int { max(columns, 1) * maxRows }

    static func trim(_ apps: [String], columns: Int) -> [String] {
        var seen = Set<String>()
        return Array(apps.filter { seen.insert($0).inserted }.prefix(capacity(columns: columns)))
    }

    static func rows(_ apps: [String], columns: Int) -> [[String]] {
        let size = max(columns, 1)
        let kept = Array(apps.prefix(capacity(columns: size)))
        return stride(from: 0, to: kept.count, by: size).map { Array(kept[$0..<min($0 + size, kept.count)]) }
    }

    static func rowsBelowDock(count: Int, columns: Int) -> Int {
        let size = max(columns, 1)
        let n = min(max(count, 0), capacity(columns: size))
        return n == 0 ? 0 : (n + size - 1) / size - 1
    }
}

struct TVLauncherConfig {
    static let maxBytes = 64 * 1024
    private(set) var object: [String: Any]
    // The bytes as read, so a backup restores the file exactly (nil: there was none).
    let original: Data?

    // nil means the TV has no config.json yet. Anything that is not a JSON object is refused rather than
    // overwritten, so a file the launcher can read is never replaced with a guess.
    init(data: Data?) throws {
        original = data
        guard let data else { object = [:]; return }
        guard data.count <= Self.maxBytes,
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ADBSessionError.protocolViolation("the launcher's config.json is not a JSON object")
        }
        object = parsed
    }

    // `content read` prints its error instead of failing when the file does not exist.
    static func fromReadOutput(_ output: String) throws -> TVLauncherConfig {
        let text = output.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty || text.contains("FileNotFoundException") { return try TVLauncherConfig(data: nil) }
        guard text.hasPrefix("{") else {
            throw ADBSessionError.protocolViolation("the launcher's config.json could not be read")
        }
        return try TVLauncherConfig(data: Data(text.utf8))
    }

    var dockApps: [String] {
        (object["dock_apps"] as? [Any])?.compactMap { $0 as? String }.filter(TVPackageListParser.isValidPackageName) ?? []
    }

    var columns: Int {
        (object["columns"] as? Int).flatMap { (1...8).contains($0) ? $0 : nil } ?? TVLauncherRows.defaultColumns
    }

    // Same rule as DockLayers.applyTo; an empty list leaves dock_apps out so the launcher keeps its own dock.
    func applying(order: [String]) throws -> Data {
        guard order.allSatisfy(TVPackageListParser.isValidPackageName) else {
            throw ADBSessionError.protocolViolation("invalid package name in the app order")
        }
        var next = object
        let dock = TVLauncherRows.trim(order, columns: columns)
        if dock.isEmpty {
            next["dock_apps"] = nil
        } else {
            let below = TVLauncherRows.rowsBelowDock(count: dock.count, columns: columns)
            next["dock_apps"] = dock
            next["rows_below_dock"] = below
            // Three rows would push the focused app's name over the third row on a 1080p screen.
            if below == TVLauncherRows.maxRows - 1 { next["dock_title"] = false }
        }
        let data = try JSONSerialization.data(withJSONObject: next, options: [.sortedKeys, .withoutEscapingSlashes])
        guard data.count <= Self.maxBytes else {
            throw ADBSessionError.protocolViolation("the launcher config would be too large")
        }
        return data
    }
}

// The config.json the TV had before the last change from this iPhone (nil: it had none), so the order can be put back.
struct TVLauncherBackup: Codable {
    let config: Data?
    let savedAt: Date
}

struct TVLauncherBackupStore {
    private let url: URL

    init(directoryURL: URL? = nil) {
        let base = directoryURL ?? FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("CleanYourTV", isDirectory: true)
        url = base.appendingPathComponent("launcher-layout-backups.json")
    }

    private func all() throws -> [String: TVLauncherBackup] {
        guard FileManager.default.fileExists(atPath: url.path) else { return [:] }
        let data = try Data(contentsOf: url)
        guard data.count <= 2 * 1024 * 1024 else { throw ADBSessionError.protocolViolation("launcher backup file is too large") }
        return try JSONDecoder().decode([String: TVLauncherBackup].self, from: data)
    }

    func load(tvID: String) throws -> TVLauncherBackup? { try all()[tvID] }

    func save(_ backup: TVLauncherBackup, tvID: String) throws {
        var backups = try all()
        backups[tvID] = backup
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(backups).write(to: url, options: .atomic)
    }

    func remove(tvID: String) throws {
        var backups = try all()
        backups[tvID] = nil
        try JSONEncoder().encode(backups).write(to: url, options: .atomic)
    }
}
