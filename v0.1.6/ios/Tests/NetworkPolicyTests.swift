import XCTest
@testable import DreamShield

final class NetworkPolicyTests: XCTestCase {
    private let pin = String(repeating: "a", count: 64)
    private let token = String(repeating: "A", count: 43)

    func testApprovedPrivateAddresses() throws {
        for host in ["10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.20"] {
            XCTAssertTrue(NetworkPolicy.isPrivateIPv4(host))
            XCTAssertEqual(try NetworkPolicy.validateBaseURL("https://\(host):8743"), "https://\(host):8743")
        }
        XCTAssertEqual(try NetworkPolicy.validateBaseURL("  https://192.168.1.20:65535\n"), "https://192.168.1.20:65535")
    }

    func testNonPrivateAndAmbiguousHostsRejected() {
        for host in ["127.0.0.1", "169.254.1.1", "8.8.8.8", "172.15.1.1", "172.32.1.1", "192.169.1.1",
                     "192.168.001.1", "192.168.1.256", "192.168.1", "3232235777", "0xc0a80101",
                     "localhost", "example.com", "::1", "192.168.1.1.example.com", "192.168.1.1\n"] {
            XCTAssertFalse(NetworkPolicy.isPrivateIPv4(host), host)
        }
    }

    func testBaseURLCannotEscapeOrigin() {
        for address in ["http://192.168.1.2:8743", "https://192.168.1.2:443", "https://192.168.1.2:65536",
                        "https://192.168.1.2:08743", "HTTPS://192.168.1.2:8743", "https://192.168.1.2:8743/",
                        "https://192.168.1.2:8743/a", "https://192.168.1.2:8743?x=1", "https://192.168.1.2:8743#x",
                        "https://person@192.168.1.2:8743", "https://192.168.1.2:8743@example.com:8743",
                        "https://192%2e168.1.2:8743", "https://[::1]:8743", "https://192.168.1.2:\n8743",
                        "https://192.168.1.2:8743\\@example.com"] {
            XCTAssertThrowsError(try NetworkPolicy.validateBaseURL(address))
        }
    }

    func testFingerprintAndBearerTokenValidation() throws {
        XCTAssertEqual(try NetworkPolicy.validateFingerprint(pin.uppercased()), pin)
        for invalid in ["", pin + "a", String(pin.dropLast()), pin + "\n", String(repeating: "z", count: 64)] {
            XCTAssertThrowsError(try NetworkPolicy.validateFingerprint(invalid))
        }
        XCTAssertEqual(try NetworkPolicy.validateToken(token), token)
        for invalid in [token + "\r\nX-Test: value", token + "=", String(token.dropLast()), "Bearer " + token] {
            XCTAssertThrowsError(try NetworkPolicy.validateToken(invalid))
        }
    }

    func testExactGatewayRoutes() throws {
        for path in ["/api/app/status", "/api/device/status", "/api/captures?limit=50",
                     "/api/environment/history?limit=20", "/api/automatic-ai/history",
                     "/api/device/snapshot?t=12345&_=67890", "/api/mobile/health",
                     "/captures/2026/10/11/capture_123.jpg", "/captures/test.json",
                     "/automatic-ai-results/2026/10/11/result_123.json"] {
            XCTAssertEqual(try NetworkPolicy.validatePath("GET", path), path)
        }
        for path in ["/api/capture", "/api/config", "/api/automatic-ai"] {
            XCTAssertEqual(try NetworkPolicy.validatePath("POST", path), path)
        }
        XCTAssertEqual(try NetworkPolicy.validatePath("POST", "/api/mobile/pair", pairing: true), "/api/mobile/pair")
    }

    func testDeniedRoutesAndQuerySmuggling() {
        for path in ["https://192.168.1.2:8743/api/app/status", "//192.168.1.2/api/app/status",
                     "/api/ac-control", "/api/mobile/pair", "/api/config", "/api/manual-vlm",
                     "/api/device/snapshot?url=example.com", "/api/captures?limit=1&limit=2",
                     "/api/captures?limit=1&", "/api/captures?limit", "/api/captures?limit=1=2",
                     "/api/captures?limit=-1", "/api/captures?limit=%32", "/api/captures?limit=1#x",
                     "/api/captures?since=2026", "/api/automatic-ai/history?limit=20", "/api/app/status?t=1",
                     "/api/captures?", "/captures/../secret.jpg", "/captures/a/../../secret.jpg",
                     "/captures/%2e%2e/secret.jpg", "/captures/a\\b.jpg", "/captures//a.jpg",
                     "/captures/./a.jpg", "/captures/a.jpg?download=1", "/captures/a.png",
                     "/automatic-ai-results/secret.jpg", "/captures/a.jpg\n", "/captures/中文.jpg"] {
            XCTAssertThrowsError(try NetworkPolicy.validatePath("GET", path), path)
        }
        XCTAssertThrowsError(try NetworkPolicy.validatePath("POST", "/api/mobile/pair"))
        XCTAssertThrowsError(try NetworkPolicy.validatePath("POST", "/api/config?x=1"))
        XCTAssertThrowsError(try NetworkPolicy.validatePath("DELETE", "/api/captures"))
    }

    func testImagesCannotBeExternalOrJSON() throws {
        XCTAssertEqual(try NetworkPolicy.validateImagePath("/captures/safe.jpg"), "/captures/safe.jpg")
        XCTAssertEqual(try NetworkPolicy.validateImagePath("/api/device/snapshot?t=123"), "/api/device/snapshot?t=123")
        for path in ["/captures/safe.json", "/api/app/status", "/automatic-ai-results/test.json", "https://example.com/a.jpg"] {
            XCTAssertThrowsError(try NetworkPolicy.validateImagePath(path))
        }
    }

    func testCommandRangesAndConsent() throws {
        try NetworkPolicy.validateCommand("/api/capture", body: [:])
        try NetworkPolicy.validateCommand("/api/config", body: ["capture_interval_seconds": .number(5), "schedule_enabled": .bool(true)])
        try NetworkPolicy.validateCommand("/api/automatic-ai", body: ["enabled": .bool(false)])
        try NetworkPolicy.validateCommand("/api/automatic-ai", body: ["enabled": .bool(true), "cloud_upload_consent": .bool(true),
                                                                     "min_interval_seconds": .number(120), "max_calls_per_hour": .number(6), "max_calls_per_session": .number(12)])
        XCTAssertThrowsError(try NetworkPolicy.validateCommand("/api/capture", body: ["device_url": .string("http://example.com")]))
        XCTAssertThrowsError(try NetworkPolicy.validateCommand("/api/config", body: [:]))
        XCTAssertThrowsError(try NetworkPolicy.validateCommand("/api/config", body: ["execute": .bool(true)]))
        XCTAssertThrowsError(try NetworkPolicy.validateCommand("/api/config", body: ["capture_interval_seconds": .number(4)]))
        XCTAssertThrowsError(try NetworkPolicy.validateCommand("/api/config", body: ["capture_interval_seconds": .number(5.5)]))
        XCTAssertThrowsError(try NetworkPolicy.validateCommand("/api/config", body: ["capture_interval_seconds": .bool(true)]))
        XCTAssertThrowsError(try NetworkPolicy.validateCommand("/api/config", body: ["capture_interval_seconds": .number(.infinity)]))
        XCTAssertThrowsError(try NetworkPolicy.validateCommand("/api/config", body: ["schedule_enabled": .number(1)]))
        XCTAssertThrowsError(try NetworkPolicy.validateCommand("/api/automatic-ai", body: ["enabled": .bool(true)]))
        XCTAssertThrowsError(try NetworkPolicy.validateCommand("/api/automatic-ai", body: ["enabled": .bool(true), "cloud_upload_consent": .bool(false)]))
        XCTAssertThrowsError(try NetworkPolicy.validateCommand("/api/automatic-ai", body: ["enabled": .bool(false), "max_calls_per_hour": .number(121)]))
        XCTAssertThrowsError(try NetworkPolicy.validateCommand("/api/automatic-ai", body: ["enabled": .bool(false), "execute": .bool(true)]))
    }

    private var payload: String {
        "{\"version\":1,\"base_url\":\"https://192.168.1.20:8743\",\"certificate_sha256\":\"\(pin)\",\"pairing_code\":\"AABBCCDDEEFF\"}"
    }

    func testPairingPayloadAcceptedAndNormalized() throws {
        let parsed = try PairingPayload.parse(" \n" + payload + "\r\n")
        XCTAssertEqual(parsed.version, 1)
        XCTAssertEqual(parsed.baseURL, "https://192.168.1.20:8743")
        XCTAssertEqual(parsed.certificateSHA256, pin)
        XCTAssertEqual(parsed.pairingCode, "AABBCCDDEEFF")
        XCTAssertEqual(try PairingPayload.parse(payload.replacingOccurrences(of: "version", with: "ver\\u0073ion")), parsed)
        XCTAssertEqual(try JSONDecoder().decode(PairingPayload.self, from: JSONEncoder().encode(parsed)), parsed)
    }

    func testPairingRejectsUnknownDuplicateMalformedOrFutureFields() {
        for invalid in ["[]", "{}", payload + "garbage", String(repeating: " ", count: 4097),
                        payload.replacingOccurrences(of: "\"version\":1", with: "\"version\":true"),
                        payload.replacingOccurrences(of: "\"version\":1", with: "\"version\":2"),
                        payload.replacingOccurrences(of: "\"version\":1", with: "\"version\":1.0"),
                        payload.replacingOccurrences(of: "\"version\":1", with: "\"version\":1e0"),
                        payload.replacingOccurrences(of: "\"version\":1", with: "\"version\":\"1\""),
                        payload.replacingOccurrences(of: "\"version\":1,", with: "\"version\":1,\"version\":1,"),
                        payload.replacingOccurrences(of: "\"base_url\":", with: "\"ver\\u0073ion\":"),
                        payload.replacingOccurrences(of: "\"base_url\":", with: "\"unknown\":"),
                        payload.replacingOccurrences(of: "AABBCCDDEEFF", with: "aabbccddeeff"),
                        payload.replacingOccurrences(of: "AABBCCDDEEFF", with: "AABBCCDD\\nEEFF"),
                        payload.replacingOccurrences(of: "https://192.168.1.20:8743", with: "https://example.com:8743"),
                        payload.replacingOccurrences(of: "}", with: ",\"extra\":1}")]
        { XCTAssertThrowsError(try PairingPayload.parse(invalid)) }
    }

    func testProfileValidityAndNameRules() throws {
        let future = ISO8601DateFormatter().string(from: Date().addingTimeInterval(3600))
        let profile = PairedProfile(baseURL: "https://192.168.1.20:8743", certificateSHA256: pin,
                                    token: token, expiresAt: future, deviceName: "我的 iPhone", serverName: "梦盾助手")
        try profile.validate()
        XCTAssertThrowsError(try profile.validate(now: Date().addingTimeInterval(7200)))
        for name in ["", "   ", "phone\nheader", String(repeating: "A", count: 81)] {
            XCTAssertThrowsError(try NetworkPolicy.validateDeviceName(name))
        }
        XCTAssertEqual(try NetworkPolicy.validateDeviceName(" 我的 iPhone "), "我的 iPhone")
    }

    func testSanitizedErrorsNeverEchoSecrets() {
        let errors: [GatewayError] = [.invalidAddress, .invalidFingerprint, .invalidPath, .invalidPairing,
                                      .authenticationRequired, .certificateRejected, .storageUnavailable, .unavailable]
        for error in errors {
            XCTAssertNotNil(error.errorDescription)
            XCTAssertFalse(error.localizedDescription.contains(pin))
            XCTAssertFalse(error.localizedDescription.contains(token))
        }
    }

    // These are structural DER fixtures, not trusted certificates. SecTrust must independently
    // verify real signatures/SAN/EKU; these tests exercise only the extra leaf-date guard.
    private func der(_ tag: UInt8, _ content: [UInt8]) -> [UInt8] {
        [tag, UInt8(content.count)] + content
    }
    private func certificate(before: String, after: String, generalized: Bool = false) -> Data {
        let tag: UInt8 = generalized ? 0x18 : 0x17
        let validity = der(0x30, der(tag, Array(before.utf8)) + der(tag, Array(after.utf8)))
        let tbs = der(0x30, der(0xa0, der(0x02, [2])) + der(0x02, [1]) + der(0x30, []) + der(0x30, []) + validity)
        return Data(der(0x30, tbs))
    }

    func testPinnedLeafExplicitDateValidity() throws {
        let now = try XCTUnwrap(NetworkPolicy.parseTimestamp("2026-10-11T12:00:00Z"))
        XCTAssertTrue(LeafCertificateValidity.isValid(certificate(before: "261010000000Z", after: "271010000000Z"), at: now))
        XCTAssertFalse(LeafCertificateValidity.isValid(certificate(before: "251010000000Z", after: "261010000000Z"), at: now))
        XCTAssertFalse(LeafCertificateValidity.isValid(certificate(before: "271010000000Z", after: "281010000000Z"), at: now))
        XCTAssertFalse(LeafCertificateValidity.isValid(certificate(before: "271010000000Z", after: "251010000000Z"), at: now))
        XCTAssertFalse(LeafCertificateValidity.isValid(certificate(before: "261332000000Z", after: "271010000000Z"), at: now))
        XCTAssertTrue(LeafCertificateValidity.isValid(certificate(before: "20261010000000Z", after: "20501010000000Z", generalized: true), at: now))
        XCTAssertFalse(LeafCertificateValidity.isValid(Data([0x30, 0x80, 0, 0]), at: now))
        XCTAssertFalse(LeafCertificateValidity.isValid(Data([0x30, 0xff]), at: now))
        XCTAssertFalse(LeafCertificateValidity.isValid(Data(), at: now))
    }
}
