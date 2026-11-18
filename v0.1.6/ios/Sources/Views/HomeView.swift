import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var atmosphere = NightAtmosphere.window

    var body: some View {
        GeometryReader { geometry in
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                PageIntro(eyebrow: "DREAMSHIELD · 每一晚的陪伴", title: "今天，也从容一点。", subtitle: "房间的变化，清楚地看见。")
                nightCard(wide: geometry.size.width >= 780 && !dynamicTypeSize.isAccessibilitySize)

                LazyVGrid(columns: cardColumns(for: geometry.size.width), alignment: .leading, spacing: 20) {
                    environmentCard
                    observationCard
                }

                LazyVGrid(columns: cardColumns(for: geometry.size.width), alignment: .leading, spacing: 20) {
                    CameraCard()
                    connectionCard
                }
                .padding(.bottom, 8)
            }
            .frame(maxWidth: 1150)
            .padding(20)
            .frame(maxWidth: .infinity)
        }
        .refreshable { await store.refresh() }
        }
        .background(DreamPalette.background)
        .navigationTitle("梦盾")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("刷新状态", systemImage: "arrow.clockwise") { Task { await store.refresh() } }
                    .disabled(store.busy || (store.profile == nil && !store.isDemo))
            }
        }
    }

    private func cardColumns(for width: CGFloat) -> [GridItem] {
        let count = width >= 740 && !dynamicTypeSize.isAccessibilitySize ? 2 : 1
        return Array(repeating: GridItem(.flexible(), spacing: 20, alignment: .top), count: count)
    }

    private func nightCard(wide: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if wide {
                HStack(spacing: 0) {
                    nightMessage.padding(26).frame(maxWidth: .infinity, alignment: .leading)
                    scene.frame(maxWidth: .infinity)
                }
            } else {
                scene
                nightMessage.padding(22)
            }
            Divider().overlay(DreamPalette.accent.opacity(0.05))
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) {
                    Text("选一种喜欢的夜色").font(.caption).foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    atmospherePicker
                }
                VStack(alignment: .leading, spacing: 12) {
                    Text("选一种喜欢的夜色").font(.caption).foregroundStyle(.secondary)
                    atmospherePicker
                }
            }
            .padding(16)
        }
        .background(DreamPalette.card, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .strokeBorder(DreamPalette.accent.opacity(0.08))
                .allowsHitTesting(false)
        }
    }

    private var scene: some View {
        ZStack {
            NightSceneArtwork(atmosphere: atmosphere)
                .id(atmosphere)
                .transition(reduceMotion ? .identity : .opacity)
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.3), value: atmosphere)
            .overlay(alignment: .bottomTrailing) {
                Text("插画场景 · 非摄像头画面")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.85))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(.black.opacity(0.24), in: Capsule())
                    .padding(12)
            }
    }

    private var nightMessage: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 8) {
                StatusPill(text: store.connectionTitle, color: store.connected ? .teal : .secondary, symbol: store.connected ? "wifi" : "wifi.slash")
                if store.busy { ProgressView().controlSize(.small).accessibilityLabel("正在同步") }
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("把夜晚留给安静。")
                    .font(.system(.title2, design: .rounded).weight(.bold))
                Text(store.ai.state)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) { nightActions }
                VStack(spacing: 12) { nightActions }
            }
        }
    }

    @ViewBuilder
    private var nightActions: some View {
        PrimaryAction(title: store.profile == nil && !store.isDemo ? "连接我的梦盾" : "管理守护", symbol: store.profile == nil && !store.isDemo ? "link" : "slider.horizontal.3") {
            store.tab = store.profile == nil && !store.isDemo ? .settings : .control
        }
        Button {
            store.tab = .history
        } label: {
            Label("查看记录", systemImage: "clock").frame(maxWidth: .infinity, minHeight: 32)
        }
        .buttonStyle(DreamButtonStyle(.secondary))
    }

    private var atmospherePicker: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 6) { atmosphereButtons }
            VStack(alignment: .leading, spacing: 6) { atmosphereButtons }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("夜色场景，仅改变界面配色")
    }

    @ViewBuilder
    private var atmosphereButtons: some View {
        ForEach(NightAtmosphere.allCases) { item in
            Button {
                atmosphere = item
            } label: {
                Label(item.title, systemImage: atmosphere == item ? "checkmark.circle.fill" : item.symbol)
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 9)
                    .frame(minHeight: 44)
                    .background(atmosphere == item ? DreamPalette.accent.opacity(0.10) : Color.clear, in: Capsule())
            }
            .buttonStyle(DreamButtonStyle(.plain))
            .accessibilityAddTraits(atmosphere == item ? .isSelected : [])
        }
    }

    private var connectionCard: some View {
        DreamCard("一直在身边的连接", symbol: "house.and.flag") {
            DetailLine(title: "设备", value: "DreamShield RS081-dev")
            DetailLine(title: "连接状态", value: store.connectionTitle)
            DetailLine(title: "连接方式", value: "家庭 Wi-Fi · 电脑服务器")
            Divider()
            Advisory(text: "手机预览仅在前台刷新；电脑上的记录、AI 与空调联动独立运行。", symbol: "network")
            Button {
                store.tab = .settings
            } label: {
                HStack {
                    Text("连接与设置")
                    Spacer()
                    Image(systemName: "arrow.right")
                }
                .frame(minHeight: 32)
            }
            .buttonStyle(DreamButtonStyle(.secondary))
        }
    }

    private var environmentCard: some View {
        DreamCard("房间的此刻", symbol: "leaf") {
            StatusPill(text: store.sensor.isFresh ? "有效传感器读数" : "等待读数更新", color: store.sensor.isFresh ? .teal : .secondary, symbol: store.sensor.isFresh ? "checkmark.circle" : "clock")
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 20) { sensorTiles }
            } else {
                HStack(alignment: .top, spacing: 24) { sensorTiles }
            }
            Divider()
            Advisory(text: store.sensor.message, symbol: store.sensor.isFresh ? "checkmark.circle" : "clock")
        }
    }

    @ViewBuilder
    private var sensorTiles: some View {
        SensorTile(title: "室内温度", value: store.sensor.temperature, unit: "°C", symbol: "thermometer.medium", color: .orange)
        SensorTile(title: "相对湿度", value: store.sensor.humidity, unit: "%RH", symbol: "humidity", color: .teal)
    }

    private var observationCard: some View {
        DreamCard("安心观察", symbol: "sparkles") {
            StatusPill(text: store.ai.state, color: store.ai.enabled ? .indigo : .secondary, symbol: "sparkles")
            VStack(alignment: .leading, spacing: 12) {
                Text(store.ai.event).font(.headline)
                Text(store.ai.confirmation)
                    .font(.subheadline).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button {
                store.tab = .control
            } label: {
                HStack {
                    Text("管理观察方式")
                    Spacer()
                    Image(systemName: "arrow.right")
                }
                .frame(minHeight: 32)
            }
            .buttonStyle(DreamButtonStyle(.secondary))
        }
    }
}

private struct SensorTile: View {
    let title: String
    let value: String
    let unit: String
    let symbol: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(title, systemImage: symbol)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.system(.largeTitle, design: .rounded).weight(.semibold))
                .foregroundStyle(value == "—" ? Color.secondary : color)
                .monospacedDigit()
                .contentTransition(.numericText())
            Text(unit).font(.caption.weight(.medium)).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title)，\(value) \(unit)")
    }
}

private struct CameraCard: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        DreamCard {
            HStack(alignment: .firstTextBaseline) {
                Label("房间画面", systemImage: "video").font(.headline)
                Spacer()
                if store.previewRunning {
                    StatusPill(text: "预览中", color: .teal)
                }
            }

            ZStack {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(DreamPalette.accent.opacity(0.055))
                if let image = store.previewImage {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .accessibilityLabel("摄像头最新返回的图片")
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "video")
                            .font(.system(size: 34, weight: .light))
                            .foregroundStyle(DreamPalette.accent)
                            .accessibilityHidden(true)
                        Text("此刻，留给安静").font(.headline)
                        Text(store.profile != nil || store.isDemo ? "开启预览，查看摄像头画面。" : "先连接电脑服务器，再看见房间。")
                            .font(.footnote).foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(20)
                }
            }
            .aspectRatio(4.0 / 3.0, contentMode: .fit)
            .frame(minHeight: dynamicTypeSize.isAccessibilitySize ? 280 : 160)
            .overlay(alignment: .bottomLeading) {
                if store.previewImage != nil && !store.previewRunning {
                    Text("预览已暂停 · 非实时画面")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.white)
                        .padding(8)
                        .background(.black.opacity(0.68), in: RoundedRectangle(cornerRadius: 10))
                        .padding(10)
                }
            }

            if let date = store.previewTimestamp {
                Text("画面更新于 \(date.formatted(date: .omitted, time: .standard))")
                    .font(.caption).foregroundStyle(.secondary)
            }

            PrimaryAction(title: store.previewRunning ? "暂停预览" : "开启预览", symbol: store.previewRunning ? "pause.fill" : "play.fill") {
                store.togglePreview()
            }
            .disabled(store.busy || (!store.connected && !store.isDemo))

            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) { secondaryActions }
                VStack(spacing: 12) { secondaryActions }
            }
            Advisory(text: "抓拍保存到电脑；“存入照片”仅保存当前图片到手机。")
        }
    }

    @ViewBuilder
    private var secondaryActions: some View {
        Button {
            Task { await store.capture() }
        } label: {
            Label("抓拍一张", systemImage: "camera")
                .frame(maxWidth: .infinity, minHeight: 32)
        }
        .buttonStyle(DreamButtonStyle(.secondary))
        .disabled(store.busy || !store.connected || store.isDemo)

        Button {
            if let image = store.previewImage { Task { await store.saveImage(image) } }
        } label: {
            Label("存入照片", systemImage: "square.and.arrow.down")
                .frame(maxWidth: .infinity, minHeight: 32)
        }
        .buttonStyle(DreamButtonStyle(.secondary))
        .disabled(store.busy || store.previewImage == nil || store.isDemo)
    }
}
