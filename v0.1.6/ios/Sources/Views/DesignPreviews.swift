import SwiftUI

#if DEBUG
// Xcode-only canvas fixtures. This store never loads a saved profile/Keychain,
// and explicitly opts into offline demo before enabling the synthetic preview.
// Fixed layout traits exercise widths; these are not device screenshots or
// evidence of an actual simulator/device acceptance run.
@MainActor
private struct DesignPreviewHost: View {
    @StateObject private var store = AppStore(loadSavedProfile: false)

    var body: some View {
        RootView()
            .environmentObject(store)
            .task {
                store.setForeground(true)
                await store.setDemo(true)
                store.clearNotice()
                store.togglePreview()
            }
            .onDisappear { store.setForeground(false) }
    }
}

// Purely local controls: no AppStore, credentials, hardware or gateway work.
// Use the live canvas to compare finger/pointer press, drag cancellation,
// disabled state and native toggle feedback before device acceptance.
private struct MotionPreviewGallery: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var taps = 0
    @State private var previewEnabled = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    PageIntro(eyebrow: "OFFLINE INTERACTION LAB", title: "轻触，就有回应。", subtitle: "只演示原生界面反馈，不连接设备或发送控制。")
                    DreamCard("按钮反馈", symbol: "hand.tap") {
                        PrimaryAction(title: "体验轻按回弹", symbol: "play.fill") { taps += 1 }
                        Button { taps += 1 } label: {
                            Label("次要操作", systemImage: "camera")
                        }
                        .buttonStyle(DreamButtonStyle(.secondary))
                        Button { taps += 1 } label: {
                            Label("文字操作", systemImage: "arrow.clockwise")
                        }
                        .buttonStyle(DreamButtonStyle(.plain))
                        Button(role: .destructive) { taps = 0 } label: {
                            Label("清空演示计数", systemImage: "arrow.counterclockwise")
                        }
                        .buttonStyle(DreamButtonStyle(.row))
                        PrimaryAction(title: "禁用操作 · 不触发", symbol: "lock") { taps += 1 }
                            .disabled(true)
                    }
                    DreamCard("原生控件", symbol: "switch.2") {
                        Toggle("界面开关演示", isOn: $previewEnabled)
                        Button { taps += 1 } label: {
                            HStack {
                                Label("可点击的记录行", systemImage: "photo")
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .accessibilityHidden(true)
                            }
                        }
                        .buttonStyle(DreamButtonStyle(.row))
                        DetailLine(title: "本地点击计数", value: "\(taps)")
                        DetailLine(title: "减弱动态效果", value: reduceMotion ? "已开启" : "已关闭")
                        Advisory(text: "按住后拖出按钮或滑动页面，应取消操作；禁用按钮不应增加计数。减弱动态效果开启时仅保留即时状态反馈。")
                    }
                }
                .frame(maxWidth: 640)
                .padding(20)
                .frame(maxWidth: .infinity)
            }
            .background(DreamPalette.background)
            .navigationTitle("交互验收 · 离线")
        }
        .tint(DreamPalette.accent)
        .dreamMotionAccessibility()
    }
}

#Preview("iPhone · 离线 DEMO", traits: .fixedLayout(width: 393, height: 852)) {
    DesignPreviewHost()
        .environment(\.horizontalSizeClass, .compact)
        .preferredColorScheme(.light)
}

#Preview("iPad · 离线 DEMO", traits: .fixedLayout(width: 1194, height: 834)) {
    DesignPreviewHost()
        .environment(\.horizontalSizeClass, .regular)
        .preferredColorScheme(.dark)
}

#Preview("夜窗 · 大字体与减弱动态", traits: .fixedLayout(width: 393, height: 852)) {
    DesignPreviewHost()
        .environment(\.horizontalSizeClass, .compact)
        .environment(\.dynamicTypeSize, .accessibility2)
        .environment(\.accessibilityReduceMotion, true)
        .preferredColorScheme(.dark)
}

#Preview("原创场景 · 三种夜色", traits: .fixedLayout(width: 640, height: 1200)) {
    VStack(spacing: 12) {
        ForEach(NightAtmosphere.allCases) { atmosphere in
            NightSceneArtwork(atmosphere: atmosphere)
                .clipShape(RoundedRectangle(cornerRadius: 24))
        }
    }
    .padding(20)
    .background(DreamPalette.background)
}

#Preview("点击反馈 · 离线", traits: .fixedLayout(width: 393, height: 852)) {
    MotionPreviewGallery()
        .preferredColorScheme(.light)
}

#Preview("减弱动态效果 · 离线", traits: .fixedLayout(width: 768, height: 1024)) {
    MotionPreviewGallery()
        .environment(\.accessibilityReduceMotion, true)
        .preferredColorScheme(.dark)
}
#endif
