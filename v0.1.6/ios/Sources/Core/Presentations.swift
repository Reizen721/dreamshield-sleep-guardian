import Foundation

enum AppTab: String, CaseIterable, Identifiable {
    case home, history, control, settings
    var id: String { rawValue }
}

struct CaptureItem: Identifiable, Equatable {
    let id: String
    let imagePath: String
    let createdAt: String
    let source: String
}

struct AIEventItem: Identifiable, Equatable {
    let id: String
    let createdAt: String
    let title: String
    let detail: String
}

struct SensorPresentation: Equatable {
    let temperature: String
    let humidity: String
    let message: String
    let isFresh: Bool

    static func unavailable(_ message: String) -> Self {
        .init(temperature: "—", humidity: "—", message: message, isFresh: false)
    }

    static func make(_ environment: JSONValue, elapsed: TimeInterval, online: Bool) -> Self {
        guard online else { return .unavailable("连接中断，旧读数已隐藏") }
        guard environment["sensor_model"].string == "sht31",
              environment["input_origin"].string == "sensor" else {
            return .unavailable("等待真实 SHT31 数据，不从画面估算温湿度")
        }
        guard environment["valid"].bool == true, environment["status"].string == "fresh",
              let age = environment["sample_age_ms"].number,
              age.isFinite, age >= 0, elapsed.isFinite, elapsed >= 0,
              age + elapsed * 1000 <= 10_000 else {
            return .unavailable("读数不可用或已过期，等待设备更新")
        }
        guard let temperature = environment["ambient_temperature_c"].number,
              let humidity = environment["relative_humidity_percent"].number,
              temperature.isFinite, humidity.isFinite,
              (-40...125).contains(temperature), (0...100).contains(humidity) else {
            return .unavailable("传感器读数缺失或越界")
        }
        return .init(temperature: String(format: "%.2f", temperature),
                     humidity: String(format: "%.2f", humidity),
                     message: "真实传感器 · 约 \(Int((age + elapsed * 1000) / 1000)) 秒前采样", isFresh: true)
    }
}

struct ControllerPresentation: Equatable {
    let title: String
    let detail: String
    static func make(_ status: JSONObject, online: Bool) -> Self {
        guard online else { return unknown }
        if status.value("ir_emission", "enabled").bool == true || status.value("ac_control", "automatic", "enabled").bool == true {
            return .init(title: "电脑自动联动已启用", detail: "电脑可能独立发射红外；手机未开放空调控制，无法确认空调实际执行。")
        }
        if status.value("ac_control", "enabled").bool == true {
            return .init(title: "电脑遥控会话已启用", detail: "电脑侧可手动发射红外，手机不发送空调指令；实际执行仍需确认。")
        }
        if status.value("ir_emission", "enabled").bool == false,
           status.value("ac_control", "enabled").bool == false,
           status.value("ac_control", "automatic", "enabled").bool == false {
            return .init(title: "电脑联动未启用", detail: "最近读取的电脑状态显示联动关闭；这不代表空调自身已关闭。")
        }
        return unknown
    }
    static let unknown = Self(title: "后台控制状态未知", detail: "请在电脑确认联动状态。退出手机不停止电脑任务，发射完成也不等于空调已执行。")
}

struct AIPresentation: Equatable {
    let enabled: Bool
    let state: String
    let event: String
    let confirmation: String
    let calls: String
    static func make(_ status: JSONObject, online: Bool) -> Self {
        guard online else { return .init(enabled: false, state: "连接后查看当前 AI 状态", event: "—", confirmation: "—", calls: "调用状态未知") }
        let ai = status.value("automatic_ai")
        let assessment = ai["last_record"]["result"]["assessment"]
        let enabled = ai["enabled"].bool == true
        let unexpected = ai["execute"].bool == true || assessment["control_decision"]["execution_allowed"].bool == true || assessment["control_decision"]["would_execute"].bool == true
        let state = unexpected ? "状态异常，请停止 AI 并检查电脑" : (enabled ? Labels.text(ai["state"].string, fallback: "自动观察中") : "自动 AI 已关闭")
        func integer(_ key: String, fallback: Int) -> Int {
            guard let n = ai[key].number, n.isFinite, (0...100000).contains(n) else { return fallback }
            return Int(n)
        }
        return .init(enabled: enabled, state: state,
                     event: Labels.text(assessment["validated_visual_decision"].string ?? assessment["visual_event"].string, fallback: "尚无判决"),
                     confirmation: Labels.text(assessment["blanket_event_state"].string ?? assessment["confirmation_state"].string),
                     calls: "本小时 \(integer("calls_this_hour", fallback: 0))/\(integer("max_calls_per_hour", fallback: 20)) · 本次 \(integer("calls_this_session", fallback: 0))/\(integer("max_calls_per_session", fallback: 50)) 次")
    }
}

enum Labels {
    static func text(_ code: String?, fallback: String = "待确认") -> String {
        guard let code else { return fallback }
        return [
            "disabled": "已关闭", "waiting_baseline": "等待首张定时图建立基线", "waiting_frame": "等待下一张定时图",
            "waiting_capture": "等待定时抓拍", "idle": "等待定时图片", "analyzing": "前后图分析中", "comparing": "前后图分析中",
            "waiting_confirmation": "等待确认帧", "confirming": "确认帧分析中", "completed": "观察已完成", "cooldown": "等待下一轮对比",
            "backoff": "调用失败，稍后重试", "paused_budget": "调用预算已用完", "budget_exhausted": "调用预算已用完",
            "error": "观察未完成", "failed": "观察未完成", "cancelled": "已取消", "stopped": "已停止",
            "confirmed": "未覆盖状态持续确认", "recovered": "已恢复覆盖", "no_candidate": "未见显著覆盖减少",
            "confirmation_pending": "等待有效确认帧", "not_required": "无需追加确认", "inconclusive": "证据不足，需复核",
            "suspected_blanket_kicking": "疑似踢被 · 覆盖显著减少", "significant_coverage_reduction_observed": "覆盖显著减少",
            "significant_coverage_reduction_candidate": "覆盖显著减少候选", "coverage_reduction_below_candidate_threshold": "覆盖减少未达候选门槛",
            "no_significant_coverage_change": "未见显著覆盖变化", "indeterminate": "证据不足，需复核", "no_person": "未见人物",
            "candidate": "覆盖显著减少候选", "pair_completed": "前后图观察完成", "confirmation_completed": "确认帧观察完成",
            "baseline_saved": "基线已建立", "scheduled": "定时抓拍", "manual": "手动抓拍", "automatic_ai_confirmation": "AI 确认帧"
        ][code] ?? fallback
    }

    static func date(_ text: String) -> Date? {
        let parser = ISO8601DateFormatter()
        parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return parser.date(from: text) ?? ISO8601DateFormatter().date(from: text)
    }
}

/// A UI-only gate, never an authentication or networking permission bypass.
struct DeveloperGate {
    private(set) var unlocked = false
    private var count = 0
    private var started: Date?
    mutating func tap(at now: Date = Date()) -> Bool {
        if started == nil || now.timeIntervalSince(started!) > 8 || now < started! {
            started = now; count = 0
        }
        count += 1
        if count >= 5 { unlocked = true; resetTaps() }
        return unlocked
    }
    mutating func resetTaps() { count = 0; started = nil }
    mutating func lock() { unlocked = false; resetTaps() }
}
