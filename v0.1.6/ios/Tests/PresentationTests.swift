import XCTest
@testable import DreamShield

final class PresentationTests: XCTestCase {
    private func environment(age: Double = 500, temperature: Double = 26, humidity: Double = 60) -> JSONValue {
        .object(["sensor_model": .string("sht31"), "input_origin": .string("sensor"), "valid": .bool(true),
                 "status": .string("fresh"), "sample_age_ms": .number(age),
                 "ambient_temperature_c": .number(temperature), "relative_humidity_percent": .number(humidity)])
    }
    func testFreshSensor() {
        let result = SensorPresentation.make(environment(), elapsed: 0.5, online: true)
        XCTAssertTrue(result.isFresh); XCTAssertEqual(result.temperature, "26.00"); XCTAssertEqual(result.humidity, "60.00")
    }
    func testStaleSensorAndOfflineAreHidden() {
        for result in [SensorPresentation.make(environment(age: 10_001), elapsed: 0, online: true),
                       SensorPresentation.make(environment(), elapsed: 10, online: true),
                       SensorPresentation.make(environment(), elapsed: -1, online: true),
                       SensorPresentation.make(environment(), elapsed: 0, online: false)] {
            XCTAssertFalse(result.isFresh); XCTAssertEqual(result.temperature, "—"); XCTAssertEqual(result.humidity, "—")
        }
    }
    func testMissingAndOutOfRangeAreNotZero() {
        for sample in [JSONValue.null, .object([:]), environment(temperature: 126), environment(humidity: 101), environment(age: -1)] {
            XCTAssertFalse(SensorPresentation.make(sample, elapsed: 0, online: true).isFresh)
        }
    }
    func testBoolIsNotNumeric() throws {
        let value = try JSONDecoder().decode(JSONObject.self, from: Data(#"{"temperature":true,"count":2,"missing":null}"#.utf8))
        XCTAssertNil(value["temperature"]?.number); XCTAssertEqual(value["temperature"]?.bool, true)
        XCTAssertEqual(value["count"]?.number, 2); XCTAssertEqual(value.value("unknown"), .null)
        XCTAssertEqual(try JSONDecoder().decode(JSONObject.self, from: JSONEncoder().encode(value)), value)
    }
    func testControllerIgnoresAIExecuteFalse() {
        let status: JSONObject = ["automatic_ai": .object(["execute": .bool(false)])]
        XCTAssertEqual(ControllerPresentation.make(status, online: true), .unknown)
    }
    func testControllerReadsActualTopLevelState() {
        let on: JSONObject = ["ir_emission": .object(["enabled": .bool(true)])]
        XCTAssertEqual(ControllerPresentation.make(on, online: true).title, "电脑自动联动已启用")
        XCTAssertEqual(ControllerPresentation.make(on, online: false), .unknown)
        let off: JSONObject = ["ir_emission": .object(["enabled": .bool(false)]), "ac_control": .object(["enabled": .bool(false), "automatic": .object(["enabled": .bool(false)])])]
        XCTAssertEqual(ControllerPresentation.make(off, online: true).title, "电脑联动未启用")
    }
    func testGateNeedsFiveTapsInTotalWindow() {
        var gate = DeveloperGate(); let start = Date(timeIntervalSince1970: 100)
        for i in 0..<4 { XCTAssertFalse(gate.tap(at: start.addingTimeInterval(Double(i)))) }
        XCTAssertTrue(gate.tap(at: start.addingTimeInterval(7.9)))
        gate.lock(); XCTAssertFalse(gate.unlocked)
        for i in 0..<4 { XCTAssertFalse(gate.tap(at: start.addingTimeInterval(Double(i)))) }
        XCTAssertFalse(gate.tap(at: start.addingTimeInterval(8.1)))
    }
    func testGateResetAndClockRollback() {
        var gate = DeveloperGate(); let now = Date()
        for _ in 0..<4 { _ = gate.tap(at: now) }
        gate.resetTaps(); XCTAssertFalse(gate.tap(at: now))
        for _ in 0..<3 { _ = gate.tap(at: now) }
        XCTAssertFalse(gate.tap(at: now.addingTimeInterval(-1)))
    }
    func testOfflineAIIsUnknown() {
        let result = AIPresentation.make(["automatic_ai": .object(["enabled": .bool(true)])], online: false)
        XCTAssertFalse(result.enabled); XCTAssertEqual(result.calls, "调用状态未知")
    }
}
