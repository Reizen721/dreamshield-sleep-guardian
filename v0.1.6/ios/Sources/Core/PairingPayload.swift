import Foundation

struct PairingPayload: Codable, Equatable, Sendable {
    let version: Int
    let baseURL: String
    let certificateSHA256: String
    let pairingCode: String

    enum CodingKeys: String, CodingKey {
        case version
        case baseURL = "base_url"
        case certificateSHA256 = "certificate_sha256"
        case pairingCode = "pairing_code"
    }

    static func parse(_ text: String) throws -> PairingPayload {
        guard text.utf8.count <= 4096 else { throw GatewayError.invalidPairing }
        do {
            // JSONDecoder alone silently accepts duplicate keys. This tiny root-object parser
            // accepts exactly the four primitive fields the desktop gateway produces.
            var parser = PairingJSONParser(text)
            let fields = try parser.parse()
            guard fields["version"] == "1", let address = fields["base_url"],
                  let fingerprint = fields["certificate_sha256"], let code = fields["pairing_code"],
                  NetworkPolicy.matches(code, "[0-9A-F]{12}") else { throw GatewayError.invalidPairing }
            return PairingPayload(version: 1, baseURL: try NetworkPolicy.validateBaseURL(address),
                                  certificateSHA256: try NetworkPolicy.validateFingerprint(fingerprint), pairingCode: code)
        } catch { throw GatewayError.invalidPairing }
    }
}

private struct PairingJSONParser {
    let bytes: [UInt8]
    var index = 0
    init(_ value: String) { bytes = Array(value.utf8) }
    mutating func whitespace() {
        while index < bytes.count && [9, 10, 13, 32].contains(bytes[index]) { index += 1 }
    }
    mutating func consume(_ byte: UInt8) throws {
        whitespace()
        guard index < bytes.count, bytes[index] == byte else { throw GatewayError.invalidPairing }
        index += 1
    }
    mutating func string() throws -> String {
        whitespace()
        let start = index
        try consume(34)
        while index < bytes.count {
            let byte = bytes[index]
            if byte == 34 {
                index += 1
                return try JSONDecoder().decode(String.self, from: Data(bytes[start..<index]))
            }
            if byte == 92 {
                index += 1
                guard index < bytes.count else { throw GatewayError.invalidPairing }
            } else if byte < 32 { throw GatewayError.invalidPairing }
            index += 1
        }
        throw GatewayError.invalidPairing
    }
    mutating func parse() throws -> [String: String] {
        try consume(123)
        var fields: [String: String] = [:]
        let allowed: Set<String> = ["version", "base_url", "certificate_sha256", "pairing_code"]
        for item in 0..<4 {
            if item > 0 { try consume(44) }
            let key = try string()
            guard allowed.contains(key), fields[key] == nil else { throw GatewayError.invalidPairing }
            try consume(58)
            if key == "version" {
                try consume(49) // Exactly JSON integer 1, not true, "1", 1.0 or 1e0.
                fields[key] = "1"
            } else { fields[key] = try string() }
        }
        try consume(125)
        whitespace()
        guard index == bytes.count, Set(fields.keys) == allowed else { throw GatewayError.invalidPairing }
        return fields
    }
}
