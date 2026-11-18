import UIKit

/// Explicit, in-memory design preview. No real profile, network, photos, or cloud requests.
@MainActor
enum DemoContent {
    static func status() -> JSONObject {
        ["ok": .bool(true), "vlm_key_configured": .bool(true),
         "config": .object(["schedule_enabled": .bool(true), "capture_interval_seconds": .number(60),
                            "environment_recording_enabled": .bool(true), "environment_interval_seconds": .number(5)]),
         "automatic_ai": .object(["enabled": .bool(false), "state": .string("disabled"),
                                  "min_interval_seconds": .number(60), "max_calls_per_hour": .number(20),
                                  "max_calls_per_session": .number(50), "calls_this_hour": .number(2), "calls_this_session": .number(4),
                                  "last_record": .object(["result": .object(["assessment": .object([
                                    "validated_visual_decision": .string("no_significant_coverage_change"),
                                    "blanket_event_state": .string("not_required")])])])]),
         "ir_emission": .object(["enabled": .bool(false)]),
         "ac_control": .object(["enabled": .bool(false), "automatic": .object(["enabled": .bool(false)])])]
    }
    static func device() -> JSONObject {
        ["environment": .object(["sensor_model": .string("sht31"), "input_origin": .string("sensor"),
                                 "valid": .bool(true), "status": .string("fresh"), "sample_age_ms": .number(1500),
                                 "ambient_temperature_c": .number(26.42), "relative_humidity_percent": .number(58.61)])]
    }
    static func captures() -> [CaptureItem] {
        (0..<6).map { index in
            .init(id: "demo-\(index)", imagePath: "/captures/demo_\(index).jpg",
                  createdAt: ISO8601DateFormatter().string(from: Date().addingTimeInterval(Double(-index * 60))),
                  source: index == 0 ? "演示手动抓拍" : "演示定时抓拍")
        }
    }
    static func events() -> [AIEventItem] {
        [.init(id: "demo-event", createdAt: ISO8601DateFormatter().string(from: Date()),
               title: "演示 · 未见显著覆盖变化", detail: "仅用于界面预览，不是真实模型判决")]
    }
    static func image() -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 960, height: 640))
        return renderer.image { context in
            UIColor(red: 0.13, green: 0.18, blue: 0.28, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: 960, height: 640))
            UIColor(red: 0.28, green: 0.35, blue: 0.47, alpha: 1).setFill()
            UIBezierPath(roundedRect: CGRect(x: 200, y: 55, width: 560, height: 500), cornerRadius: 40).fill()
            UIColor(red: 0.75, green: 0.78, blue: 0.85, alpha: 1).setFill()
            UIBezierPath(roundedRect: CGRect(x: 230, y: 85, width: 500, height: 440), cornerRadius: 28).fill()
            UIColor(red: 0.91, green: 0.92, blue: 0.95, alpha: 1).setFill()
            for x in [265.0, 500.0] { UIBezierPath(roundedRect: CGRect(x: x, y: 115, width: 195, height: 90), cornerRadius: 22).fill() }
            UIColor(red: 0.44, green: 0.58, blue: 0.65, alpha: 1).setFill()
            UIBezierPath(roundedRect: CGRect(x: 230, y: 225, width: 500, height: 300), cornerRadius: 20).fill()
            let text = "DEMO · NO CAMERA CONNECTED"
            let attributes: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 25, weight: .medium), .foregroundColor: UIColor.white]
            let width = (text as NSString).size(withAttributes: attributes).width
            (text as NSString).draw(at: CGPoint(x: (960 - width) / 2, y: 585), withAttributes: attributes)
        }
    }
}
