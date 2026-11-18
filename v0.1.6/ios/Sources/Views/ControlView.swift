import SwiftUI

struct ControlView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Label("按照你的节奏，安静守护。", systemImage: "slider.horizontal.3")
                        .font(.headline)
                    Text("记录与 AI 在电脑上运行。手机负责查看与管理，无需一直亮屏。")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                .padding(.vertical, 8)
            }

            Section {
                Toggle(isOn: $store.scheduleEnabled) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("定时保存图片")
                        Text("关闭并保存后，自动 AI 也会停止。")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                NumericSetting(title: "抓拍间隔", unit: "秒", value: $store.captureInterval, range: 5...86400)
                    .disabled(!store.scheduleEnabled)
                Toggle("记录温湿度", isOn: $store.environmentEnabled)
                Button {
                    Task { await store.saveConfiguration() }
                } label: {
                    Label("保存记录设置", systemImage: "checkmark.circle")
                        .frame(minHeight: 34)
                }
                .buttonStyle(DreamButtonStyle(.row))
                .disabled(!canWrite)
            } header: {
                Text("后台记录")
            } footer: {
                Text("更改后需点击保存。图片与环境记录保存在电脑；传感器失效或数据过期时不会显示为实时读数。")
            }
            .disabled(store.isDemo || store.busy || !store.connected)

            Section {
                StatusPill(text: store.ai.state, color: store.ai.enabled ? .indigo : .secondary, symbol: "sparkles")
                    .padding(.vertical, 3)
                DetailLine(title: "最近观察", value: store.ai.event)
                DetailLine(title: "后续确认", value: store.ai.confirmation)
                DetailLine(title: "调用情况", value: store.ai.calls)
                if store.ai.enabled {
                    Button(role: .destructive) {
                        Task { await store.stopAI() }
                    } label: {
                        Label("停止自动 AI", systemImage: "stop.circle").frame(minHeight: 34)
                    }
                    .buttonStyle(DreamButtonStyle(.row))
                    .disabled(!canWrite)
                } else {
                    Button {
                        store.requestAIStart()
                    } label: {
                        Label("了解并开启 AI 观察", systemImage: "sparkles").frame(minHeight: 34)
                    }
                    .buttonStyle(DreamButtonStyle(.row))
                    .disabled(!canWrite)
                }
            } header: {
                Text("AI 观察")
            } footer: {
                Text("开启前会说明上传范围和调用预算，并再次征求你的同意。停止 AI 不会自动停止定时抓拍。")
            }

            Section {
                Label(store.controller.title, systemImage: "air.conditioner.horizontal")
                    .font(.headline)
                Text(store.controller.detail)
                    .font(.subheadline).foregroundStyle(.secondary)
                Advisory(text: "手机端尚未提供温度调节。电脑可能另有已开启的空调联动；红外发射完成不等于空调已执行。", symbol: "exclamationmark.shield")
            } header: {
                Text("电脑侧控制状态")
            }

            Section("后续能力") {
                PendingFeature(title: "手机空调遥控", symbol: "air.conditioner.horizontal", detail: "待红外控制与执行验证完成后开放。")
                PendingFeature(title: "事件推送", symbol: "bell.badge", detail: "尚未接入通知服务；当前请在 APP 内查看结果。")
                PendingFeature(title: "设备固件更新", symbol: "arrow.down.circle", detail: "当前版本不在手机端安装设备固件。")
            }
        }
        .scrollContentBackground(.hidden)
        .background(DreamPalette.background)
        .navigationTitle("守护方式")
        .refreshable { await store.refresh() }
        .toolbar {
            if store.busy {
                ToolbarItem(placement: .topBarTrailing) {
                    ProgressView().accessibilityLabel("正在与服务器同步")
                }
            }
        }
    }

    private var canWrite: Bool { store.connected && !store.busy && !store.isDemo }
}
