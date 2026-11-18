import XCTest
@testable import DreamShield

// Pure scalar policy tests. These do not certify native gesture recognition,
// animation frame rate, scrolling cancellation, VoiceOver, or physical devices.
final class MotionPolicyTests: XCTestCase {
    func testScaleOnlyMovesForAnEnabledPressWithoutReduceMotion() {
        for pressed in [false, true] {
            for enabled in [false, true] {
                for reduceMotion in [false, true] {
                    let expected = pressed && enabled && !reduceMotion ? 0.98 : 1.0
                    XCTAssertEqual(DreamMotionPolicy.pressScale(
                        isPressed: pressed, isEnabled: enabled, reduceMotion: reduceMotion
                    ), expected, accuracy: 0.000_001)
                }
            }
        }
    }

    func testDisabledControlsDoNotRespondToPressWithOpacityChanges() {
        XCTAssertEqual(DreamMotionPolicy.pressOpacity(isPressed: false, isEnabled: false), 0.45)
        XCTAssertEqual(DreamMotionPolicy.pressOpacity(isPressed: true, isEnabled: false), 0.45)
    }

    func testEnabledControlsHaveSubtleStaticPressFeedback() {
        XCTAssertEqual(DreamMotionPolicy.pressOpacity(isPressed: false, isEnabled: true), 1)
        XCTAssertEqual(DreamMotionPolicy.pressOpacity(isPressed: true, isEnabled: true), 0.92)
    }

    func testHighlightOnlyAppearsDuringEnabledPress() {
        for pressed in [false, true] {
            for enabled in [false, true] {
                XCTAssertEqual(DreamMotionPolicy.highlightOpacity(
                    isPressed: pressed, isEnabled: enabled
                ), pressed && enabled ? 0.10 : 0)
            }
        }
    }

    func testReduceMotionUsesNoTimedAnimation() {
        for pressed in [false, true] {
            XCTAssertEqual(DreamMotionPolicy.duration(
                isPressed: pressed, isEnabled: true, reduceMotion: true
            ), 0)
        }
    }

    func testDisabledControlsUseNoTimedAnimation() {
        for pressed in [false, true] {
            for reduceMotion in [false, true] {
                XCTAssertEqual(DreamMotionPolicy.duration(
                    isPressed: pressed, isEnabled: false, reduceMotion: reduceMotion
                ), 0)
            }
        }
    }

    func testEnabledPressUsesShorterDurationThanRelease() {
        let press = DreamMotionPolicy.duration(isPressed: true, isEnabled: true, reduceMotion: false)
        let release = DreamMotionPolicy.duration(isPressed: false, isEnabled: true, reduceMotion: false)
        XCTAssertEqual(press, 0.16, accuracy: 0.000_001)
        XCTAssertEqual(release, 0.26, accuracy: 0.000_001)
        XCTAssertLessThan(press, release)
        XCTAssertGreaterThan(press, 0)
        XCTAssertLessThan(release, 0.4)
    }

    func testReleaseRestoresNeutralVisuals() {
        XCTAssertEqual(DreamMotionPolicy.pressScale(
            isPressed: false, isEnabled: true, reduceMotion: false
        ), 1)
        XCTAssertEqual(DreamMotionPolicy.pressOpacity(isPressed: false, isEnabled: true), 1)
        XCTAssertEqual(DreamMotionPolicy.highlightOpacity(isPressed: false, isEnabled: true), 0)
    }
}
