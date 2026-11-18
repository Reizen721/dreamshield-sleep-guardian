import SwiftUI

struct HistoryView: View {
    @EnvironmentObject private var store: AppStore
    @State private var selection = 0

    var body: some View {
        List {
            Section {
                Picker("记录类型", selection: $selection) {
                    Text("抓拍图片").tag(0)
                    Text("AI 观察").tag(1)
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 6, trailing: 0))
            }

            if selection == 0 {
                if store.captures.isEmpty {
                    emptyState(title: "还没有抓拍记录", description: "连接服务器后，手动抓拍或开启定时记录。", symbol: "photo.on.rectangle.angled")
                } else {
                    Section {
                        ForEach(store.captures) { item in
                            Button {
                                Task { await store.openCapture(item) }
                            } label: {
                                HStack(spacing: 15) {
                                    Image(systemName: "photo")
                                        .font(.title2)
                                        .foregroundStyle(DreamPalette.accent)
                                        .frame(width: 54, height: 54)
                                        .background(DreamPalette.accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                                        .accessibilityHidden(true)
                                    VStack(alignment: .leading, spacing: 5) {
                                        Text(displayDate(item.createdAt)).font(.subheadline.weight(.semibold))
                                        Text(captureSource(item.source)).font(.footnote).foregroundStyle(.secondary)
                                    }
                                    Spacer(minLength: 0)
                                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary).accessibilityHidden(true)
                                }
                                .padding(.vertical, 5)
                                .foregroundStyle(.primary)
                            }
                            .buttonStyle(DreamButtonStyle(.row))
                            .disabled(store.busy)
                        }
                    } header: {
                        Text("最近的图片 · \(store.captures.count) 张")
                    } footer: {
                        Text("原始抓拍保存在电脑服务器。点开图片后，可以单独保存到 iPhone 或 iPad 照片图库。")
                    }
                }
            } else {
                if store.events.isEmpty {
                    emptyState(title: "观察记录将出现在这里", description: "在控制页授权并开启 AI 后，查看覆盖变化与确认结果。", symbol: "sparkles")
                } else {
                    Section {
                        ForEach(store.events) { event in
                            HStack(alignment: .top, spacing: 14) {
                                Image(systemName: "sparkles")
                                    .font(.title3)
                                    .foregroundStyle(DreamPalette.accent)
                                    .frame(width: 30)
                                    .padding(.top, 2)
                                    .accessibilityHidden(true)
                                VStack(alignment: .leading, spacing: 7) {
                                    Text(event.title).font(.headline)
                                    Text(event.detail).font(.subheadline).foregroundStyle(.secondary)
                                    Text(displayDate(event.createdAt)).font(.caption).foregroundStyle(.tertiary)
                                }
                            }
                            .padding(.vertical, 8)
                            .accessibilityElement(children: .combine)
                        }
                    } header: {
                        Text("最近的观察 · \(store.events.count) 条")
                    } footer: {
                        Text("AI 记录是图像观察结果，不代表空调已执行，也不构成医疗判断。")
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(DreamPalette.background)
        .navigationTitle("夜晚的片刻")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("刷新记录", systemImage: "arrow.clockwise") { Task { await store.loadHistory() } }
                    .disabled(store.busy || (store.profile == nil && !store.isDemo))
            }
        }
        .task { if store.profile != nil || store.isDemo { await store.loadHistory() } }
        .refreshable { if store.profile != nil || store.isDemo { await store.loadHistory() } }
    }

    private func emptyState(title: String, description: String, symbol: String) -> some View {
        Section {
            ContentUnavailableView(title, systemImage: symbol, description: Text(description))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 30)
                .listRowBackground(Color.clear)
        }
    }

    private func captureSource(_ source: String) -> String {
        switch source.lowercased() {
        case "定时抓拍", "手动抓拍", "ai 确认帧", "演示手动抓拍", "演示定时抓拍": return source
        case "scheduled", "schedule", "automatic": return "定时抓拍"
        case "manual", "mobile": return "手动抓拍"
        case "automatic_ai_confirmation": return "AI 确认帧"
        case "demo": return "演示图片"
        default: return "服务器抓拍"
        }
    }

    private func displayDate(_ text: String) -> String {
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var date = parser.date(from: text)
        if date == nil {
            parser.formatOptions = [.withInternetDateTime]
            date = parser.date(from: text)
        }
        return date?.formatted(date: .abbreviated, time: .standard) ?? text
    }
}
