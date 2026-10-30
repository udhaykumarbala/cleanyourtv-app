import Foundation
import Network

struct ADBService: Identifiable {
    let id: String
    let name: String
    let endpoint: NWEndpoint
}

enum IPv4Address {
    static func isValid(_ value: String) -> Bool {
        let octets = value.split(separator: ".", omittingEmptySubsequences: false)
        guard octets.count == 4 else { return false }
        return octets.allSatisfy { octet in
            guard !octet.isEmpty, octet.count <= 3,
                  octet.allSatisfy(\.isNumber),
                  let number = Int(octet) else { return false }
            return (0...255).contains(number)
        }
    }
}

@MainActor
final class TVDiscoveryModel: ObservableObject {
    @Published private(set) var services: [ADBService] = []
    @Published private(set) var isSearching = false
    @Published private(set) var isConnecting = false
    @Published private(set) var isReadingPackages = false
    @Published private(set) var identity: TVIdentity?
    @Published private(set) var packages: [TVPackage]?
    @Published private(set) var configuration: TVConfigurationSnapshot?
    @Published private(set) var profileResolution: TVProfileResolution?
    @Published private(set) var safetyReport: TVSafetyReport?
    @Published private(set) var reviewPlan: TVReviewPlan?
    @Published private(set) var restorePoint: TVRestorePoint?
    @Published private(set) var isApplyingPlan = false
    @Published private(set) var isRestoringPlan = false
    @Published private(set) var homeSwitchStatus: TVHomeSwitchStatus?
    @Published private(set) var isChangingHome = false
    @Published private(set) var homeSwitchMessage: String?
    @Published private(set) var launcherLayout: LauncherLayoutState?
    @Published private(set) var isChangingLayout = false
    @Published private(set) var layoutMessage: String?
    @Published private(set) var currentSnapshotDigest: String?
    @Published private(set) var authFingerprint: String?
    @Published private(set) var connectionMessage: String?
    @Published private(set) var planMessage: String?
    @Published private(set) var statusTitle = "Ready to scan"
    @Published private(set) var statusDetail = "Looking for Android TV ADB services."

    private var browser: NWBrowser?
    private var connectionTask: Task<Void, Never>?
    private var tvConnection: ADBTVConnection?
    private var profileCatalog: TVProfileCatalog?
    private let reviewPlanStore = TVReviewPlanStore()
    private let restorePointStore = TVRestorePointStore()
    private let layoutBackupStore = TVLauncherBackupStore()

    var reviewPlanIsStale: Bool {
        guard let reviewPlan else { return false }
        return reviewPlan.isStale(against: currentSnapshotDigest)
    }

    func start() {
        guard browser == nil else { return }
        services = []
        isSearching = true
        statusTitle = "Searching your network"
        statusDetail = "Your iPhone and TV need to be on the same Wi-Fi."

        let browser = NWBrowser(
            for: .bonjour(type: "_adb._tcp.", domain: "local."),
            using: .tcp
        )
        browser.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                guard let self else { return }
                switch state {
                case .ready:
                    self.isSearching = true
                    self.statusTitle = "Scanning for TVs"
                case .failed(let error):
                    self.isSearching = false
                    self.statusTitle = "Network scan unavailable"
                    self.statusDetail = error.localizedDescription
                    self.browser?.cancel()
                    self.browser = nil
                case .cancelled:
                    self.isSearching = false
                default:
                    break
                }
            }
        }
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            let found = results.compactMap { result -> ADBService? in
                guard case let .service(name, type, domain, interface) = result.endpoint else { return nil }
                return ADBService(
                    id: "\(name).\(type).\(domain)",
                    name: name,
                    endpoint: .service(name: name, type: type, domain: domain, interface: interface)
                )
            }
            Task { @MainActor in
                guard let self else { return }
                self.services = found.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
                self.statusTitle = found.isEmpty ? "Scanning for TVs" : "\(found.count) ADB service\(found.count == 1 ? "" : "s") found"
                self.statusDetail = found.isEmpty
                    ? "Keep the TV on and network debugging enabled."
                    : "Found on your local network. Authorization is still required."
            }
        }
        self.browser = browser
        browser.start(queue: .main)
    }

    func stop() {
        browser?.cancel()
        browser = nil
        isSearching = false
        connectionTask?.cancel()
        connectionTask = nil
        tvConnection?.close()
        tvConnection = nil
        isConnecting = false
        isReadingPackages = false
        isApplyingPlan = false
        isRestoringPlan = false
        packages = nil
        configuration = nil
        profileResolution = nil
        safetyReport = nil
        reviewPlan = nil
        restorePoint = nil
        currentSnapshotDigest = nil
        identity = nil
        authFingerprint = nil
        connectionMessage = nil
    }

    func connect(to endpoint: NWEndpoint) {
        connectionTask?.cancel()
        tvConnection?.close()
        tvConnection = nil
        identity = nil
        packages = nil
        isReadingPackages = false
        configuration = nil
        profileResolution = nil
        safetyReport = nil
        reviewPlan = nil
        currentSnapshotDigest = nil
        authFingerprint = nil
        connectionMessage = nil
        planMessage = nil
        isConnecting = true
        isApplyingPlan = false
        isRestoringPlan = false
        restorePoint = nil
        homeSwitchStatus = nil
        homeSwitchMessage = nil
        isChangingHome = false
        launcherLayout = nil
        layoutMessage = nil
        isChangingLayout = false
        connectionTask = Task { [weak self] in
            do {
                let (connection, identity) = try await ADBTVConnection.open(endpoint: endpoint) { fingerprint in
                    self?.authFingerprint = fingerprint
                    self?.connectionMessage = "Approve the debugging prompt on your TV. The key fingerprint is shown below."
                }
                guard !Task.isCancelled else {
                    connection.close()
                    return
                }
                self?.tvConnection = connection
                self?.identity = identity
                if let self {
                    do {
                        self.reviewPlan = try self.reviewPlanStore.load(tvID: identity.tvID)
                        self.restorePoint = try self.restorePointStore.load(tvID: identity.tvID)
                        self.planMessage = nil
                    } catch {
                        self.reviewPlan = nil
                        self.planMessage = error.localizedDescription
                    }
                }
                self?.authFingerprint = nil
                self?.connectionMessage = "TV identified. Review the safety report before applying package changes."
                self?.isConnecting = false
            } catch is CancellationError {
                self?.isConnecting = false
            } catch {
                self?.connectionMessage = error.localizedDescription
                self?.isConnecting = false
            }
        }
    }

    func readPackages() {
        guard let tvConnection, let identity, !isReadingPackages else { return }
        isReadingPackages = true
        packages = nil
        configuration = nil
        profileResolution = nil
        safetyReport = nil
        currentSnapshotDigest = nil
        connectionMessage = nil
        connectionTask = Task { [weak self] in
            do {
                let packages = try await tvConnection.readPackageInventory()
                guard !Task.isCancelled else { return }
                guard let self else { return }
                let catalog = try self.profileCatalog ?? TVProfileCatalog.loadBundled()
                self.profileCatalog = catalog
                let resolution = catalog.resolve(identity: identity)
                let installedNames = Set(packages.map(\.name))
                let profileCandidates = Set(resolution.groups.flatMap(\.packages).map(\.name))
                    .intersection(installedNames)
                let snapshot = try await tvConnection.readSafetySnapshot(
                    packages: packages,
                    candidatePackageNames: profileCandidates
                )
                guard !Task.isCancelled else { return }
                self.packages = snapshot.packages
                self.configuration = snapshot.configuration
                self.homeSwitchStatus = TVHomeSwitchStatus(
                    packages: Set(snapshot.packages.map(\.name)),
                    services: snapshot.configuration.settings.first { $0.setting == .enabledAccessibilityServices }?.value
                )
                self.profileResolution = resolution
                self.safetyReport = TVSafetyAnalyzer.analyze(
                    identity: identity,
                    resolution: resolution,
                    snapshot: snapshot
                )
                self.currentSnapshotDigest = TVSafetyAnalyzer.snapshotDigest(
                    identity: identity,
                    resolution: resolution,
                    snapshot: snapshot
                )
                self.connectionMessage = "Read TV safety details and prepared a read-only review. No changes were made."
                self.isReadingPackages = false
            } catch is CancellationError {
                self?.isReadingPackages = false
            } catch {
                self?.connectionMessage = error.localizedDescription
                self?.isReadingPackages = false
            }
        }
    }

    func createReviewPlan() {
        guard let identity, let profileResolution, let safetyReport, let currentSnapshotDigest else {
            planMessage = "Analyze the TV before creating a review plan."
            return
        }
        guard safetyReport.incompleteReasons.isEmpty else {
            planMessage = "A review plan is unavailable until all required safety checks pass."
            return
        }
        let plan = TVReviewPlan(
            tvID: identity.tvID,
            profileID: profileResolution.profileID,
            profileTitle: profileResolution.title,
            snapshotDigest: currentSnapshotDigest,
            report: safetyReport
        )
        guard !plan.items.isEmpty else {
            planMessage = "This profile has no installed packages available for a review plan."
            return
        }
        do {
            try reviewPlanStore.save(plan)
            reviewPlan = plan
            planMessage = "Review plan saved privately on this iPhone."
        } catch {
            planMessage = error.localizedDescription
        }
    }

    func setReviewPlanChoice(package: String, selected: Bool) {
        guard var plan = reviewPlan else { return }
        guard !reviewPlanIsStale else {
            planMessage = "This draft is based on an older TV snapshot. Analyze the TV and create a new draft."
            return
        }
        do {
            try plan.setSelected(selected, for: package)
            try reviewPlanStore.save(plan)
            reviewPlan = plan
            planMessage = "Review plan updated."
        } catch {
            planMessage = error.localizedDescription
        }
    }

    var applyReviewPlanBlockReason: String? {
        guard let reviewPlan else { return "Create a reviewable plan from this analysis first." }
        guard !reviewPlanIsStale else { return "This plan is out of date. Replace it with the latest analysis." }
        guard reviewPlan.isDigestValid else { return "This plan failed its integrity check. Create a new plan." }
        guard !reviewPlan.selectedItems.isEmpty else { return "Select at least one package to apply." }
        guard reviewPlan.selectedItems.allSatisfy({ $0.status == .reviewCandidate }) else {
            return "Deselect optional packages. Only review candidates can be applied."
        }
        guard let safetyReport else { return "Analyze the TV before applying this plan." }
        guard safetyReport.incompleteReasons.isEmpty else {
            return "Safety checks are incomplete: \(safetyReport.incompleteReasons.joined(separator: " "))"
        }
        if restorePoint?.isRestored == false {
            return "Restore the original package states before applying another plan."
        }
        guard tvConnection != nil, identity != nil, profileResolution != nil, currentSnapshotDigest != nil else {
            return "Reconnect to the TV and analyze it before applying this plan."
        }
        if isApplyingPlan { return "Applying the selected package changes." }
        if isRestoringPlan { return "Wait for package restoration to finish." }
        return nil
    }

    var canApplyReviewPlan: Bool { applyReviewPlanBlockReason == nil }

    func applyReviewPlan() {
        guard canApplyReviewPlan,
              let tvConnection,
              let identity,
              let profileResolution,
              let plan = reviewPlan,
              let expectedDigest = currentSnapshotDigest else {
            planMessage = "Re-scan the TV and resolve any active restore point before applying this plan."
            return
        }
        isApplyingPlan = true
        planMessage = "Rechecking safety and saving a restore point before making changes."
        connectionTask?.cancel()
        connectionTask = Task { [weak self] in
            guard let self else { return }
            do {
                let current = try await self.readCurrentSafetyState(
                    connection: tvConnection,
                    identity: identity
                )
                guard current.digest == expectedDigest,
                      current.digest == plan.snapshotDigest,
                      current.report.incompleteReasons.isEmpty,
                      plan.selectedItems.allSatisfy({ item in
                          current.report.rows.first(where: { $0.package == item.package })?.status == .reviewCandidate
                      }) else {
                    throw TVReviewPlanError.invalidSnapshot
                }
                let currentPackages = Dictionary(current.snapshot.packages.map { ($0.name, $0) }, uniquingKeysWith: { first, _ in first })
                let items = try plan.selectedItems.map { item -> TVRestorePoint.Item in
                    guard item.status == .reviewCandidate,
                          let package = currentPackages[item.package],
                          !package.isDisabled else {
                        throw TVReviewPlanError.invalidSnapshot
                    }
                    return TVRestorePoint.Item(package: item.package, wasDisabled: package.isDisabled)
                }
                let point = TVRestorePoint(
                    tvID: identity.tvID,
                    profileID: profileResolution.profileID,
                    sourcePlanDigest: plan.planDigest,
                    items: items
                )
                try self.restorePointStore.save(point)
                self.restorePoint = point
                for item in point.items where item.wasDisabled != true {
                    try await tvConnection.setPackageDisabled(item.package, disabled: true)
                    let inventory = try await tvConnection.readPackageInventory()
                    guard let changed = inventory.first(where: { $0.name == item.package }),
                          changed.isDisabled else {
                        throw TVReviewPlanError.invalidSnapshot
                    }
                }
                self.currentSnapshotDigest = nil
                self.isApplyingPlan = false
                self.planMessage = "Selected packages were disabled. A restore point is saved on this iPhone."
            } catch {
                self.isApplyingPlan = false
                self.currentSnapshotDigest = nil
                self.planMessage = "Application stopped. The restore point was kept so you can restore the original package states. \(error.localizedDescription)"
            }
        }
    }

    func restoreReviewPlan() {
        guard !isApplyingPlan, !isRestoringPlan,
              let tvConnection,
              let identity,
              var point = restorePoint,
              point.tvID == identity.tvID,
              point.isDigestValid,
              !point.isRestored else {
            planMessage = "No active, valid restore point is available for this TV."
            return
        }
        isRestoringPlan = true
        planMessage = "Restoring the saved package states."
        connectionTask?.cancel()
        connectionTask = Task { [weak self] in
            guard let self else { return }
            do {
                var inventory = try await tvConnection.readPackageInventory()
                for item in point.items {
                    guard let current = inventory.first(where: { $0.name == item.package }) else {
                        throw ADBSessionError.protocolViolation("cannot restore \(item.package) because it is no longer installed")
                    }
                    if current.isDisabled != item.wasDisabled {
                        try await tvConnection.setPackageDisabled(item.package, disabled: item.wasDisabled)
                        inventory = try await tvConnection.readPackageInventory()
                        guard let restored = inventory.first(where: { $0.name == item.package }),
                              restored.isDisabled == item.wasDisabled else {
                            throw ADBSessionError.protocolViolation("could not verify the restored state of \(item.package)")
                        }
                    }
                }
                point.markRestored()
                try self.restorePointStore.save(point)
                self.restorePoint = point
                self.currentSnapshotDigest = nil
                self.isRestoringPlan = false
                self.planMessage = "Original package states restored and verified."
            } catch {
                self.isRestoringPlan = false
                self.planMessage = "Restore is incomplete. The saved restore point remains available; reconnect and retry. \(error.localizedDescription)"
            }
        }
    }

    var homeSwitchBlockReason: String? {
        guard let identity, let profileResolution, tvConnection != nil else { return "Connect to the TV and analyze it first." }
        guard TVHomeInterceptor.isFireTV(identity, platform: profileResolution.platform) else {
            return "This applies to Fire TV only; other TVs can take a launcher as their home screen directly."
        }
        if isChangingHome { return "Working on the TV." }
        if isApplyingPlan || isRestoringPlan || isReadingPackages { return "Wait for the current operation to finish." }
        return nil
    }

    var canChangeHome: Bool { homeSwitchBlockReason == nil }

    // Installs the two bundled APKs and turns the service on, keeping the services already enabled.
    func installHomeInterceptor() {
        guard canChangeHome, let tvConnection else { return }
        isChangingHome = true
        homeSwitchMessage = "Installing the launcher and the Home button service."
        connectionTask?.cancel()
        connectionTask = Task { [weak self] in
            guard let self else { return }
            do {
                let launcher = try TVHomeInterceptor.load(TVHomeInterceptor.launcher)
                let interceptor = try TVHomeInterceptor.load(TVHomeInterceptor.interceptor)
                try await tvConnection.installBundledAPK(launcher)
                try await tvConnection.installBundledAPK(interceptor)
                let installed = Set(try await tvConnection.readPackageInventory().map(\.name))
                guard TVHomeInterceptor.packages.isSubset(of: installed) else {
                    throw ADBSessionError.protocolViolation("the TV does not list the installed home screen apps")
                }
                let current = try await tvConnection.readSetting(.enabledAccessibilityServices)
                try await tvConnection.writeAccessibilityServices(TVHomeInterceptor.enabling(in: current))
                let after = try await tvConnection.readSetting(.enabledAccessibilityServices)
                guard TVHomeInterceptor.isEnabled(in: after) else {
                    throw ADBSessionError.protocolViolation("the TV did not keep the Home button service switched on")
                }
                self.finishHomeChange(installed: installed, services: after,
                                      message: "Done. Press Home on the TV remote: it now opens the Clean Your TV launcher. Back from Amazon's Settings still works.")
            } catch {
                self.isChangingHome = false
                self.homeSwitchMessage = "Stopped. \(error.localizedDescription) Nothing else was changed; you can try again or remove it."
            }
        }
    }

    // Switches the service off (only ours) and uninstalls the two bundled apps.
    func removeHomeInterceptor() {
        guard canChangeHome, let tvConnection else { return }
        isChangingHome = true
        homeSwitchMessage = "Giving Home back to Amazon."
        connectionTask?.cancel()
        connectionTask = Task { [weak self] in
            guard let self else { return }
            do {
                let current = try await tvConnection.readSetting(.enabledAccessibilityServices)
                try await tvConnection.writeAccessibilityServices(TVHomeInterceptor.disabling(in: current))
                let installed = Set(try await tvConnection.readPackageInventory().map(\.name))
                for package in [TVHomeInterceptor.interceptorPackage, TVHomeInterceptor.launcherPackage] where installed.contains(package) {
                    try await tvConnection.uninstallBundledPackage(package)
                }
                let remaining = Set(try await tvConnection.readPackageInventory().map(\.name))
                let after = try await tvConnection.readSetting(.enabledAccessibilityServices)
                guard remaining.isDisjoint(with: TVHomeInterceptor.packages), !TVHomeInterceptor.isEnabled(in: after) else {
                    throw ADBSessionError.protocolViolation("the TV still has part of the home screen setup")
                }
                self.finishHomeChange(installed: remaining, services: after,
                                      message: "Amazon's home screen is back. The launcher and the Home button service were removed.")
            } catch {
                self.isChangingHome = false
                self.homeSwitchMessage = "Removal is incomplete. \(error.localizedDescription) Reconnect and try again."
            }
        }
    }

    var layoutBlockReason: String? {
        guard tvConnection != nil, identity != nil else { return "Connect to the TV first." }
        guard homeSwitchStatus?.launcherInstalled == true else { return "Install the Clean Your TV launcher first." }
        if isChangingLayout || isChangingHome { return "Working on the TV." }
        if isApplyingPlan || isRestoringPlan || isReadingPackages { return "Wait for the current operation to finish." }
        return nil
    }

    // Reads the apps the launcher can show and the order its config.json has now.
    func loadLauncherLayout() {
        guard layoutBlockReason == nil, let tvConnection, let identity else { return }
        isChangingLayout = true
        layoutMessage = "Reading the apps on the TV."
        connectionTask?.cancel()
        connectionTask = Task { [weak self] in
            guard let self else { return }
            do {
                let disabled = Set((self.packages ?? []).filter(\.isDisabled).map(\.name))
                let apps = try await tvConnection.readLaunchableApps().filter { !disabled.contains($0) }
                let config = try await tvConnection.readLauncherConfig()
                let shown = Set(apps)
                let backup = try self.layoutBackupStore.load(tvID: identity.tvID)
                self.launcherLayout = LauncherLayoutState(
                    apps: apps,
                    order: TVLauncherRows.trim(config.dockApps.filter(shown.contains), columns: config.columns),
                    columns: config.columns,
                    hasBackup: backup != nil
                )
                self.layoutMessage = nil
            } catch {
                self.layoutMessage = "Could not read the layout. \(error.localizedDescription)"
            }
            self.isChangingLayout = false
        }
    }

    func moveLauncherApp(_ package: String, by offset: Int) {
        guard var layout = launcherLayout, let index = layout.order.firstIndex(of: package),
              layout.order.indices.contains(index + offset) else { return }
        layout.order.swapAt(index, index + offset)
        launcherLayout = layout
    }

    func addLauncherApp(_ package: String) {
        guard var layout = launcherLayout, !layout.order.contains(package),
              layout.order.count < TVLauncherRows.capacity(columns: layout.columns) else { return }
        layout.order.append(package)
        launcherLayout = layout
    }

    func removeLauncherApp(_ package: String) {
        guard var layout = launcherLayout else { return }
        layout.order.removeAll { $0 == package }
        launcherLayout = layout
    }

    // Writes the chosen order, keeping every other key of the TV's config.json and a copy of the file as it was.
    func applyLauncherLayout() {
        guard layoutBlockReason == nil, let tvConnection, let identity, let layout = launcherLayout else { return }
        isChangingLayout = true
        layoutMessage = "Sending the app order to the TV."
        connectionTask?.cancel()
        connectionTask = Task { [weak self] in
            guard let self else { return }
            do {
                let current = try await tvConnection.readLauncherConfig()
                let next = try current.applying(order: layout.order)
                try self.layoutBackupStore.save(TVLauncherBackup(config: current.original, savedAt: Date()), tvID: identity.tvID)
                try await tvConnection.writeLauncherConfig(next)
                let written = try await tvConnection.readLauncherConfig()
                guard written.dockApps == TVLauncherRows.trim(layout.order, columns: current.columns) else {
                    throw ADBSessionError.protocolViolation("the TV did not keep the new app order")
                }
                self.launcherLayout?.hasBackup = true
                self.layoutMessage = "Done. The launcher restarted with your order. Anything you set in the launcher's own settings applies until you choose Follow theme."
            } catch {
                self.layoutMessage = "Stopped. \(error.localizedDescription) The previous order is saved on this iPhone."
            }
            self.isChangingLayout = false
        }
    }

    func restoreLauncherLayout() {
        guard layoutBlockReason == nil, let tvConnection, let identity else { return }
        guard let backup = try? layoutBackupStore.load(tvID: identity.tvID) else {
            layoutMessage = "No saved order for this TV."
            return
        }
        isChangingLayout = true
        layoutMessage = "Putting the previous order back."
        connectionTask?.cancel()
        connectionTask = Task { [weak self] in
            guard let self else { return }
            do {
                try await tvConnection.writeLauncherConfig(backup.config)
                let config = try await tvConnection.readLauncherConfig()
                guard config.original == backup.config || (backup.config == nil && config.object.isEmpty) else {
                    throw ADBSessionError.protocolViolation("the TV did not keep the previous order")
                }
                try? self.layoutBackupStore.remove(tvID: identity.tvID)
                let shown = Set(self.launcherLayout?.apps ?? [])
                self.launcherLayout = LauncherLayoutState(
                    apps: self.launcherLayout?.apps ?? [],
                    order: TVLauncherRows.trim(config.dockApps.filter(shown.contains), columns: config.columns),
                    columns: config.columns,
                    hasBackup: false
                )
                self.layoutMessage = "The previous order is back."
            } catch {
                self.layoutMessage = "Restore is incomplete. \(error.localizedDescription) The saved copy is kept; try again."
            }
            self.isChangingLayout = false
        }
    }

    // The scan no longer matches the TV (a new HOME app appeared or left), so the plan must be re-made.
    private func finishHomeChange(installed: Set<String>, services: String?, message: String) {
        homeSwitchStatus = TVHomeSwitchStatus(packages: installed, services: services)
        currentSnapshotDigest = nil
        isChangingHome = false
        homeSwitchMessage = message
    }

    private func readCurrentSafetyState(
        connection: ADBTVConnection,
        identity: TVIdentity
    ) async throws -> (snapshot: TVSafetySnapshot, report: TVSafetyReport, digest: String) {
        let packages = try await connection.readPackageInventory()
        let catalog = try profileCatalog ?? TVProfileCatalog.loadBundled()
        profileCatalog = catalog
        let resolution = catalog.resolve(identity: identity)
        let installedNames = Set(packages.map(\.name))
        let candidates = Set(resolution.groups.flatMap(\.packages).map(\.name))
            .intersection(installedNames)
        let snapshot = try await connection.readSafetySnapshot(
            packages: packages,
            candidatePackageNames: candidates
        )
        let report = TVSafetyAnalyzer.analyze(identity: identity, resolution: resolution, snapshot: snapshot)
        let digest = TVSafetyAnalyzer.snapshotDigest(identity: identity, resolution: resolution, snapshot: snapshot)
        return (snapshot, report, digest)
    }
}

struct LauncherLayoutState {
    let apps: [String]
    var order: [String]
    let columns: Int
    var hasBackup: Bool

    var others: [String] { apps.filter { !order.contains($0) } }
}
