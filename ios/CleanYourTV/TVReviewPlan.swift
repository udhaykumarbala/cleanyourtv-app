import CryptoKit
import Foundation

struct TVReviewPlan: Codable, Identifiable {
    struct Item: Codable, Identifiable {
        let package: String
        let groupID: String
        let groupTitle: String
        let note: String?
        let status: TVAnalysisStatus
        var selected: Bool

        var id: String { package }
    }

    let id: UUID
    let tvID: String
    let profileID: String
    let profileTitle: String
    let createdAt: Date
    let snapshotDigest: String
    var items: [Item]
    private(set) var planDigest: String

    init(
        tvID: String,
        profileID: String,
        profileTitle: String,
        snapshotDigest: String,
        report: TVSafetyReport
    ) {
        id = UUID()
        self.tvID = tvID
        self.profileID = profileID
        self.profileTitle = profileTitle
        createdAt = Date()
        self.snapshotDigest = snapshotDigest
        items = report.groups.flatMap { group in
            group.rows.compactMap { row in
                guard row.status == .reviewCandidate || row.status == .optional else { return nil }
                return Item(
                    package: row.package,
                    groupID: group.id,
                    groupTitle: group.title,
                    note: row.note,
                    status: row.status,
                    selected: row.status == .reviewCandidate
                )
            }
        }
        planDigest = ""
        planDigest = Self.digest(
            id: id,
            tvID: tvID,
            profileID: profileID,
            profileTitle: profileTitle,
            createdAt: createdAt,
            snapshotDigest: snapshotDigest,
            items: items
        )
    }

    var selectedItems: [Item] { items.filter(\.selected) }
    var selectedCount: Int { selectedItems.count }

    var isDigestValid: Bool {
        Set(items.map(\.package)).count == items.count
            && items.allSatisfy {
                TVPackageListParser.isValidPackageName($0.package)
                    && ($0.status == .reviewCandidate || $0.status == .optional)
            }
            && planDigest == Self.digest(
            id: id,
            tvID: tvID,
            profileID: profileID,
            profileTitle: profileTitle,
            createdAt: createdAt,
            snapshotDigest: snapshotDigest,
            items: items
        )
    }

    func isStale(against currentSnapshotDigest: String?) -> Bool {
        guard let currentSnapshotDigest else { return true }
        return snapshotDigest != currentSnapshotDigest || !isDigestValid
    }

    mutating func setSelected(_ selected: Bool, for package: String) throws {
        guard let index = items.firstIndex(where: { $0.package == package }) else {
            throw TVReviewPlanError.unknownPackage
        }
        items[index].selected = selected
        planDigest = Self.digest(
            id: id,
            tvID: tvID,
            profileID: profileID,
            profileTitle: profileTitle,
            createdAt: createdAt,
            snapshotDigest: snapshotDigest,
            items: items
        )
    }

    var exportText: String {
        var lines = [
            "Clean Your TV — review-only plan",
            "TV ID: \(tvID)",
            "Profile: \(profileTitle) (\(profileID))",
            "Review basis SHA-256: \(snapshotDigest)",
            "Plan SHA-256: \(planDigest)",
            "Created: \(ISO8601DateFormatter().string(from: createdAt))",
            "",
            "Selected packages (\(selectedCount)); this file contains no executable commands."
        ]
        lines += selectedItems.sorted { $0.package < $1.package }.map { "- \($0.package) — \($0.groupTitle)" }
        lines.append("")
        lines.append("This draft has not changed the TV.")
        return lines.joined(separator: "\n")
    }

    private static func digest(
        id: UUID,
        tvID: String,
        profileID: String,
        profileTitle: String,
        createdAt: Date,
        snapshotDigest: String,
        items: [Item]
    ) -> String {
        var fields = [
            id.uuidString,
            tvID,
            profileID,
            profileTitle,
            String(Int64(createdAt.timeIntervalSince1970 * 1000)),
            snapshotDigest
        ]
        for item in items.sorted(by: { $0.package < $1.package }) {
            fields += [
                item.package,
                item.groupID,
                item.groupTitle,
                item.note ?? "",
                item.status.rawValue,
                item.selected ? "1" : "0"
            ]
        }
        let bytes = Data(fields.map { "\($0.utf8.count):\($0)" }.joined(separator: "|").utf8)
        return SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }
}

struct TVRestorePoint: Codable, Identifiable {
    struct Item: Codable, Identifiable {
        let package: String
        let wasDisabled: Bool

        var id: String { package }
    }

    let id: UUID
    let tvID: String
    let profileID: String
    let createdAt: Date
    let sourcePlanDigest: String
    let items: [Item]
    var isRestored: Bool
    private(set) var digest: String

    init(tvID: String, profileID: String, sourcePlanDigest: String, items: [Item]) {
        id = UUID()
        self.tvID = tvID
        self.profileID = profileID
        createdAt = Date()
        self.sourcePlanDigest = sourcePlanDigest
        self.items = items
        isRestored = false
        digest = ""
        digest = Self.makeDigest(
            id: id,
            tvID: tvID,
            profileID: profileID,
            createdAt: createdAt,
            sourcePlanDigest: sourcePlanDigest,
            items: items,
            isRestored: isRestored
        )
    }

    var isDigestValid: Bool {
        !items.isEmpty
            && Set(items.map(\.package)).count == items.count
            && items.allSatisfy({ TVPackageListParser.isValidPackageName($0.package) })
            && sourcePlanDigest.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
            && digest == Self.makeDigest(
                id: id,
                tvID: tvID,
                profileID: profileID,
                createdAt: createdAt,
                sourcePlanDigest: sourcePlanDigest,
                items: items,
                isRestored: isRestored
            )
    }

    mutating func markRestored() {
        isRestored = true
        digest = Self.makeDigest(
            id: id,
            tvID: tvID,
            profileID: profileID,
            createdAt: createdAt,
            sourcePlanDigest: sourcePlanDigest,
            items: items,
            isRestored: isRestored
        )
    }

    private static func makeDigest(
        id: UUID,
        tvID: String,
        profileID: String,
        createdAt: Date,
        sourcePlanDigest: String,
        items: [Item],
        isRestored: Bool
    ) -> String {
        let fields = [
            id.uuidString,
            tvID,
            profileID,
            String(Int64(createdAt.timeIntervalSince1970 * 1000)),
            sourcePlanDigest,
            isRestored ? "restored" : "active"
        ] + items.sorted { $0.package < $1.package }.flatMap {
            [$0.package, $0.wasDisabled ? "1" : "0"]
        }
        let bytes = Data(fields.map { "\($0.utf8.count):\($0)" }.joined(separator: "|").utf8)
        return SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }
}

struct TVRestorePointStore {
    private let fileManager = FileManager.default
    private let directoryURL: URL?

    init(directoryURL: URL? = nil) {
        self.directoryURL = directoryURL
    }

    func load(tvID: String) throws -> TVRestorePoint? {
        let url = try fileURL()
        guard fileManager.fileExists(atPath: url.path) else { return nil }
        do {
            let data = try Data(contentsOf: url)
            guard data.count <= 2 * 1024 * 1024 else { throw TVReviewPlanError.invalidPlan }
            let points = try JSONDecoder().decode([String: TVRestorePoint].self, from: data)
            guard points.allSatisfy({ $0.key == $0.value.tvID && $0.value.isDigestValid }) else {
                throw TVReviewPlanError.invalidPlan
            }
            return points[tvID]
        } catch let error as TVReviewPlanError {
            throw error
        } catch {
            throw TVReviewPlanError.storage(error.localizedDescription)
        }
    }

    func save(_ point: TVRestorePoint) throws {
        guard point.isDigestValid else { throw TVReviewPlanError.invalidPlan }
        let url = try fileURL()
        var points: [String: TVRestorePoint] = [:]
        if fileManager.fileExists(atPath: url.path) {
            do {
                let data = try Data(contentsOf: url)
                guard data.count <= 2 * 1024 * 1024 else { throw TVReviewPlanError.invalidPlan }
                points = try JSONDecoder().decode([String: TVRestorePoint].self, from: data)
                guard points.allSatisfy({ $0.key == $0.value.tvID && $0.value.isDigestValid }) else {
                    throw TVReviewPlanError.invalidPlan
                }
            } catch let error as TVReviewPlanError {
                throw error
            } catch {
                throw TVReviewPlanError.storage(error.localizedDescription)
            }
        }
        points[point.tvID] = point
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        do {
            try encoder.encode(points).write(to: url, options: .atomic)
            try fileManager.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: url.path
            )
        } catch {
            throw TVReviewPlanError.storage(error.localizedDescription)
        }
    }

    private func fileURL() throws -> URL {
        let base: URL
        if let directoryURL {
            base = directoryURL
        } else {
            do {
                base = try fileManager.url(
                    for: .applicationSupportDirectory,
                    in: .userDomainMask,
                    appropriateFor: nil,
                    create: true
                )
                .appendingPathComponent("CleanYourTV", isDirectory: true)
            } catch {
                throw TVReviewPlanError.storage(error.localizedDescription)
            }
        }
        do {
            try fileManager.createDirectory(
                at: base,
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
            )
        } catch {
            throw TVReviewPlanError.storage(error.localizedDescription)
        }
        return base.appendingPathComponent("restore-points.json", isDirectory: false)
    }
}

enum TVReviewPlanError: LocalizedError {
    case unknownPackage
    case invalidPlan
    case invalidSnapshot
    case storage(String)

    var errorDescription: String? {
        switch self {
        case .unknownPackage: "That package is not part of this review plan."
        case .invalidPlan: "The saved review plan failed its integrity check."
        case .invalidSnapshot: "The TV safety snapshot is incomplete or invalid."
        case .storage(let detail): "Could not save the review plan: \(detail)"
        }
    }
}

struct TVReviewPlanStore {
    private let fileManager = FileManager.default
    private let directoryURL: URL?

    init(directoryURL: URL? = nil) {
        self.directoryURL = directoryURL
    }

    func load(tvID: String) throws -> TVReviewPlan? {
        let url = try fileURL()
        guard fileManager.fileExists(atPath: url.path) else { return nil }
        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            throw TVReviewPlanError.storage(error.localizedDescription)
        }
        guard data.count <= 2 * 1024 * 1024 else { throw TVReviewPlanError.invalidPlan }
        let plans: [String: TVReviewPlan]
        do {
            plans = try JSONDecoder().decode([String: TVReviewPlan].self, from: data)
        } catch {
            throw TVReviewPlanError.invalidPlan
        }
        guard let plan = plans[tvID] else { return nil }
        guard plan.tvID == tvID, plan.isDigestValid else { throw TVReviewPlanError.invalidPlan }
        return plan
    }

    func save(_ plan: TVReviewPlan) throws {
        guard plan.isDigestValid else { throw TVReviewPlanError.invalidPlan }
        let url = try fileURL()
        var plans: [String: TVReviewPlan] = [:]
        if fileManager.fileExists(atPath: url.path) {
            do {
                let data = try Data(contentsOf: url)
                guard data.count <= 2 * 1024 * 1024 else { throw TVReviewPlanError.invalidPlan }
                plans = try JSONDecoder().decode([String: TVReviewPlan].self, from: data)
                guard plans.allSatisfy({ $0.key == $0.value.tvID && $0.value.isDigestValid }) else {
                    throw TVReviewPlanError.invalidPlan
                }
            } catch let error as TVReviewPlanError {
                throw error
            } catch {
                throw TVReviewPlanError.storage(error.localizedDescription)
            }
        }
        plans[plan.tvID] = plan
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        do {
            let data = try encoder.encode(plans)
            try data.write(to: url, options: .atomic)
            try fileManager.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: url.path
            )
        } catch {
            throw TVReviewPlanError.storage(error.localizedDescription)
        }
    }

    private func fileURL() throws -> URL {
        if let directoryURL {
            do {
                try fileManager.createDirectory(
                    at: directoryURL,
                    withIntermediateDirectories: true,
                    attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
                )
            } catch {
                throw TVReviewPlanError.storage(error.localizedDescription)
            }
            return directoryURL.appendingPathComponent("review-plans.json", isDirectory: false)
        }
        let base: URL
        do {
            base = try fileManager.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )
        } catch {
            throw TVReviewPlanError.storage(error.localizedDescription)
        }
        let directory = base.appendingPathComponent("CleanYourTV", isDirectory: true)
        do {
            try fileManager.createDirectory(
                at: directory,
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
            )
        } catch {
            throw TVReviewPlanError.storage(error.localizedDescription)
        }
        return directory.appendingPathComponent("review-plans.json", isDirectory: false)
    }
}
