import SwiftUI

enum DreamPalette {
    static let accent = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.76, green: 0.70, blue: 0.98, alpha: 1)
            : UIColor(red: 0.41, green: 0.33, blue: 0.70, alpha: 1)
    })
    static let accentFill = Color(red: 0.41, green: 0.33, blue: 0.70)
    static let teal = Color.teal
    static let background = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.075, green: 0.080, blue: 0.13, alpha: 1)
            : UIColor(red: 0.965, green: 0.958, blue: 0.985, alpha: 1)
    })
    static let card = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.13, green: 0.14, blue: 0.21, alpha: 1)
            : .white
    })
}

struct DreamCard<Content: View>: View {
    let title: String?
    let symbol: String?
    private let content: Content

    init(_ title: String? = nil, symbol: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.symbol = symbol
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if let title {
                HStack(spacing: 9) {
                    if let symbol {
                        Image(systemName: symbol)
                            .foregroundStyle(DreamPalette.accent)
                            .accessibilityHidden(true)
                    }
                    Text(title).font(.headline)
                }
            }
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background(DreamPalette.card, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(DreamPalette.accent.opacity(0.07))
                .allowsHitTesting(false)
        }
    }
}

struct StatusPill: View {
    let text: String
    let color: Color
    var symbol: String = "circle.fill"

    var body: some View {
        Label(text, systemImage: symbol)
            .font(.caption.weight(.semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 11)
            .padding(.vertical, 7)
            .background(color.opacity(0.10), in: Capsule())
            .fixedSize(horizontal: false, vertical: true)
    }
}

struct DetailLine: View {
    let title: String
    let value: String

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .firstTextBaseline, spacing: 24) {
                Text(title).foregroundStyle(.secondary)
                Spacer(minLength: 12)
                Text(value).multilineTextAlignment(.trailing)
            }
            VStack(alignment: .leading, spacing: 5) {
                Text(title).foregroundStyle(.secondary)
                Text(value)
            }
        }
        .font(.subheadline)
        .accessibilityElement(children: .combine)
    }
}

struct Advisory: View {
    let text: String
    var symbol: String = "info.circle"
    var color: Color = .secondary

    var body: some View {
        Label {
            Text(text).fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: symbol).accessibilityHidden(true)
        }
        .font(.footnote)
        .foregroundStyle(color)
        .labelStyle(.titleAndIcon)
    }
}

struct PageIntro: View {
    let eyebrow: String
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(eyebrow)
                .font(.caption.weight(.semibold))
                .tracking(2)
                .foregroundStyle(DreamPalette.accent)
            Text(title).font(.system(.title, design: .rounded).weight(.bold))
            Text(subtitle)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 5)
        .accessibilityElement(children: .combine)
    }
}

struct PrimaryAction: View {
    let title: String
    let symbol: String
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 32)
        }
        .buttonStyle(DreamButtonStyle(.primary))
    }
}

struct NumericSetting: View {
    let title: String
    let unit: String
    @Binding var value: Int
    let range: ClosedRange<Int>

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(title)
                Spacer(minLength: 8)
                TextField(title, value: $value, format: .number)
                    .keyboardType(.numberPad)
                    .multilineTextAlignment(.trailing)
                    .font(.subheadline.monospacedDigit())
                    .frame(minWidth: 60, maxWidth: 100, minHeight: 44)
                    .accessibilityLabel(title)
                Text(unit).font(.subheadline).foregroundStyle(.secondary)
            }
            Stepper(value: $value, in: range) {
                Text("允许范围 \(range.lowerBound)–\(range.upperBound) \(unit)")
                    .font(.caption).foregroundStyle(.secondary)
            }
            .accessibilityLabel(title)
            .accessibilityValue("\(value) \(unit)")
        }
        .padding(.vertical, 3)
    }
}

struct PendingFeature: View {
    let title: String
    let symbol: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(.secondary)
                .frame(width: 30)
                .padding(.top, 3)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 5) {
                Text(title).font(.headline)
                Text(detail).font(.footnote).foregroundStyle(.secondary)
                Text("暂未开放")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Image(systemName: "lock.fill").foregroundStyle(.tertiary).accessibilityHidden(true)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

// An alert belongs to the currently presented screen, not its covered presenter.
// This keeps pairing/save errors visible while a sheet is open, without creating
// two simultaneous alert presenters for the same notice.
private struct DreamNoticeModifier: ViewModifier {
    @EnvironmentObject private var store: AppStore
    let isActive: Bool

    func body(content: Content) -> some View {
        content.alert("梦盾提示", isPresented: Binding(
            get: { isActive && store.notice != nil },
            set: { if !$0 && isActive { store.clearNotice() } }
        )) {
            Button("知道了", role: .cancel) { store.clearNotice() }
        } message: {
            Text(store.notice ?? "")
        }
    }
}

extension View {
    func dreamNoticeAlert(isActive: Bool = true) -> some View {
        modifier(DreamNoticeModifier(isActive: isActive))
    }
}
