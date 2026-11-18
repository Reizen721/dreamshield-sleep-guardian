import Foundation
import Security

struct PairedProfile: Codable, Equatable, Sendable {
    let baseURL: String
    let certificateSHA256: String
    let token: String
    let expiresAt: String
    let deviceName: String
    let serverName: String

    func validate(now: Date = Date()) throws {
        _ = try NetworkPolicy.validateBaseURL(baseURL)
        _ = try NetworkPolicy.validateFingerprint(certificateSHA256)
        _ = try NetworkPolicy.validateToken(token)
        _ = try NetworkPolicy.validateDeviceName(deviceName)
        guard let expiry = NetworkPolicy.parseTimestamp(expiresAt), expiry > now,
              !serverName.isEmpty, serverName.count <= 80,
              !serverName.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
        else { throw GatewayError.authenticationRequired }
    }
}

/// One local device-only Keychain item; no iCloud sync, credential logs or UserDefaults.
enum ProfileStore {
    private static let service = "com.dreamshield.ios.gateway"
    private static let account = "paired-computer-v1"
    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service, kSecAttrAccount as String: account,
         kSecAttrSynchronizable as String: false]
    }

    static func load() throws -> PairedProfile? {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data, data.count <= 4096
        else { throw GatewayError.storageUnavailable }
        let profile: PairedProfile
        do { profile = try JSONDecoder().decode(PairedProfile.self, from: data) }
        catch { throw GatewayError.storageUnavailable }
        try profile.validate()
        return profile
    }

    static func save(_ profile: PairedProfile) throws {
        try profile.validate()
        let data: Data
        do { data = try JSONEncoder().encode(profile) }
        catch { throw GatewayError.storageUnavailable }
        guard data.count <= 4096 else { throw GatewayError.storageUnavailable }
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        let update = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if update == errSecItemNotFound {
            var insert = query
            attributes.forEach { insert[$0.key] = $0.value }
            guard SecItemAdd(insert as CFDictionary, nil) == errSecSuccess else { throw GatewayError.storageUnavailable }
        } else if update != errSecSuccess { throw GatewayError.storageUnavailable }
    }

    static func delete() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw GatewayError.storageUnavailable }
    }
}
