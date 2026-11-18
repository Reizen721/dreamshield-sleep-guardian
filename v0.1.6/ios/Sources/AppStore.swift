import Foundation
import SwiftUI
import Photos
import ImageIO

@MainActor
final class AppStore: ObservableObject {
    @Published var tab: AppTab = .home {
        didSet { if tab != .home { stopPreview() } }
    }
    @Published private(set) var profile: PairedProfile?
    @Published private(set) var status: JSONObject = [:]
    @Published private(set) var deviceStatus: JSONObject = [:]
    @Published private(set) var connected = false
    @Published private(set) var busy = false
    @Published private(set) var notice: String?
    @Published private(set) var previewImage: UIImage?
    @Published private(set) var previewTimestamp: Date?
    @Published private(set) var previewRunning = false
    @Published private(set) var captures: [CaptureItem] = []
    @Published private(set) var events: [AIEventItem] = []
    @Published private(set) var selectedImage: UIImage?
    @Published private(set) var selectedImageTitle = ""
    @Published private(set) var isDemo = false
    @Published private var clock = Date()
    @Published var scheduleEnabled = false
    @Published var captureInterval = 60
    @Published var environmentEnabled = false
    @Published var environmentInterval = 5
    @Published var aiInterval = 60
    @Published var aiHourly = 20
    @Published var aiSession = 50
    @Published var developerUnlocked = false
    @Published var showAIConsent = false
    @Published var cloudConsent = false
    @Published var pairingText = ""
    @Published var pendingPairing: PairingPayload?
    @Published var showPairingConfirmation = false

    private var client: GatewayClient?
    private var pairingClient: GatewayClient?
    private var foreground = false
    private var epoch = UUID()
    private var actionID: UUID?
    private var refreshID: UUID?
    private var pollTask: Task<Void, Never>?
    private var clockTask: Task<Void, Never>?
    private var previewTask: Task<Void, Never>?
    private var previewEpoch = UUID()
    private var environmentRequestedAt = Date.distantPast
    private var statusReceivedAt = Date.distantPast
    private var inputsInitialized = false
    private var gate = DeveloperGate()
    private var consentEpoch: UUID?
    private var consentPayload: JSONObject?

    init(loadSavedProfile: Bool = true) {
        guard loadSavedProfile else { return }
        do {
            if let stored = try ProfileStore.load() {
                guard let expires = Labels.date(stored.expiresAt), expires > Date() else {
                    notice = "配对已过期，请从电脑生成新配置后重新配对。"
                    return
                }
                profile = stored
                client = try GatewayClient(baseURL: stored.baseURL, certificateSHA256: stored.certificateSHA256, token: stored.token)
            }
        } catch GatewayError.authenticationRequired { notice = "配对已过期，请在电脑生成新配置后重新配对。" }
        catch { notice = "无法读取本机配对。请解锁设备，或重新核对电脑配对配置。" }
    }

    var connectionTitle: String {
        if isDemo { return "离线演示" }
        if connected { return "电脑已连接" }
        return profile == nil ? "等待配对" : "电脑未连接"
    }
    private var statusFresh: Bool {
        let age = clock.timeIntervalSince(statusReceivedAt)
        return connected && age >= 0 && age <= 15
    }
    var sensor: SensorPresentation {
        SensorPresentation.make(deviceStatus.value("environment"), elapsed: clock.timeIntervalSince(environmentRequestedAt), online: connected)
    }
    var ai: AIPresentation { .make(status, online: statusFresh) }
    var controller: ControllerPresentation { .make(status, online: statusFresh) }

    func setForeground(_ active: Bool) {
        guard active != foreground else { return }
        foreground = active
        if active {
            clock = Date()
            pollTask = Task { [weak self] in
                while !Task.isCancelled {
                    guard let self, self.foreground else { return }
                    await self.refresh()
                    do { try await Task.sleep(for: .seconds(5)) } catch { return }
                }
            }
            clockTask = Task { [weak self] in
                while !Task.isCancelled {
                    guard let self, self.foreground else { return }
                    self.clock = Date()
                    do { try await Task.sleep(for: .seconds(1)) } catch { return }
                }
            }
        } else {
            invalidateSessionView()
            pollTask?.cancel(); pollTask = nil
            clockTask?.cancel(); clockTask = nil
            lockDeveloper()
        }
    }

    private func invalidateSessionView() {
        epoch = UUID(); actionID = nil; refreshID = nil; busy = false
        client?.cancelAll(); pairingClient?.cancelAll(); pairingClient = nil
        stopPreview(); previewImage = nil; previewTimestamp = nil
        dismissImage(); captures = []; events = []
        connected = false; deviceStatus = [:]; status = [:]
        inputsInitialized = false
        showAIConsent = false; cloudConsent = false; consentEpoch = nil; consentPayload = nil
        cancelPairing()
    }

    func refresh() async {
        guard foreground, refreshID == nil, !busy, isDemo || client != nil else { return }
        if isDemo { applyDemo(); return }
        guard let client else { return }
        let generation = epoch, requestID = UUID()
        refreshID = requestID
        defer { if refreshID == requestID { refreshID = nil } }
        do {
            let response = try await client.getJSON("/api/app/status")
            guard isCurrent(generation) else { return }
            status = response; connected = true; statusReceivedAt = Date(); clock = Date()
            if !inputsInitialized { resetInputs(); inputsInitialized = true }
            let requestedAt = Date()
            do {
                let device = try await client.getJSON("/api/device/status")
                guard isCurrent(generation) else { return }
                deviceStatus = device; environmentRequestedAt = requestedAt; clock = Date()
            } catch {
                if isCurrent(generation) { deviceStatus = [:]; handleAuthentication(error) }
            }
        } catch {
            guard isCurrent(generation) else { return }
            connected = false; deviceStatus = [:]; stopPreview()
            report(error, fallback: "暂时无法连接电脑。请检查同一家庭网络、电脑电源与服务器；电脑任务可能仍在运行。")
        }
    }

    func togglePreview() {
        if previewRunning { stopPreview(); return }
        guard foreground, connected, !busy, tab == .home else { notice = "请先连接电脑，再开启预览。"; return }
        previewRunning = true
        let generation = epoch, imageGeneration = UUID()
        previewEpoch = imageGeneration
        previewTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.isCurrent(generation), self.previewRunning, self.previewEpoch == imageGeneration else { return }
                do {
                    let image: UIImage
                    if self.isDemo { image = DemoContent.image() }
                    else {
                        guard let client = self.client else { return }
                        image = try Self.decodeImage(try await client.getImage("/api/device/snapshot"))
                    }
                    guard self.isCurrent(generation), self.previewEpoch == imageGeneration, self.previewRunning else { return }
                    self.previewImage = image; self.previewTimestamp = Date()
                } catch {
                    if self.isCurrent(generation), self.previewEpoch == imageGeneration {
                        self.stopPreview(); self.report(error, fallback: "预览已停止，请检查摄像头和电脑连接。")
                    }
                    return
                }
                do { try await Task.sleep(for: .milliseconds(2500)) } catch { return }
            }
        }
    }

    private func stopPreview() {
        previewRunning = false; previewEpoch = UUID()
        previewTask?.cancel(); previewTask = nil
    }

    func capture() async {
        await performAction {
            let generation = self.epoch
            self.stopPreview()
            if self.isDemo {
                self.previewImage = DemoContent.image(); self.previewTimestamp = Date()
                self.notice = "演示抓拍：未连接摄像头、未保存电脑文件。"; return
            }
            guard let client = self.client else { throw ClientActionError.notConnected }
            let response = try await client.postJSON("/api/capture", body: [:])
            guard self.isCurrent(generation) else { return }
            guard let path = response.value("capture", "url").string else { throw ClientActionError.invalidResponse }
            let image = try Self.decodeImage(try await client.getImage(path))
            guard self.isCurrent(generation) else { return }
            self.previewImage = image
            self.previewTimestamp = Labels.date(response.value("capture", "captured_at").string ?? "") ?? Date()
            self.notice = "抓拍已保存在电脑。可将当前画面另存到 iPhone 相册。"
        }
    }

    func loadHistory() async {
        await performAction {
            if self.isDemo { self.captures = DemoContent.captures(); self.events = DemoContent.events(); return }
            guard let client = self.client else { throw ClientActionError.notConnected }
            let generation = self.epoch
            let captureResult = try await client.getJSON("/api/captures?limit=24")
            guard self.isCurrent(generation) else { return }
            let values = captureResult["items"]?.array ?? []
            var seen = Set<String>()
            self.captures = values.prefix(24).compactMap { value in
                guard let path = value["url"].string, Self.safeCapturePath(path), seen.insert(path).inserted else { return nil }
                return CaptureItem(id: path, imagePath: path, createdAt: String((value["captured_at"].string ?? "").prefix(80)), source: Labels.text(value["source"].string, fallback: "抓拍记录"))
            }
            let eventResult = try await client.getJSON("/api/automatic-ai/history")
            guard self.isCurrent(generation) else { return }
            self.events = (eventResult["items"]?.array ?? []).prefix(30).enumerated().map { index, value in
                let assessment = value["result"]["assessment"]
                return AIEventItem(id: "\(index)-\(String((value["id"].string ?? "").prefix(80)))", createdAt: String((value["after"]["captured_at"].string ?? value["created_at"].string ?? "").prefix(80)), title: Labels.text(assessment["validated_visual_decision"].string ?? value["status"].string, fallback: "观察记录"), detail: Labels.text(assessment["blanket_event_state"].string ?? assessment["confirmation_state"].string, fallback: "历史观察，不代表当前覆盖状态"))
            }
        }
    }

    func openCapture(_ item: CaptureItem) async {
        await performAction {
            let generation = self.epoch
            let image: UIImage
            if self.isDemo { image = DemoContent.image() }
            else {
                guard let client = self.client, Self.safeCapturePath(item.imagePath), self.captures.contains(item) else { throw ClientActionError.invalidResponse }
                image = try Self.decodeImage(try await client.getImage(item.imagePath))
            }
            guard self.isCurrent(generation) else { return }
            self.selectedImageTitle = item.createdAt; self.selectedImage = image
        }
    }

    func saveImage(_ image: UIImage) async {
        guard foreground, !busy else { return }
        guard !isDemo else { notice = "演示画面不会写入相册。"; return }
        // Save the immutable image the user actually saw, never fetch a replacement snapshot.
        guard image === previewImage || image === selectedImage else { notice = "图片已失效，请重新打开。"; return }
        let generation = epoch
        let authorization = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard isCurrent(generation) else { return }
        guard authorization == .authorized || authorization == .limited else {
            notice = "未获准添加照片。可到系统设置中允许梦盾添加照片后重试。"; return
        }
        await performAction {
            guard let bytes = image.jpegData(compressionQuality: 0.95) else { throw ClientActionError.invalidResponse }
            try await PHPhotoLibrary.shared().performChanges {
                let request = PHAssetCreationRequest.forAsset()
                let options = PHAssetResourceCreationOptions()
                options.originalFilename = "DreamShield-\(UUID().uuidString).jpg"
                request.addResource(with: .photo, data: bytes, options: options)
            }
            if self.isCurrent(generation) { self.notice = "当前所见图片已保存到系统相册。" }
        }
    }

    func saveConfiguration() async {
        await performAction {
            guard (5...86400).contains(self.captureInterval), (5...3600).contains(self.environmentInterval) else { throw ClientActionError.invalidConfiguration }
            let payload: JSONObject = ["schedule_enabled": .bool(self.scheduleEnabled), "capture_interval_seconds": .number(Double(self.captureInterval)), "environment_recording_enabled": .bool(self.environmentEnabled), "environment_interval_seconds": .number(Double(self.environmentInterval))]
            if self.isDemo { self.status["config"] = .object(payload); self.notice = "仅修改演示状态，电脑任务未改变。"; return }
            guard let client = self.client else { throw ClientActionError.notConnected }
            let generation = self.epoch
            _ = try await client.postJSON("/api/config", body: payload)
            guard self.isCurrent(generation) else { return }
            self.inputsInitialized = false; self.notice = "设置已提交到电脑，等待刷新确认。"
        }
        await refresh()
    }

    func requestAIStart() {
        guard foreground, connected, statusFresh, !busy else { notice = "请先刷新并确认电脑连接。"; return }
        guard isDemo || status.value("vlm_key_configured").bool == true else { notice = "电脑尚未配置模型 API 密钥，请先在电脑完成配置。"; return }
        guard let interval = status.value("config", "capture_interval_seconds").number, (5...300).contains(interval) else { notice = "自动 AI 需要电脑抓拍间隔在 5–300 秒之间；先保存抓拍设置。"; return }
        guard (60...3600).contains(aiInterval), (2...120).contains(aiHourly), (2...500).contains(aiSession) else { notice = "AI 调用预算或对比间隔超出允许范围。"; return }
        consentPayload = ["enabled": .bool(true), "cloud_upload_consent": .bool(true), "min_interval_seconds": .number(Double(aiInterval)), "max_calls_per_hour": .number(Double(aiHourly)), "max_calls_per_session": .number(Double(aiSession))]
        cloudConsent = false; consentEpoch = epoch; showAIConsent = true
    }

    func confirmAIStart() async {
        guard foreground, showAIConsent, cloudConsent, consentEpoch == epoch, let payload = consentPayload else {
            notice = "请重新阅读并明确同意图片上传与调用费用。"; return
        }
        showAIConsent = false; cloudConsent = false; consentPayload = nil; consentEpoch = nil
        await performAction {
            let generation = self.epoch
            if self.isDemo { self.notice = "演示模式不会上传图片或调用 AI。"; return }
            guard let client = self.client else { throw ClientActionError.notConnected }
            _ = try await client.postJSON("/api/automatic-ai", body: payload)
            guard self.isCurrent(generation) else { return }
            self.inputsInitialized = false; self.notice = "已请求开启电脑自动 AI；退出手机不会停止电脑任务。"
        }
        await refresh()
    }

    func stopAI() async {
        showAIConsent = false; cloudConsent = false; consentPayload = nil; consentEpoch = nil
        await performAction {
            if self.isDemo { self.notice = "演示模式没有运行中的 AI 请求。"; return }
            guard let client = self.client else { throw ClientActionError.notConnected }
            let generation = self.epoch
            _ = try await client.postJSON("/api/automatic-ai", body: ["enabled": .bool(false)])
            if self.isCurrent(generation) { self.notice = "停止请求已发送。已上传或已发出的模型请求无法撤回。" }
        }
        await refresh()
    }

    func preparePairing() {
        guard foreground, !busy, !isDemo else { return }
        do { pendingPairing = try PairingPayload.parse(pairingText); showPairingConfirmation = true }
        catch { notice = "配对配置不合法。请粘贴电脑生成的完整 JSON，并核对局域网地址与证书指纹。" }
    }

    func confirmPairing() async {
        guard foreground, !busy, !isDemo, showPairingConfirmation, let payload = pendingPairing else { return }
        showPairingConfirmation = false; pendingPairing = nil; pairingText = ""
        // Pairing is explicit; simply pasting JSON never sends the one-time code.
        invalidateSessionView()
        let generation = epoch, identifier = UUID()
        actionID = identifier; busy = true
        defer { if actionID == identifier { busy = false; actionID = nil; pairingClient = nil } }
        do {
            let pairing = try GatewayClient(baseURL: payload.baseURL, certificateSHA256: payload.certificateSHA256)
            pairingClient = pairing
            let result = try await pairing.pair(code: payload.pairingCode, deviceName: "梦盾 iOS 客户端")
            guard isCurrent(generation) else { return }
            let authenticated = try GatewayClient(baseURL: result.baseURL, certificateSHA256: result.certificateSHA256, token: result.token)
            try ProfileStore.save(result)
            client?.cancelAll(); client = authenticated; profile = result
            notice = "配对已保存，请刷新连接后开启预览。"; inputsInitialized = false
        } catch {
            if isCurrent(generation) {
                // An invalid one-time pairing code must not invalidate the previous saved session.
                if let gateway = error as? GatewayError, case .cancelled = gateway { return }
                notice = (error as? GatewayError)?.errorDescription ?? "配对未完成。请检查本地网络权限、指纹和配对码有效期；原配对未覆盖。"
            }
        }
    }

    func cancelPairing() { showPairingConfirmation = false; pendingPairing = nil; pairingText = "" }

    func unpair() async {
        guard !busy else { return }
        do {
            try ProfileStore.delete()
            invalidateSessionView(); client = nil; profile = nil; isDemo = false; lockDeveloper()
            notice = "本机配对已移除。电脑任务和相册照片不受影响；如需撤销所有令牌，请在电脑配对页操作。"
        } catch { notice = "本机配对未能清除，请解锁设备后重试。" }
    }

    func versionTapped() { developerUnlocked = gate.tap() }
    func resetVersionTaps() { gate.resetTaps() }
    func lockDeveloper() { gate.lock(); developerUnlocked = false; resetAdvancedInputs() }
    func dismissImage() { selectedImage = nil; selectedImageTitle = "" }
    func clearNotice() { notice = nil }

    func setDemo(_ enabled: Bool) async {
        guard !busy else { return }
        invalidateSessionView(); lockDeveloper(); isDemo = enabled
        if enabled {
            // No live client is read while demo is selected; existing pairing remains in Keychain.
            applyDemo(); notice = "正在查看离线演示；不会连接硬件、保存照片或调用 AI。"
        } else {
            notice = "已退出演示，重新读取真实电脑状态。"
            await refresh()
        }
    }

    private func applyDemo() {
        if status.isEmpty { status = DemoContent.status() }
        deviceStatus = DemoContent.device()
        connected = true; clock = Date(); statusReceivedAt = clock; environmentRequestedAt = clock
        if !inputsInitialized { resetInputs(); inputsInitialized = true }
    }
    private func resetInputs() {
        scheduleEnabled = status.value("config", "schedule_enabled").bool == true
        environmentEnabled = status.value("config", "environment_recording_enabled").bool == true
        captureInterval = integer(status.value("config", "capture_interval_seconds"), range: 5...86400, fallback: 60)
        resetAdvancedInputs()
    }
    private func resetAdvancedInputs() {
        environmentInterval = integer(status.value("config", "environment_interval_seconds"), range: 5...3600, fallback: 5)
        aiInterval = integer(status.value("automatic_ai", "min_interval_seconds"), range: 60...3600, fallback: 60)
        aiHourly = integer(status.value("automatic_ai", "max_calls_per_hour"), range: 2...120, fallback: 20)
        aiSession = integer(status.value("automatic_ai", "max_calls_per_session"), range: 2...500, fallback: 50)
    }
    private func integer(_ value: JSONValue, range: ClosedRange<Int>, fallback: Int) -> Int {
        guard let number = value.number, number.isFinite, number.rounded() == number,
              number >= Double(range.lowerBound), number <= Double(range.upperBound) else { return fallback }
        return Int(number)
    }
    private func isCurrent(_ generation: UUID) -> Bool { foreground && generation == epoch && !Task.isCancelled }
    private func performAction(_ operation: () async throws -> Void) async {
        guard foreground, !busy, isDemo || client != nil else { notice = "请先连接电脑。"; return }
        let generation = epoch, identifier = UUID()
        actionID = identifier; busy = true
        defer { if actionID == identifier { busy = false; actionID = nil } }
        do { try await operation() }
        catch { if isCurrent(generation) { report(error, fallback: "操作未完成，请检查电脑连接后重试；不要假定任务已生效。") } }
    }
    private func report(_ error: Error, fallback: String) {
        if error is CancellationError { return }
        if let gateway = error as? GatewayError, case .cancelled = gateway { return }
        if handleAuthentication(error) { return }
        // Only our explicitly sanitized error types are exposed, never raw URLSession or server text.
        notice = (error as? ClientActionError)?.errorDescription ?? (error as? GatewayError)?.errorDescription ?? fallback
    }
    @discardableResult private func handleAuthentication(_ error: Error) -> Bool {
        guard let gateway = error as? GatewayError, case .authenticationRequired = gateway else { return false }
        invalidateSessionView(); client = nil
        notice = "手机授权已失效或被撤销，请在电脑重新生成配置后配对。电脑任务可能仍在运行。"
        return true
    }
    private static func safeCapturePath(_ path: String) -> Bool {
        path.count <= 500 && !path.contains("..") && path.range(of: #"^/captures/(?:[0-9]{4}/[0-9]{2}/[0-9]{2}/)?[A-Za-z0-9_-]+\.jpg$"#, options: .regularExpression) != nil
    }
    private static func decodeImage(_ data: Data) throws -> UIImage {
        guard data.count <= 10 * 1024 * 1024,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = props[kCGImagePropertyPixelWidth] as? NSNumber,
              let height = props[kCGImagePropertyPixelHeight] as? NSNumber,
              width.intValue > 0, height.intValue > 0, width.intValue <= 4096, height.intValue <= 4096,
              width.intValue * height.intValue <= 12_000_000,
              let image = UIImage(data: data) else { throw ClientActionError.invalidResponse }
        return image
    }
}

enum ClientActionError: LocalizedError {
    case notConnected, invalidResponse, invalidConfiguration
    var errorDescription: String? {
        switch self {
        case .notConnected: return "请先连接电脑服务器。"
        case .invalidResponse: return "电脑未返回有效图片或数据，请刷新后重试。"
        case .invalidConfiguration: return "抓拍间隔需为 5–86400 秒，温湿度间隔需为 5–3600 秒。"
        }
    }
}
