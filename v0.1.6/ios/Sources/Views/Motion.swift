import SwiftUI

/// Small, deterministic interaction values shared by the style and its tests.
/// Nothing here changes an action, a request, a selection or a device setting.
enum DreamMotionPolicy {
    static let pressAnimationDuration: Double = 0.16
    static let releaseAnimationDuration: Double = 0.26

    static func pressScale(isPressed: Bool, isEnabled: Bool, reduceMotion: Bool) -> Double {
        isPressed && isEnabled && !reduceMotion ? 0.98 : 1
    }

    static func pressOpacity(isPressed: Bool, isEnabled: Bool) -> Double {
        guard isEnabled else { return 0.45 }
        return isPressed ? 0.92 : 1
    }

    static func highlightOpacity(isPressed: Bool, isEnabled: Bool) -> Double {
        isPressed && isEnabled ? 0.10 : 0
    }

    static func duration(isPressed: Bool, isEnabled: Bool, reduceMotion: Bool) -> Double {
        guard isEnabled && !reduceMotion else { return 0 }
        return isPressed ? pressAnimationDuration : releaseAnimationDuration
    }
}

/// Uses SwiftUI's native press, cancellation, keyboard and accessibility actions.
/// In particular, a scroll never becomes a second, synthesized button action.
struct DreamButtonStyle: ButtonStyle {
    enum Kind {
        case primary
        case secondary
        case plain
        case row
    }

    let kind: Kind

    init(_ kind: Kind = .secondary) {
        self.kind = kind
    }

    func makeBody(configuration: Configuration) -> some View {
        DreamButtonBody(configuration: configuration, kind: kind)
    }
}

private struct DreamButtonBody: View {
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityDifferentiateWithoutColor) private var differentiateWithoutColor
    @Environment(\.colorSchemeContrast) private var contrast

    let configuration: ButtonStyleConfiguration
    let kind: DreamButtonStyle.Kind

    private var pressed: Bool { configuration.isPressed && isEnabled }
    private var filled: Bool { kind == .primary || kind == .secondary }
    private var accent: Color { configuration.role == .destructive ? .red : DreamPalette.accent }
    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: filled ? 16 : 12, style: .continuous) }

    private var foreground: Color {
        switch kind {
        case .primary: return .white
        case .secondary, .plain: return accent
        case .row: return configuration.role == .destructive ? .red : .primary
        }
    }

    private var background: Color {
        switch kind {
        case .primary: return configuration.role == .destructive ? .red : DreamPalette.accentFill
        case .secondary: return accent.opacity(contrast == .increased ? 0.18 : 0.10)
        case .plain, .row: return .clear
        }
    }

    private var outlineOpacity: Double {
        if differentiateWithoutColor && pressed { return 0.8 }
        if contrast == .increased { return filled ? 0.65 : 0.45 }
        return kind == .secondary ? 0.12 : 0
    }

    private var feedbackAnimation: Animation? {
        let duration = DreamMotionPolicy.duration(isPressed: pressed, isEnabled: isEnabled, reduceMotion: reduceMotion)
        guard duration > 0 else { return nil }
        return pressed ? .easeOut(duration: duration) : .spring(response: duration, dampingFraction: 0.84)
    }

    var body: some View {
        configuration.label
            .fontWeight(filled ? .semibold : nil)
            .foregroundStyle(foreground)
            .padding(.horizontal, filled ? 16 : (kind == .plain ? 8 : 0))
            .padding(.vertical, filled ? 10 : 0)
            .frame(maxWidth: kind == .plain ? nil : .infinity, minHeight: filled ? 48 : 44, alignment: kind == .row ? .leading : .center)
            .background(background, in: shape)
            .overlay {
                shape
                    .fill(kind == .primary ? Color.black : Color.primary)
                    .opacity(DreamMotionPolicy.highlightOpacity(isPressed: pressed, isEnabled: isEnabled))
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            .overlay {
                shape
                    .strokeBorder(foreground.opacity(outlineOpacity), lineWidth: differentiateWithoutColor && pressed ? 2 : 1)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            .contentShape(shape)
            .contentShape(.hoverEffect, shape)
            .hoverEffect(.highlight, isEnabled: isEnabled && !reduceMotion)
            // Scope animation to these effects only. An image load, text refresh or
            // privacy change elsewhere in the label must never inherit this spring.
            .animation(feedbackAnimation) { effect in
                effect
                    .scaleEffect(DreamMotionPolicy.pressScale(isPressed: pressed, isEnabled: isEnabled, reduceMotion: reduceMotion))
                    .opacity(DreamMotionPolicy.pressOpacity(isPressed: pressed, isEnabled: isEnabled))
            }
            .transaction { transaction in
                if reduceMotion || !isEnabled {
                    transaction.animation = nil
                    transaction.disablesAnimations = true
                }
            }
    }
}

private struct DreamMotionAccessibility: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.transaction { transaction in
            // No global animation is added when Reduce Motion is off.
            if reduceMotion {
                transaction.animation = nil
                transaction.disablesAnimations = true
            }
        }
    }
}

extension View {
    func dreamMotionAccessibility() -> some View {
        modifier(DreamMotionAccessibility())
    }
}
