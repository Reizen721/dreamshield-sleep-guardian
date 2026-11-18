import Foundation
import Security
import CryptoKit

/// The only HTTP implementation in the app. It never contacts a model or the ESP32 directly.
final class GatewayClient: @unchecked Sendable {
    private let baseURL: String
    private let certificateSHA256: String
    private let token: String?
    private let transport: GatewayTransport
    private let session: URLSession

    init(baseURL: String, certificateSHA256: String, token: String? = nil) throws {
        self.baseURL = try NetworkPolicy.validateBaseURL(baseURL)
        self.certificateSHA256 = try NetworkPolicy.validateFingerprint(certificateSHA256)
        self.token = try token.map(NetworkPolicy.validateToken)
        guard let components = URLComponents(string: self.baseURL),
              let host = components.host, let port = components.port else { throw GatewayError.invalidAddress }
        transport = GatewayTransport(host: host, port: port, fingerprint: self.certificateSHA256)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.allowsCellularAccess = false
        configuration.waitsForConnectivity = false
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 30
        configuration.httpMaximumConnectionsPerHost = 4
        configuration.tlsMinimumSupportedProtocolVersion = .TLSv12
        let queue = OperationQueue()
        queue.name = "DreamShield.gateway.delegate"
        queue.maxConcurrentOperationCount = 1
        session = URLSession(configuration: configuration, delegate: transport, delegateQueue: queue)
    }

    deinit { session.invalidateAndCancel() }

    /// Cancels current work but leaves this client reusable for a later foreground refresh.
    func cancelAll() { transport.cancelAll() }

    func getJSON(_ path: String) async throws -> JSONObject {
        try await json(method: "GET", path: path, body: nil)
    }

    func postJSON(_ path: String, body: JSONObject) async throws -> JSONObject {
        try NetworkPolicy.validateCommand(path, body: body)
        return try await json(method: "POST", path: path, body: body)
    }

    func getImage(_ path: String) async throws -> Data {
        let checkedPath = try NetworkPolicy.validateImagePath(path)
        let response = try await exchange(method: "GET", path: checkedPath, body: nil, kind: .jpeg)
        guard response.body.count >= 3, Array(response.body.prefix(3)) == [0xff, 0xd8, 0xff]
        else { throw GatewayError.invalidResponse }
        return response.body
    }

    func pair(code: String, deviceName: String) async throws -> PairedProfile {
        guard token == nil, NetworkPolicy.matches(code, "[0-9A-F]{12}") else { throw GatewayError.invalidPairing }
        let name = try NetworkPolicy.validateDeviceName(deviceName)
        let result = try await json(method: "POST", path: "/api/mobile/pair", body: [
            "pairing_code": .string(code), "device_name": .string(name)
        ], pairing: true)
        guard result["ok"] == .bool(true), case .some(.string(let returnedToken)) = result["token"],
              case .some(.string(let expiresAt)) = result["expires_at"],
              case .some(.string(let returnedName)) = result["device_name"],
              case .some(.string(let serverName)) = result["server_name"], returnedName == name
        else { throw GatewayError.invalidResponse }
        let profile = PairedProfile(baseURL: baseURL, certificateSHA256: certificateSHA256,
                                    token: returnedToken, expiresAt: expiresAt,
                                    deviceName: returnedName, serverName: serverName)
        try profile.validate()
        return profile // The owner decides when to commit this result to Keychain.
    }

    private func json(method: String, path: String, body: JSONObject?, pairing: Bool = false) async throws -> JSONObject {
        let response = try await exchange(method: method, path: path, body: body, kind: .json, pairing: pairing)
        do { return try JSONDecoder().decode(JSONObject.self, from: response.body) }
        catch { throw GatewayError.invalidResponse }
    }

    private func exchange(method: String, path: String, body: JSONObject?, kind: GatewayResponseKind,
                          pairing: Bool = false) async throws -> GatewayResponse {
        let checked = try NetworkPolicy.validatePath(method, path, pairing: pairing)
        if !pairing && checked != "/api/mobile/health" && token == nil { throw GatewayError.authenticationRequired }
        guard let url = URL(string: baseURL + checked) else { throw GatewayError.invalidPath }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 20)
        request.httpMethod = method
        request.setValue(kind == .jpeg ? "image/jpeg" : "application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if let token { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if method == "POST" {
            let data: Data
            do { data = try JSONEncoder().encode(body ?? [:]) }
            catch { throw GatewayError.invalidCommand }
            guard data.count <= NetworkPolicy.maximumCommandBytes else { throw GatewayError.invalidCommand }
            request.httpBody = data
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.setValue(String(data.count), forHTTPHeaderField: "Content-Length")
        }
        let finalRequest = request
        let cancellation = RequestCancellation()
        do {
            return try await withTaskCancellationHandler(operation: {
                try Task.checkCancellation()
                return try await withCheckedThrowingContinuation { continuation in
                    let task = session.dataTask(with: finalRequest)
                    transport.register(task, kind: kind, continuation: continuation)
                    cancellation.attach(task)
                    task.resume()
                }
            }, onCancel: { cancellation.cancel() })
        } catch is CancellationError { throw GatewayError.cancelled }
        catch let error as GatewayError { throw error }
        catch { throw GatewayError.unavailable }
    }
}

private enum GatewayResponseKind: Equatable { case json, jpeg }
private struct GatewayResponse { let body: Data }

/// Handles cancellation even when Swift's task is cancelled just before URLSession registration.
private final class RequestCancellation: @unchecked Sendable {
    private let lock = NSLock()
    private var task: URLSessionTask?
    private var cancelled = false
    func attach(_ value: URLSessionTask) {
        lock.lock()
        task = value
        let mustCancel = cancelled
        lock.unlock()
        if mustCancel { value.cancel() }
    }
    func cancel() {
        lock.lock()
        cancelled = true
        let current = task
        lock.unlock()
        current?.cancel()
    }
}

private final class GatewayTransport: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private final class Pending {
        let task: URLSessionDataTask
        let kind: GatewayResponseKind
        let continuation: CheckedContinuation<GatewayResponse, Error>
        var data = Data()
        init(task: URLSessionDataTask, kind: GatewayResponseKind, continuation: CheckedContinuation<GatewayResponse, Error>) {
            self.task = task; self.kind = kind; self.continuation = continuation
        }
    }
    private let host: String
    private let port: Int
    private let fingerprint: String
    private let lock = NSLock()
    private var pending: [Int: Pending] = [:]

    init(host: String, port: Int, fingerprint: String) {
        self.host = host; self.port = port; self.fingerprint = fingerprint
        super.init()
    }

    func register(_ task: URLSessionDataTask, kind: GatewayResponseKind,
                  continuation: CheckedContinuation<GatewayResponse, Error>) {
        lock.lock()
        pending[task.taskIdentifier] = Pending(task: task, kind: kind, continuation: continuation)
        lock.unlock()
    }

    func cancelAll() {
        lock.lock()
        let work = Array(pending.values)
        pending.removeAll()
        lock.unlock()
        for item in work {
            item.task.cancel()
            item.continuation.resume(throwing: GatewayError.cancelled)
        }
    }

    private func finish(_ task: URLSessionTask, error: GatewayError? = nil) {
        lock.lock()
        let item = pending.removeValue(forKey: task.taskIdentifier)
        lock.unlock()
        guard let item else { return }
        if let error {
            task.cancel()
            item.continuation.resume(throwing: error)
        } else { item.continuation.resume(returning: GatewayResponse(body: item.data)) }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, let url = http.url,
              url.scheme == "https", url.host == host, url.port == port else {
            finish(dataTask, error: .invalidResponse); completionHandler(.cancel); return
        }
        guard http.statusCode == 200 else {
            let error: GatewayError
            switch http.statusCode {
            case 401: error = .authenticationRequired
            case 403: error = .forbidden
            case 300...399: error = .redirectRejected
            case 500...599: error = .unavailable
            default: error = .invalidResponse
            }
            finish(dataTask, error: error); completionHandler(.cancel); return
        }
        guard response.expectedContentLength <= Int64(NetworkPolicy.maximumResponseBytes) else {
            finish(dataTask, error: .responseTooLarge); completionHandler(.cancel); return
        }
        lock.lock()
        let expected = pending[dataTask.taskIdentifier]?.kind
        lock.unlock()
        let accepted = expected == .json ? response.mimeType?.lowercased() == "application/json"
            : expected == .jpeg && response.mimeType?.lowercased() == "image/jpeg"
        guard accepted else { finish(dataTask, error: .invalidResponse); completionHandler(.cancel); return }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        guard let item = pending[dataTask.taskIdentifier] else { lock.unlock(); dataTask.cancel(); return }
        // Enforced before each append, including chunked/unknown Content-Length responses.
        let exceedsLimit = data.count > NetworkPolicy.maximumResponseBytes - item.data.count
        if !exceedsLimit { item.data.append(data) }
        lock.unlock()
        if exceedsLimit { finish(dataTask, error: .responseTooLarge) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { finish(task); return }
        let nsError = error as NSError
        let mapped: GatewayError
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled { mapped = .cancelled }
        else if nsError.domain == NSURLErrorDomain && (-1206 ... -1200).contains(nsError.code) { mapped = .certificateRejected }
        else { mapped = .unavailable }
        finish(task, error: mapped)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        // Reject even same-host redirects; never forward the bearer credential to a new URL.
        finish(task, error: .redirectRejected)
        completionHandler(nil)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    willCacheResponse proposedResponse: CachedURLResponse,
                    completionHandler: @escaping (CachedURLResponse?) -> Void) { completionHandler(nil) }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        evaluate(challenge, completionHandler: completionHandler)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        evaluate(challenge, completionHandler: completionHandler)
    }

    private func evaluate(_ challenge: URLAuthenticationChallenge,
                          completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let space = challenge.protectionSpace
        guard challenge.previousFailureCount == 0, space.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              space.host == host, space.port == port, space.protocol == "https", let trust = space.serverTrust,
              let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate], let leaf = chain.first else {
            rejectChallenge(completionHandler); return
        }
        let der = SecCertificateCopyData(leaf) as Data
        let actual = SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
        let samePin = zip(actual.utf8, fingerprint.utf8).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
        let now = Date()
        // An explicitly trusted anchor can have different root-date treatment by the OS.
        // Check the pinned leaf's own DER validity interval as well as SecTrust's SSL policy.
        guard actual.utf8.count == fingerprint.utf8.count, samePin,
              LeafCertificateValidity.isValid(der, at: now),
              SecTrustSetPolicies(trust, SecPolicyCreateSSL(true, host as CFString)) == errSecSuccess,
              SecTrustSetAnchorCertificates(trust, [leaf] as CFArray) == errSecSuccess,
              SecTrustSetAnchorCertificatesOnly(trust, true) == errSecSuccess,
              SecTrustSetNetworkFetchAllowed(trust, false) == errSecSuccess,
              SecTrustSetVerifyDate(trust, now as CFDate) == errSecSuccess,
              SecTrustEvaluateWithError(trust, nil) else {
            rejectChallenge(completionHandler); return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }

    private func rejectChallenge(_ completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        // All tasks on this immutable client share one exact origin and pin. Return a useful
        // sanitized error even on OS versions that describe a rejected challenge as -999.
        lock.lock()
        let work = Array(pending.values)
        pending.removeAll()
        lock.unlock()
        completionHandler(.cancelAuthenticationChallenge, nil)
        for item in work {
            item.task.cancel()
            item.continuation.resume(throwing: GatewayError.certificateRejected)
        }
    }
}

/// A minimal, fail-closed reader of RFC 5280's TBSCertificate validity field.
/// It is not a substitute for signature/SAN/EKU checks, which remain SecTrust's job.
enum LeafCertificateValidity {
    static func isValid(_ certificate: Data, at date: Date) -> Bool {
        do {
            var outer = DERReader(Array(certificate))
            let sequence = try outer.read(tag: 0x30)
            guard outer.finished else { return false }
            var cert = DERReader(sequence)
            var tbs = DERReader(try cert.read(tag: 0x30))
            if tbs.nextTag == 0xa0 { _ = try tbs.read(tag: 0xa0) }
            _ = try tbs.read(tag: 0x02) // serial
            _ = try tbs.read(tag: 0x30) // signature algorithm
            _ = try tbs.read(tag: 0x30) // issuer
            var validity = DERReader(try tbs.read(tag: 0x30))
            let before = try validity.readTime()
            let after = try validity.readTime()
            return validity.finished && before <= after && before <= date && date <= after
        } catch { return false }
    }
}

private struct DERReader {
    private let bytes: [UInt8]
    private var cursor = 0
    init(_ bytes: [UInt8]) { self.bytes = bytes }
    var finished: Bool { cursor == bytes.count }
    var nextTag: UInt8? { cursor < bytes.count ? bytes[cursor] : nil }
    mutating func read(tag: UInt8) throws -> [UInt8] {
        guard cursor + 2 <= bytes.count, bytes[cursor] == tag else { throw GatewayError.certificateRejected }
        cursor += 1
        let firstLength = bytes[cursor]
        cursor += 1
        var length = Int(firstLength)
        if firstLength & 0x80 != 0 {
            let count = Int(firstLength & 0x7f)
            guard (1...4).contains(count), cursor + count <= bytes.count, bytes[cursor] != 0
            else { throw GatewayError.certificateRejected }
            length = 0
            for _ in 0..<count { length = length * 256 + Int(bytes[cursor]); cursor += 1 }
            guard length >= 128 else { throw GatewayError.certificateRejected }
        }
        guard length <= bytes.count - cursor else { throw GatewayError.certificateRejected }
        let value = Array(bytes[cursor..<cursor + length])
        cursor += length
        return value
    }
    mutating func readTime() throws -> Date {
        guard let tag = nextTag, tag == 0x17 || tag == 0x18,
              let raw = String(bytes: try read(tag: tag), encoding: .ascii) else { throw GatewayError.certificateRejected }
        let normalized: String
        if tag == 0x17 {
            guard NetworkPolicy.matches(raw, "[0-9]{12}Z"), let year = Int(raw.prefix(2)) else { throw GatewayError.certificateRejected }
            normalized = (year >= 50 ? "19" : "20") + raw
        } else {
            guard NetworkPolicy.matches(raw, "[0-9]{14}Z") else { throw GatewayError.certificateRejected }
            normalized = raw
        }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMddHHmmss'Z'"
        formatter.isLenient = false
        guard let date = formatter.date(from: normalized), formatter.string(from: date) == normalized
        else { throw GatewayError.certificateRejected }
        return date
    }
}
