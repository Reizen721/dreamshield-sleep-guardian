import Foundation

/// Deliberately small transport surface: this app can reach only the paired LAN gateway.
enum GatewayError: Error, LocalizedError, Equatable {
    case invalidAddress, invalidFingerprint, invalidPath, invalidCommand, invalidPairing
    case authenticationRequired, forbidden, invalidResponse, responseTooLarge
    case certificateRejected, redirectRejected, unavailable, cancelled, storageUnavailable

    var errorDescription: String? {
        switch self {
        case .invalidAddress: return "电脑地址应为 https://家庭局域网 IPv4:端口，端口范围为 8743–65535。"
        case .invalidFingerprint: return "证书指纹格式不正确，请重新复制电脑端的配对信息。"
        case .invalidPath: return "此操作未开放给手机客户端。"
        case .invalidCommand: return "设置内容不在允许范围内。"
        case .invalidPairing: return "配对信息不完整或格式不正确，请重新复制完整配对信息。"
        case .authenticationRequired: return "配对已失效或配对码已过期，请在电脑端重新生成配对信息。"
        case .forbidden: return "电脑端拒绝了此操作，请检查配对状态与局域网连接。"
        case .invalidResponse: return "电脑返回了无法识别的数据，请检查服务端版本。"
        case .responseTooLarge: return "响应超过安全大小限制，已停止接收。"
        case .certificateRejected: return "电脑证书验证失败，请核对系统时间并重新配对；不要忽略证书错误。"
        case .redirectRejected: return "已阻止服务器重定向，请核对电脑端配对地址。"
        case .unavailable: return "暂时无法连接电脑，请确认双方位于同一家庭网络、电脑服务已开启，并允许本地网络访问。"
        case .cancelled: return "操作已取消。"
        case .storageUnavailable: return "无法读取或保存安全配对信息，请解锁设备后重试。"
        }
    }
}

enum NetworkPolicy {
    static let maximumResponseBytes = 10 * 1024 * 1024
    static let maximumCommandBytes = 64 * 1024
    private static let getRoutes: Set<String> = [
        "/api/app/status", "/api/device/status", "/api/device/snapshot", "/api/captures",
        "/api/environment/history", "/api/automatic-ai/history", "/api/mobile/health"
    ]
    private static let postRoutes: Set<String> = ["/api/config", "/api/capture", "/api/automatic-ai"]

    static func matches(_ value: String, _ pattern: String) -> Bool {
        guard let expression = try? NSRegularExpression(pattern: pattern) else { return false }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return expression.firstMatch(in: value, range: range)?.range == range
    }

    static func isPrivateIPv4(_ value: String) -> Bool {
        guard matches(value, "[0-9]{1,3}(\\.[0-9]{1,3}){3}") else { return false }
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        let octets = parts.compactMap { Int($0) }
        guard octets.count == 4, octets.allSatisfy({ $0 <= 255 }),
              !parts.contains(where: { $0.count > 1 && $0.first == "0" }) else { return false }
        return octets[0] == 10 || (octets[0] == 172 && (16...31).contains(octets[1]))
            || (octets[0] == 192 && octets[1] == 168)
    }

    static func validateBaseURL(_ input: String) throws -> String {
        // Reject noncanonical syntax before Foundation can normalize it (userinfo, %, paths,
        // fragments, shorthand/octal IPs and invisible characters are all prohibited).
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.utf8.count <= 120,
              matches(value, "https://[0-9]{1,3}(\\.[0-9]{1,3}){3}:[0-9]{4,5}"),
              let components = URLComponents(string: value), components.scheme == "https",
              let host = components.host, isPrivateIPv4(host), let port = components.port,
              (8743...65535).contains(port), components.path.isEmpty,
              components.user == nil, components.password == nil, components.query == nil,
              components.fragment == nil,
              value == "https://\(host):\(port)" else { throw GatewayError.invalidAddress }
        return value
    }

    static func validateFingerprint(_ input: String) throws -> String {
        guard matches(input, "[0-9a-fA-F]{64}") else { throw GatewayError.invalidFingerprint }
        return input.lowercased()
    }

    static func validateToken(_ input: String) throws -> String {
        guard matches(input, "[A-Za-z0-9_-]{43}") else { throw GatewayError.authenticationRequired }
        return input
    }

    static func validateDeviceName(_ input: String) throws -> String {
        let name = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, input.count <= 80,
              !input.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
        else { throw GatewayError.invalidPairing }
        return name
    }

    static func validatePath(_ method: String, _ input: String, pairing: Bool = false) throws -> String {
        guard input.utf8.count <= 2048, input.hasPrefix("/"), !input.hasPrefix("//"),
              input.unicodeScalars.allSatisfy({ (33...126).contains($0.value) }),
              !input.contains("%"), !input.contains("\\"), !input.contains("#"), !input.contains("//")
        else { throw GatewayError.invalidPath }
        let parts = input.split(separator: "?", omittingEmptySubsequences: false)
        guard parts.count <= 2 else { throw GatewayError.invalidPath }
        let path = String(parts[0])
        guard !path.split(separator: "/", omittingEmptySubsequences: false).contains(where: { $0 == "." || $0 == ".." })
        else { throw GatewayError.invalidPath }
        if method == "POST" {
            guard parts.count == 1, postRoutes.contains(path) || (pairing && path == "/api/mobile/pair")
            else { throw GatewayError.invalidPath }
            return path
        }
        guard method == "GET" else { throw GatewayError.invalidPath }
        if getRoutes.contains(path) {
            if parts.count == 2 {
                let query = String(parts[1])
                guard !query.isEmpty else { throw GatewayError.invalidPath }
                let allowed: Set<String>
                switch path {
                case "/api/captures", "/api/environment/history": allowed = ["limit"]
                case "/api/device/snapshot": allowed = ["t", "_"]
                default: allowed = []
                }
                var seen = Set<String>()
                for part in query.split(separator: "&", omittingEmptySubsequences: false) {
                    let pair = part.split(separator: "=", omittingEmptySubsequences: false)
                    guard pair.count == 2, allowed.contains(String(pair[0])), seen.insert(String(pair[0])).inserted,
                          matches(String(pair[1]), "[0-9]{1,16}") else { throw GatewayError.invalidPath }
                }
            }
            return input
        }
        guard parts.count == 1,
              matches(path, "/captures/(?:[0-9]{4}/[0-9]{2}/[0-9]{2}/)?[A-Za-z0-9_-]+\\.(jpg|json)") ||
              matches(path, "/automatic-ai-results/(?:[0-9]{4}/[0-9]{2}/[0-9]{2}/)?[A-Za-z0-9_-]+\\.json")
        else { throw GatewayError.invalidPath }
        return path
    }

    static func validateImagePath(_ input: String) throws -> String {
        let path = try validatePath("GET", input)
        let route = String(path.split(separator: "?", omittingEmptySubsequences: false)[0])
        guard route == "/api/device/snapshot" || route.hasPrefix("/captures/") && route.hasSuffix(".jpg")
        else { throw GatewayError.invalidPath }
        return path
    }

    static func validateCommand(_ path: String, body: JSONObject) throws {
        func number(_ key: String, _ range: ClosedRange<Double>) -> Bool {
            guard let value = body[key] else { return true }
            guard case .number(let number) = value else { return false }
            return number.isFinite && number.rounded() == number && range.contains(number)
        }
        func boolean(_ key: String) -> Bool {
            guard let value = body[key] else { return true }
            if case .bool = value { return true }
            return false
        }
        let keys = Set(body.keys)
        switch path {
        case "/api/capture":
            guard body.isEmpty else { throw GatewayError.invalidCommand }
        case "/api/config":
            guard !body.isEmpty, keys.isSubset(of: ["capture_interval_seconds", "schedule_enabled", "environment_recording_enabled", "environment_interval_seconds"]),
                  boolean("schedule_enabled"), boolean("environment_recording_enabled"),
                  number("capture_interval_seconds", 5...86400), number("environment_interval_seconds", 5...3600)
            else { throw GatewayError.invalidCommand }
        case "/api/automatic-ai":
            guard keys.isSubset(of: ["enabled", "cloud_upload_consent", "min_interval_seconds", "max_calls_per_hour", "max_calls_per_session"]),
                  case .some(.bool(let enabled)) = body["enabled"], boolean("cloud_upload_consent"),
                  !enabled || body["cloud_upload_consent"] == .bool(true),
                  number("min_interval_seconds", 60...3600), number("max_calls_per_hour", 2...120), number("max_calls_per_session", 2...500)
            else { throw GatewayError.invalidCommand }
        default: throw GatewayError.invalidPath
        }
    }

    static func parseTimestamp(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        if let date = formatter.date(from: value) { return date }
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: value)
    }
}
