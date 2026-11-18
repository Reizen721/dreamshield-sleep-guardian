"""Offline source-contract regressions, NOT Swift compilation or UI tests.

These tests check reviewed source boundaries for the interaction layer. They
cannot prove gesture cancellation, VoiceOver operation, animation appearance,
frame rate, or on-device privacy behavior. Run XCTest/Xcode and the documented
iPhone/iPad acceptance checklist separately.
"""
from pathlib import Path
import re
import unittest

from validate_project import code_for_privacy_scan


ROOT = Path(__file__).resolve().parents[1]


def source(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


def code(relative):
    return code_for_privacy_scan(source(relative))


def block(text, declaration):
    """Extract a brace block from comment/string-stripped source, not an AST."""
    start = text.index("{", text.index(declaration))
    depth = 0
    for position in range(start, len(text)):
        if text[position] == "{":
            depth += 1
        elif text[position] == "}":
            depth -= 1
            if depth == 0:
                return text[start:position + 1]
    raise ValueError(f"Unclosed source block: {declaration}")


class MotionSourceContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.motion = code("Sources/Views/Motion.swift")
        cls.root = code("Sources/Views/RootView.swift")
        cls.components = code("Sources/Views/Components.swift")
        cls.app = code("Sources/DreamShieldApp.swift")

    def test_motion_uses_native_button_style_and_pressed_state(self):
        self.assertRegex(self.motion, r"struct\s+DreamButtonStyle\s*:\s*ButtonStyle\b")
        self.assertIn("configuration.label", self.motion)
        self.assertIn("configuration.isPressed && isEnabled", self.motion)
        self.assertNotIn("PrimitiveButtonStyle", self.motion)

    def test_motion_does_not_synthesize_or_intercept_actions(self):
        for symbol in ("onTapGesture", "onLongPressGesture", "DragGesture", "TapGesture",
                       "simultaneousGesture", "highPriorityGesture", "LongPressGesture",
                       "trigger", "sendActions", "UIImpactFeedbackGenerator",
                       "sensoryFeedback", "onChange"):
            self.assertNotRegex(self.motion, rf"\b{symbol}\b")

    def test_motion_has_no_business_or_network_side_effects(self):
        for symbol in ("Task", "AppStore", "URLSession", "GatewayClient", "Keychain",
                       "NotificationCenter", "Timer", "async", "await"):
            self.assertNotRegex(self.motion, rf"\b{symbol}\b")

    def test_reduce_motion_and_disabled_state_are_system_driven(self):
        self.assertIn("@Environment(\\.isEnabled)", self.motion)
        self.assertIn("@Environment(\\.accessibilityReduceMotion)", self.motion)
        self.assertRegex(self.motion, r"if\s+reduceMotion\s*\|\|\s*!isEnabled\s*\{")
        self.assertIn("transaction.animation = nil", self.motion)
        self.assertIn("transaction.disablesAnimations = true", self.motion)

    def test_press_animation_is_scoped_to_visual_effects(self):
        self.assertEqual(self.motion.count(".animation("), 1)
        effect = block(self.motion, ".animation(feedbackAnimation)")
        self.assertIn("effect in", effect)
        self.assertIn(".scaleEffect(DreamMotionPolicy.pressScale", effect)
        self.assertIn(".opacity(DreamMotionPolicy.pressOpacity", effect)
        self.assertNotIn("configuration.label", effect)
        self.assertNotIn("withAnimation", self.motion)

    def test_accessibility_modifier_only_disables_animations(self):
        modifier = block(self.motion, "struct DreamMotionAccessibility")
        self.assertRegex(modifier, r"if\s+reduceMotion\s*\{")
        self.assertIn("transaction.animation = nil", modifier)
        self.assertIn("transaction.disablesAnimations = true", modifier)
        self.assertNotIn(".animation(", modifier)

    def test_feedback_decoration_cannot_take_over_hits_or_accessibility(self):
        self.assertGreaterEqual(self.motion.count(".allowsHitTesting(false)"), 2)
        self.assertGreaterEqual(self.motion.count(".accessibilityHidden(true)"), 2)
        self.assertIn("accessibilityDifferentiateWithoutColor", self.motion)
        self.assertIn("colorSchemeContrast", self.motion)
        self.assertIn(".contentShape(shape)", self.motion)

    def test_pointer_hover_uses_native_accessibility_aware_effect(self):
        self.assertIn(".hoverEffect(.highlight, isEnabled: isEnabled && !reduceMotion)", self.motion)
        self.assertNotIn(".onHover", self.motion)

    def test_primary_action_and_major_views_adopt_native_style(self):
        self.assertIn(".buttonStyle(DreamButtonStyle(.primary))", self.components)
        for relative in ("HomeView.swift", "HistoryView.swift", "ControlView.swift",
                         "SettingsView.swift", "RootView.swift"):
            with self.subTest(view=relative):
                self.assertIn(".buttonStyle(DreamButtonStyle(", code("Sources/Views/" + relative))

    def test_native_navigation_switches_and_steppers_remain_native(self):
        self.assertIn("TabView(selection: $store.tab)", self.root)
        self.assertIn("NavigationSplitView", self.root)
        self.assertIn("NavigationStack", self.root)
        self.assertIn("Stepper(value: $value, in: range)", self.components)
        self.assertIn("Toggle(isOn: $store.scheduleEnabled)", code("Sources/Views/ControlView.swift"))
        all_views = "\n".join(code_for_privacy_scan(path.read_text(encoding="utf-8"))
                              for path in (ROOT / "Sources/Views").glob("*.swift"))
        self.assertNotIn("ToggleStyle", all_views)
        self.assertNotIn("withAnimation", all_views)

    def test_root_only_animates_sidebar_selection_not_destination(self):
        sidebar = block(self.root, "private func sidebarButton")
        self.assertEqual(self.root.count(".animation("), 1)
        self.assertEqual(sidebar.count(".animation("), 1)
        self.assertIn("reduceMotion ? nil", sidebar)
        self.assertIn("value: store.tab == tab", sidebar)
        self.assertNotIn(".id(store.tab)", self.root)
        self.assertNotIn(".transition(", self.root)

    def test_reduce_motion_modifier_covers_root_and_presented_sheets(self):
        self.assertGreaterEqual(self.root.count(".dreamMotionAccessibility()"), 4)
        self.assertLess(self.root.index(".dreamMotionAccessibility()"), self.root.index(".sheet("))

    def test_privacy_cover_is_immediate_and_does_not_fade(self):
        overlay = block(self.app, ".overlay")
        self.assertIn("if scenePhase != .active", overlay)
        self.assertIn("Color(uiColor: .systemBackground).ignoresSafeArea()", overlay)
        self.assertIn(".allowsHitTesting(true)", overlay)
        self.assertIn("transaction.animation = nil", overlay)
        self.assertIn("transaction.disablesAnimations = true", overlay)
        self.assertNotIn(".transition(", overlay)
        self.assertNotIn(".animation(", overlay)

    def test_live_camera_frames_have_no_crossfade_or_blanket_animation(self):
        camera = block(code("Sources/Views/HomeView.swift"), "struct CameraCard")
        self.assertIn("store.previewImage", camera)
        self.assertNotIn(".animation(", camera)
        self.assertNotIn(".transition(", camera)
        self.assertNotIn("withAnimation", camera)

    def test_version_gate_is_still_one_native_button_action(self):
        settings = code("Sources/Views/SettingsView.swift")
        self.assertEqual(settings.count("store.versionTapped()"), 1)
        self.assertRegex(settings, r"Button\s*\{\s*store\.versionTapped\(\)")
        self.assertNotIn("onTapGesture", settings)
        self.assertIn("if store.developerUnlocked", settings)

    def test_motion_preview_is_local_and_includes_reduced_motion_fixture(self):
        preview = code("Sources/Views/DesignPreviews.swift")
        gallery = block(preview, "struct MotionPreviewGallery")
        self.assertIn("@State private var taps = 0", gallery)
        self.assertIn(".disabled(true)", gallery)
        self.assertNotIn("AppStore", gallery)
        self.assertNotIn("Task", gallery)
        self.assertIn(".environment(\\.accessibilityReduceMotion, true)", preview)

    def test_edited_swift_files_have_balanced_delimiters(self):
        # Delimiter smoke check catches accidental extra braces; it is NOT a
        # Swift grammar/type/concurrency/availability check or a Swift compiler.
        paths = [ROOT / "Sources/DreamShieldApp.swift", ROOT / "Tests/MotionPolicyTests.swift"]
        paths.extend((ROOT / "Sources/Views").glob("*.swift"))
        closing = {"}": "{", ")": "(", "]": "["}
        for path in paths:
            with self.subTest(file=path.name):
                stack = []
                for token in code_for_privacy_scan(path.read_text(encoding="utf-8")):
                    if token in "{([":
                        stack.append(token)
                    elif token in closing:
                        self.assertTrue(stack, f"Extra {token} in {path.name}")
                        self.assertEqual(stack.pop(), closing[token], f"Mismatched delimiter in {path.name}")
                self.assertFalse(stack, f"Unclosed delimiters in {path.name}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
