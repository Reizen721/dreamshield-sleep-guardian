import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var confirmUnpair = false
    @State private var confirmDemo = false

    var body: some View {
        Form {
            Section {
                HStack(spacing: 16) {
                    Image(systemName: "shield.lefthalf.filled")
                        .font(.system(size: 28, weight: .medium))
                        .foregroundStyle(DreamPalette.accent)
                        .frame(width: 60, height: 60)
                        .background(DreamPalette.accent.opacity(0.09), in: RoundedRectangle(cornerRadius: 18))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 5) {
                        Text("梦盾助手").font(.title3.bold())
                        Text("属于你的安静夜晚")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 8)
            }

            Section("连接与设备") {
                DetailLine(title: "连接状态", value: store.connectionTitle)
                if let profile = store.profile, !store.isDemo {
                    DetailLine(title: "服务器", value: profile.baseURL)
                    DetailLine(title: "设备", value: "DreamShield RS081-dev")
                    Button("刷新连接", systemImage: "arrow.clockwise") { Task { await store.refresh() } }
                        .frame(minHeight: 32)
                        .buttonStyle(DreamButtonStyle(.row))
                        .disabled(store.busy)
                    NavigationLink { PairingView() } label: {
                        Label("更新配对", systemImage: "arrow.triangle.2.circlepath")
                            .frame(minHeight: 32)
                    }
                    .disabled(store.busy)
                    Button("解除配对", role: .destructive) { confirmUnpair = true }
                        .frame(minHeight: 32)
                        .buttonStyle(DreamButtonStyle(.row))
                        .disabled(store.busy)
                } else if !store.isDemo {
                    NavigationLink { PairingView() } label: {
                        Label("连接电脑服务器", systemImage: "link")
                            .frame(minHeight: 32)
                    }
                }
            }

            Section("关于你的数据") {
                NavigationLink { PrivacyView() } label: {
                    Label("隐私与数据", systemImage: "hand.raised")
                        .frame(minHeight: 32)
                }
                NavigationLink { HelpView() } label: {
                    Label("使用帮助", systemImage: "questionmark.circle")
                        .frame(minHeight: 32)
                }
            }

            Section {
                if store.isDemo {
                    Button("退出界面演示", systemImage: "rectangle.portrait.and.arrow.right") {
                        Task { await store.setDemo(false) }
                    }
                    .frame(minHeight: 32)
                    .buttonStyle(DreamButtonStyle(.row))
                    .disabled(store.busy)
                } else {
                    Button("体验界面演示", systemImage: "play.rectangle") { confirmDemo = true }
                        .frame(minHeight: 32)
                        .buttonStyle(DreamButtonStyle(.row))
                        .disabled(store.busy)
                }
                NavigationLink { AboutView() } label: {
                    Label("关于梦盾", systemImage: "info.circle").frame(minHeight: 32)
                }
            } footer: {
                Text("家庭 Wi-Fi 连接 · 电脑服务器常开\n不用于医疗诊断、生命体征或紧急情况监护。")
            }
        }
        .scrollContentBackground(.hidden)
        .background(DreamPalette.background)
        .navigationTitle("设置")
        .confirmationDialog("解除与电脑的配对？", isPresented: $confirmUnpair, titleVisibility: .visible) {
            Button("解除配对", role: .destructive) { Task { await store.unpair() } }
            Button("取消", role: .cancel) {}
        } message: {
            Text("手机将删除本地配对凭据。电脑上的抓拍、AI 和空调联动不会因此停止，已保存的记录也不会删除；若需停机，请先在电脑端或控制页停止相关任务。")
        }
        .confirmationDialog("进入离线界面演示？", isPresented: $confirmDemo, titleVisibility: .visible) {
            Button("进入演示") { Task { await store.setDemo(true) } }
            Button("取消", role: .cancel) {}
        } message: {
            Text("接下来显示的画面、温湿度和记录都是模拟数据，不连接设备、不调用 AI。电脑已有任务仍独立运行。你可随时点击顶部“退出演示”。")
        }
    }
}

private struct PairingView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 12) {
                    Image(systemName: "laptopcomputer.and.iphone")
                        .font(.system(size: 36, weight: .light))
                        .foregroundStyle(DreamPalette.accent)
                        .accessibilityHidden(true)
                    Text("先把梦盾，带到你的手机。")
                        .font(.title3.bold())
                    Text("让 iPhone / iPad 与电脑连接同一个家庭 Wi-Fi，在电脑的手机连接工具中创建一组新配对资料。")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                .padding(.vertical, 10)
            }

            Section {
                TextEditor(text: $store.pairingText)
                    .font(.footnote.monospaced())
                    .frame(minHeight: 160)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .keyboardType(.asciiCapable)
                    .privacySensitive()
                    .accessibilityLabel("电脑生成的配对资料")
                PasteButton(payloadType: String.self) { strings in
                    if let value = strings.first { store.pairingText = value }
                }
                .buttonBorderShape(.capsule)
            } header: {
                Text("粘贴配对资料")
            } footer: {
                Text("请粘贴电脑生成的完整 JSON，不是摄像头网页地址。配对资料包含短时验证码，请勿公开。")
            }

            Section {
                Button {
                    store.preparePairing()
                } label: {
                    Label("检查并继续", systemImage: "arrow.right.circle")
                        .frame(minHeight: 34)
                }
                .buttonStyle(DreamButtonStyle(.row))
                .disabled(store.pairingText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || store.busy || store.isDemo)
            } footer: {
                Text("下一步将核对电脑地址和证书指纹。首次连接时，请允许系统询问的“本地网络”访问权限。")
            }
        }
        .navigationTitle("连接梦盾")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct AboutView: View {
    @EnvironmentObject private var store: AppStore

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—"
    }

    var body: some View {
        Form {
            Section {
                VStack(spacing: 16) {
                    Image(systemName: "shield.lefthalf.filled")
                        .font(.system(size: 48, weight: .light))
                        .foregroundStyle(DreamPalette.accent)
                        .accessibilityHidden(true)
                    Text("梦盾").font(.largeTitle.bold())
                    Text("DREAMSHIELD").font(.caption.weight(.semibold)).tracking(3).foregroundStyle(.secondary)
                    Text("把安心留给夜晚。")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
            }

            Section {
                Button {
                    store.versionTapped()
                } label: {
                    DetailLine(title: "应用版本", value: version)
                        .foregroundStyle(.primary)
                        .frame(minHeight: 34)
                }
                .buttonStyle(DreamButtonStyle(.row))
                .accessibilityLabel("应用版本 \(version)")
                DetailLine(title: "设备型号", value: "DreamShield RS081-dev")
                DetailLine(title: "客户端", value: "iPhone / iPad")
                if store.developerUnlocked {
                    NavigationLink { DeveloperView() } label: {
                        Label("进入开发者工具", systemImage: "wrench.and.screwdriver")
                            .frame(minHeight: 34)
                    }
                }
            }

            Section {
                Advisory(text: "梦盾帮助观察房间环境与被子覆盖变化。AI 可能误判，红外发射不代表空调执行，不作为医疗或安全保障设备。", symbol: "checkmark.shield")
            }
        }
        .navigationTitle("关于梦盾")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { store.resetVersionTaps() }
    }
}

private struct DeveloperView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if store.developerUnlocked {
                Form {
                    Section {
                        Advisory(text: "仅本次前台会话有效。进入后台或退出后会重新锁定；未提交的高级参数不保留。", symbol: "lock.open")
                        DetailLine(title: "工作模式", value: "Wi-Fi - DebuggingServer")
                        DetailLine(title: "设备", value: "DreamShield RS081-dev")
                    }
                    Section {
                        NumericSetting(title: "温湿度记录间隔", unit: "秒", value: $store.environmentInterval, range: 5...3600)
                        NumericSetting(title: "AI 最短对比间隔", unit: "秒", value: $store.aiInterval, range: 60...3600)
                        NumericSetting(title: "每小时调用上限", unit: "次", value: $store.aiHourly, range: 2...120)
                        NumericSetting(title: "本次服务器调用上限", unit: "次", value: $store.aiSession, range: 2...500)
                        Button("前往控制页应用参数", systemImage: "slider.horizontal.3") {
                            store.tab = .control
                        }
                        .frame(minHeight: 34)
                        .buttonStyle(DreamButtonStyle(.row))
                    } header: {
                        Text("高级观察参数")
                    } footer: {
                        Text("修改不会立即写入服务器。记录参数需在控制页保存；AI 参数需明确授权开启后生效。")
                    }
                    .disabled(store.busy || store.isDemo || !store.connected)

                    Section("只读状态摘要") {
                        DetailLine(title: "服务器", value: store.connectionTitle)
                        DetailLine(title: "预览", value: store.previewRunning ? "前台预览中" : "已暂停")
                        DetailLine(title: "AI", value: store.ai.state)
                        DetailLine(title: "环境数据", value: store.sensor.message)
                        DetailLine(title: "控制状态", value: store.controller.title)
                        Advisory(text: "这里不显示令牌、配对验证码、原始请求或卧室图片。")
                    }
                    Section {
                        Button("退出并锁定", role: .destructive) {
                            store.lockDeveloper()
                            dismiss()
                        }
                        .frame(minHeight: 34)
                        .buttonStyle(DreamButtonStyle(.row))
                    }
                }
            } else {
                ContentUnavailableView("开发者工具已锁定", systemImage: "lock", description: Text("仅已解锁的当前前台会话可以访问。"))
            }
        }
        .navigationTitle("开发者工具")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct PrivacyView: View {
    var body: some View {
        Form {
            Section("哪些数据会被使用") {
                privacyRow("房间图片", symbol: "photo", detail: "预览从家庭电脑读取，定时抓拍保存在电脑。只有明确开启 AI 后，电脑才会按已配置的流程把图片发送给第三方模型服务。")
                privacyRow("环境记录", symbol: "thermometer.medium", detail: "温湿度来自 SHT31 传感器，不从图像估算。数据失效或过期会标记，不应当作为实时状态使用。")
                privacyRow("配对资料", symbol: "key", detail: "连接凭据保存在系统钥匙串中，证书指纹用于核验电脑身份。请不要公开配对 JSON 或验证码。")
            }
            Section("系统权限") {
                privacyRow("本地网络", symbol: "network", detail: "用于访问同一家庭网络中的电脑服务器。拒绝后可前往系统设置，开启梦盾的本地网络权限。")
                privacyRow("仅添加照片", symbol: "photo.badge.plus", detail: "只有点击保存图片时才申请。不会扫描、读取或上传你的系统照片图库。")
            }
            Section("管理和删除") {
                Text("解除配对会移除这台手机的本地连接凭据，但不会删除电脑记录或系统照片，也不会关闭电脑任务。")
                Text("抓拍和分析记录请在电脑端管理；已保存到手机的图片请在“照片”中删除。结束实验时，请明确停止 AI，并根据需要关闭定时记录和电脑空调联动。")
            }
            Section("请与同住的人确认") {
                Text("使用摄像头前请取得画面中人员的知情同意。卧室图像属于敏感个人内容；尽量缩小镜头范围，避免不必要的长时间留存。")
                Text("第三方 AI 的保存方式和费用取决于你在电脑端配置的服务。上传前请核查该服务的隐私条款。")
            }
        }
        .navigationTitle("隐私与数据")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func privacyRow(_ title: String, symbol: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(title, systemImage: symbol).font(.headline)
            Text(detail).font(.subheadline).foregroundStyle(.secondary)
        }
        .padding(.vertical, 5)
    }
}

private struct HelpView: View {
    var body: some View {
        Form {
            Section("开始使用") {
                help("1. 连接同一 Wi-Fi", "电脑、设备和手机应位于可互相访问的家庭网络。电脑需保持运行，服务器地址应是电脑的局域网地址，不是 127.0.0.1 或摄像头地址。")
                help("2. 配对你的电脑", "在电脑生成手机配对资料，粘贴到设置中的“连接电脑服务器”，再核对地址与证书指纹。验证码失效后重新生成。")
                help("3. 检查画面", "首页手动开启预览，检查机位和照明。预览暂停时会标注非实时画面；点击抓拍可留下一张电脑记录。")
                help("4. 选择观察方式", "控制页设置定时抓拍并保存。需要 AI 时，单独阅读图片上传和费用说明后授权。")
            }
            Section("常见问题") {
                help("为什么连接不上？", "确认电脑服务已启动、防火墙允许局域网访问、手机已授予本地网络权限。路由器的访客隔离也可能阻止访问。证书更换后需要重新配对。")
                help("手机锁屏后还会记录吗？", "手机预览会暂停；电脑上的定时抓拍与 AI 任务可继续，前提是电脑不休眠且服务器运行。关闭 APP 不等于停止任务。")
                help("温湿度为什么显示横线？", "传感器可能未连接、读数失效或已经过期。请检查设备接线和服务器状态，不把旧值当作当前数据。")
                help("AI 判断不准确怎么办？", "先检查摄像头是否固定、身体与被子是否可见、夜间照明是否充足。模型只作辅助观察，需以实际情况复核。")
                help("手机能直接调节空调吗？", "本版本尚未开放手机遥控。电脑侧联动可独立运行，APP 会显示服务器上报的状态；是否真正执行仍需确认空调本身。")
            }
        }
        .navigationTitle("使用帮助")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func help(_ title: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            Text(detail).font(.subheadline).foregroundStyle(.secondary)
        }
        .padding(.vertical, 7)
    }
}
