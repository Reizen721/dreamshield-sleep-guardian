import SwiftUI

@main
@MainActor
struct DreamShieldApp: App {
    @StateObject private var store: AppStore
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Hosted unit tests must not load a real pairing from the simulator's Keychain.
        #if DEBUG
        let testHost = ProcessInfo.processInfo.environment["DREAMSHIELD_TEST_MODE"] == "1"
            || ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
        #else
        let testHost = false
        #endif
        _store = StateObject(wrappedValue: AppStore(loadSavedProfile: !testHost))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .overlay {
                    Group {
                        if scenePhase != .active {
                            ZStack {
                                Color(uiColor: .systemBackground).ignoresSafeArea()
                                Label("梦盾 · 画面已隐藏", systemImage: "lock.shield")
                                    .font(.title3.weight(.medium)).foregroundStyle(.secondary)
                            }
                            .allowsHitTesting(true)
                            .accessibilityLabel("梦盾隐私保护画面")
                        }
                    }
                    .transaction { transaction in
                        // Never fade bedroom imagery through the privacy cover.
                        transaction.animation = nil
                        transaction.disablesAnimations = true
                    }
                }
                .task { if scenePhase == .active { store.setForeground(true) } }
                .onChange(of: scenePhase) { _, phase in
                    // Inactive also occurs during our own system permission alert.
                    // Obscure it, but only cancel the session when actually backgrounded.
                    if phase != .inactive { store.setForeground(phase == .active) }
                }
        }
    }
}
