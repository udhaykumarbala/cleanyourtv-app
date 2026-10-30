import XCTest
@testable import CleanYourTV

final class TVHomeInterceptorTests: XCTestCase {
    private let ours = TVHomeInterceptor.service
    private let other = "com.example.reader/.ReaderService"

    func testEnablingKeepsServicesTheTVAlreadyHad() {
        XCTAssertEqual(TVHomeInterceptor.enabling(in: nil), ours)
        XCTAssertEqual(TVHomeInterceptor.enabling(in: other), "\(other):\(ours)")
        XCTAssertEqual(TVHomeInterceptor.enabling(in: "\(other):\(ours)"), "\(other):\(ours)")
    }

    func testDisablingRemovesOnlyOurService() {
        XCTAssertEqual(TVHomeInterceptor.disabling(in: "\(other):\(ours)"), other)
        XCTAssertEqual(TVHomeInterceptor.disabling(in: ours), "")
        XCTAssertEqual(TVHomeInterceptor.disabling(in: nil), "")
        XCTAssertTrue(TVHomeInterceptor.isEnabled(in: "\(other):\(ours)"))
        XCTAssertFalse(TVHomeInterceptor.isEnabled(in: other))
    }

    func testOurServiceIsAcceptedBySettingValidation() {
        XCTAssertTrue(TVSetting.enabledAccessibilityServices.accepts(TVHomeInterceptor.enabling(in: other)))
    }

    func testBundledAPKsMatchTheirPinnedDigests() throws {
        let bundle = Bundle(for: TVDiscoveryModel.self)
        XCTAssertFalse(try TVHomeInterceptor.load(TVHomeInterceptor.launcher, bundle: bundle).isEmpty)
        XCTAssertFalse(try TVHomeInterceptor.load(TVHomeInterceptor.interceptor, bundle: bundle).isEmpty)
    }

    func testStatusNeedsAllThreePartsToBeActive() {
        let both = TVHomeInterceptor.packages
        XCTAssertTrue(TVHomeSwitchStatus(packages: both, services: ours).isActive)
        XCTAssertFalse(TVHomeSwitchStatus(packages: both, services: other).isActive)
        XCTAssertFalse(TVHomeSwitchStatus(packages: [], services: nil).hasAnyPart)
    }
}
