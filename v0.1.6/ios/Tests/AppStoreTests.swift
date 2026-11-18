import XCTest
@testable import DreamShield

@MainActor
final class AppStoreTests: XCTestCase {
    func testStartsWithoutNetworkOrDemo() {
        let store = AppStore(loadSavedProfile: false)
        XCTAssertFalse(store.isDemo); XCTAssertFalse(store.connected)
        XCTAssertNil(store.profile); XCTAssertNil(store.previewImage)
        XCTAssertEqual(store.sensor.temperature, "—")
    }
    func testExplicitDemoAndBackgroundLock() async {
        let store = AppStore(loadSavedProfile: false)
        store.setForeground(true)
        await store.setDemo(true)
        XCTAssertTrue(store.isDemo); XCTAssertTrue(store.connected)
        for _ in 0..<5 { store.versionTapped() }
        XCTAssertTrue(store.developerUnlocked)
        store.requestAIStart(); XCTAssertTrue(store.showAIConsent); XCTAssertFalse(store.cloudConsent)
        store.setForeground(false)
        XCTAssertFalse(store.developerUnlocked); XCTAssertFalse(store.showAIConsent)
        XCTAssertFalse(store.connected); XCTAssertNil(store.previewImage)
        XCTAssertNil(store.pendingPairing); XCTAssertTrue(store.captures.isEmpty)
    }
    func testNoConsentCannotStartAI() async {
        let store = AppStore(loadSavedProfile: false)
        store.setForeground(true); await store.setDemo(true)
        store.requestAIStart(); await store.confirmAIStart()
        XCTAssertFalse(store.ai.enabled)
        XCTAssertNotNil(store.notice)
        store.setForeground(false)
    }
    func testExitDemoCannotLeakDataIntoLiveView() async {
        let store = AppStore(loadSavedProfile: false)
        store.setForeground(true); await store.setDemo(true); await store.loadHistory()
        XCTAssertFalse(store.captures.isEmpty)
        await store.setDemo(false)
        XCTAssertFalse(store.isDemo); XCTAssertFalse(store.connected)
        XCTAssertTrue(store.status.isEmpty); XCTAssertTrue(store.captures.isEmpty)
        XCTAssertEqual(store.sensor.humidity, "—")
        store.setForeground(false)
    }
}
