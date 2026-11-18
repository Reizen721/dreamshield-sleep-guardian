import SwiftUI

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if horizontalSizeClass == .regular {
                NavigationSplitView {
                    List {
                        sidebarButton(.home, title: "首页", symbol: "house")
                        sidebarButton(.history, title: "记录", symbol: "clock")
                        sidebarButton(.control, title: "控制", symbol: "slider.horizontal.3")
                        sidebarButton(.settings, title: "设置", symbol: "gearshape")
                    }
                    .navigationTitle("梦盾")
                    .safeAreaInset(edge: .bottom) {
                        VStack(alignment: .leading, spacing: 7) {
                            Text("DREAMSHIELD").font(.caption.weight(.semibold)).tracking(2)
                            Text("安静守护，每一个好梦。")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(24)
                    }
                    .navigationSplitViewColumnWidth(min: 220, ideal: 245, max: 290)
                } detail: {
                    NavigationStack { page(for: store.tab) }
                }
            } else {
                TabView(selection: $store.tab) {
                    NavigationStack { HomeView() }
                        .tabItem { Label("首页", systemImage: "house") }.tag(AppTab.home)
                    NavigationStack { HistoryView() }
                        .tabItem { Label("记录", systemImage: "clock") }.tag(AppTab.history)
                    NavigationStack { ControlView() }
                        .tabItem { Label("控制", systemImage: "slider.horizontal.3") }.tag(AppTab.control)
                    NavigationStack { SettingsView() }
                        .tabItem { Label("设置", systemImage: "gearshape") }.tag(AppTab.settings)
                }
            }
        }
        .tint(DreamPalette.accent)
        .safeAreaInset(edge: .top, spacing: 0) {
            if store.isDemo { DemoBanner() }
        }
        .dreamMotionAccessibility()
        .sheet(isPresented: Binding(
            get: { store.selectedImage != nil },
            set: { if !$0 { store.dismissImage() } }
        )) { ImageDetailView() }
        .sheet(isPresented: $store.showAIConsent, onDismiss: { store.cloudConsent = false }) {
            AIConsentView()
        }
        .sheet(isPresented: $store.showPairingConfirmation, onDismiss: { store.cancelPairing() }) {
            PairingTrustView()
        }
        .dreamNoticeAlert(isActive: !store.showAIConsent && !store.showPairingConfirmation && store.selectedImage == nil)
    }

    @ViewBuilder
    private func page(for tab: AppTab) -> some View {
        switch tab {
        case .home: HomeView()
        case .history: HistoryView()
        case .control: ControlView()
        case .settings: SettingsView()
        }
    }

    private func sidebarButton(_ tab: AppTab, title: String, symbol: String) -> some View {
        Button {
            store.tab = tab
        } label: {
            HStack {
                Label(title, systemImage: symbol).font(.body.weight(store.tab == tab ? .semibold : .regular))
                Spacer()
                if store.tab == tab { Image(systemName: "circle.fill").font(.system(size: 6)) }
            }
            .frame(minHeight: 34)
            .foregroundStyle(store.tab == tab ? DreamPalette.accent : Color.primary)
        }
        .buttonStyle(DreamButtonStyle(.row))
        .listRowBackground(store.tab == tab ? DreamPalette.accent.opacity(0.10) : Color.clear)
        // Animate only this selection affordance, not the destination's live data.
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: store.tab == tab)
        .accessibilityAddTraits(store.tab == tab ? .isSelected : [])
    }
}

private struct DemoBanner: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Label("DEMO · 全部为演示数据", systemImage: "rectangle.inset.filled")
                .font(.caption.weight(.semibold))
            Spacer(minLength: 4)
            Button("退出演示") { Task { await store.setDemo(false) } }
                .font(.caption.weight(.bold))
                .frame(minHeight: 44)
                .buttonStyle(DreamButtonStyle(.plain))
                .disabled(store.busy)
        }
        .padding(.horizontal, 20)
        .foregroundStyle(Color.primary)
        .background(Color.orange.opacity(0.16))
        .accessibilityElement(children: .contain)
    }
}

private struct ImageDetailView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                if let image = store.selectedImage {
                    Image(uiImage: image)
                        .resizable().scaledToFit()
                        .accessibilityLabel("所选抓拍图片")
                    PrimaryAction(title: "保存到照片", symbol: "square.and.arrow.down") {
                        Task { await store.saveImage(image) }
                    }
                    .disabled(store.busy || store.isDemo)
                }
                Advisory(text: store.isDemo ? "演示图片不写入照片图库。" : "仅添加这一张图片到系统照片，不读取你的照片图库。")
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(DreamPalette.background)
            .navigationTitle(store.selectedImageTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { store.dismissImage() }
                }
            }
        }
        .dreamMotionAccessibility()
        .dreamNoticeAlert()
    }
}

private struct PairingTrustView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label("确认这是你的电脑", systemImage: "checkmark.shield")
                        .font(.title3.bold())
                    Text("请将下面的服务器地址及证书指纹，与电脑配对窗口显示的内容逐项核对。只有一致时才继续。")
                }
                if let pairing = store.pendingPairing {
                    Section("服务器") {
                        Text(pairing.baseURL).font(.callout.monospaced()).textSelection(.enabled)
                    }
                    Section("证书 SHA-256 指纹") {
                        Text(pairing.certificateSHA256)
                            .font(.footnote.monospaced())
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Section {
                    Button {
                        Task { await store.confirmPairing() }
                    } label: {
                        Label("已核对一致，建立配对", systemImage: "link")
                            .frame(minHeight: 34)
                    }
                    .buttonStyle(DreamButtonStyle(.row))
                    .disabled(store.busy || store.pendingPairing == nil)
                } footer: {
                    Text("连接使用 HTTPS，并绑定你确认的证书。不要信任他人发送的不明配对资料。")
                }
            }
            .navigationTitle("信任这台服务器")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(store.busy)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { store.cancelPairing(); dismiss() }.disabled(store.busy)
                }
                if store.busy {
                    ToolbarItem(placement: .confirmationAction) { ProgressView().accessibilityLabel("正在配对") }
                }
            }
        }
        .dreamMotionAccessibility()
        .dreamNoticeAlert()
    }
}

private struct AIConsentView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label("让 AI 帮你观察", systemImage: "sparkles")
                        .font(.title3.bold())
                    Text("电脑服务器会将抓拍图片发送到你配置的第三方视觉模型服务。图片可能包含人物和卧室环境，服务可能按次收费。")
                    Advisory(text: "请确保画面中所有人知情同意。模型可能误判，不能代替医疗监护。", symbol: "hand.raised")
                }
                Section("本次调用预算") {
                    DetailLine(title: "最短对比间隔", value: "\(store.aiInterval) 秒")
                    DetailLine(title: "每小时上限", value: "\(store.aiHourly) 次")
                    DetailLine(title: "本次服务器上限", value: "\(store.aiSession) 次")
                }
                Section {
                    Toggle("我同意上传图片，并了解可能产生的费用", isOn: $store.cloudConsent)
                    Button {
                        Task { await store.confirmAIStart() }
                    } label: {
                        Label("同意并开启", systemImage: "sparkles").frame(minHeight: 34)
                    }
                    .buttonStyle(DreamButtonStyle(.row))
                    .disabled(!store.cloudConsent || store.busy || !store.connected || store.isDemo)
                } footer: {
                    Text("开启会自动打开电脑定时抓拍及温湿度记录；调用上限包含确认帧和失败请求。手机退到后台、关闭预览或解除配对不会停止电脑任务。结束实验时请在控制页明确停止 AI；定时抓拍需另行关闭并保存。")
                }
                Section("空调控制说明") {
                    Text(store.controller.title).font(.headline)
                    Text(store.controller.detail).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("图片上传授权")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(store.busy)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("暂不开启") { store.cloudConsent = false; dismiss() }.disabled(store.busy)
                }
                if store.busy {
                    ToolbarItem(placement: .confirmationAction) { ProgressView().accessibilityLabel("正在开启 AI") }
                }
            }
        }
        .dreamMotionAccessibility()
        .dreamNoticeAlert()
    }
}
