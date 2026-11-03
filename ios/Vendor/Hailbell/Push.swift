import Foundation
import UIKit
import UserNotifications

/// Push notifications sent from the Hailbell dashboard, the server API or an AI agent.
///
///     // At launch, after Hailbell.configure(appId:)
///     UNUserNotificationCenter.current().delegate = self
///     Task { await Hailbell.push.requestPermission() }
///
///     func application(_ app: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken token: Data) {
///         Hailbell.setPushToken(token)
///     }
///
///     // UNUserNotificationCenterDelegate
///     @MainActor
///     func userNotificationCenter(_ c: UNUserNotificationCenter, didReceive r: UNNotificationResponse) async {
///         if Hailbell.push.handle(r) { return }
///         Hailbell.handleNotification(r.notification.request.content.userInfo) // chat replies
///     }
///     func userNotificationCenter(_ c: UNUserNotificationCenter, willPresent n: UNNotification) async -> UNNotificationPresentationOptions {
///         [.banner, .list, .sound]
///     }
@MainActor
public final class HailbellPush {
    static let shared = HailbellPush()

    /// Called when the user taps a Hailbell notification or one of its buttons. Return true if you
    /// handled the link yourself; otherwise Hailbell opens it (deep links and web pages alike).
    public var onOpen: ((HailbellNotification) -> Bool)?

    private(set) var subscriptionId: String? {
        get { UserDefaults.standard.string(forKey: "hailbell.push.subscription") }
        set { UserDefaults.standard.set(newValue, forKey: "hailbell.push.subscription") }
    }
    /// The last `setMarketing` choice. It is kept on disk so every subscribe sends it, including the
    /// one for a new token, and the server never falls back to its default for this device.
    private(set) var marketing: Bool? {
        get { UserDefaults.standard.object(forKey: "hailbell.push.marketing") as? Bool }
        set { UserDefaults.standard.set(newValue, forKey: "hailbell.push.marketing") }
    }

    private init() {}

    /// Asks for permission to show notifications, then registers with Apple. Returns whether it was granted.
    @discardableResult
    public func requestPermission(provisional: Bool = false) async -> Bool {
        var options: UNAuthorizationOptions = [.alert, .badge, .sound]
        if provisional { options.insert(.provisional) }
        let granted = (try? await UNUserNotificationCenter.current().requestAuthorization(options: options)) ?? false
        if granted { UIApplication.shared.registerForRemoteNotifications() }
        return granted
    }

    /// Whether the user has allowed notifications.
    public var permission: UNAuthorizationStatus {
        get async { await UNUserNotificationCenter.current().notificationSettings().authorizationStatus }
    }

    /// Key/value data about the user, for targeting: ["plan": "pro", "favourite_cuisine": "thai"].
    /// A nil value removes the tag.
    public func setTags(_ tags: [String: String?]) {
        let body = tags.mapValues { $0.map { $0 as Any } ?? NSNull() }
        Task { try? await Hailbell.shared.client?.post("/v1/tags", body: ["tags": body]) }
    }

    /// Promotional pushes on or off. Transactional ones (receipts, replies) still arrive.
    /// The choice is remembered, so calling this at every launch costs nothing when it hasn't changed.
    public func setMarketing(_ enabled: Bool) {
        let changed = marketing != enabled
        marketing = enabled
        // An unchanged choice already reaches the server with the next subscribe.
        guard changed, let id = subscriptionId else { return }
        Task { try? await Hailbell.shared.client?.post("/v1/push/preferences", body: ["subscriptionId": id, "marketing": enabled]) }
    }

    /// Stops Hailbell pushes to this device until the next `setPushToken`.
    public func optOut() {
        guard let id = subscriptionId else { return }
        Task { try? await Hailbell.shared.client?.post("/v1/push/unsubscribe", body: ["subscriptionId": id]) }
    }

    /// Clears the app icon badge, and the count kept for badge increments in `appGroup`.
    public func clearBadge(appGroup: String? = nil) {
        if let appGroup { UserDefaults(suiteName: appGroup)?.set(0, forKey: "hailbell.badge") }
        UNUserNotificationCenter.current().setBadgeCount(0)
    }

    /// Handles a tap. Returns false for notifications that are not Hailbell pushes.
    @discardableResult
    public func handle(_ response: UNNotificationResponse) -> Bool {
        let action = response.actionIdentifier == UNNotificationDefaultActionIdentifier ? nil : response.actionIdentifier
        return handle(userInfo: response.notification.request.content.userInfo, actionIdentifier: action)
    }

    /// Handles a tap from its payload, for apps whose push library passes on the notification's
    /// `userInfo` rather than the `UNNotificationResponse` (React Native, Flutter). `actionIdentifier` is
    /// the tapped button's id, or nil for a tap on the notification itself. Returns false for
    /// notifications that are not Hailbell pushes.
    @discardableResult
    public func handle(userInfo: [AnyHashable: Any], actionIdentifier: String? = nil) -> Bool {
        guard let note = HailbellNotification(userInfo: userInfo) else { return false }
        var link = note.url
        if let actionIdentifier, let button = note.buttons.first(where: { $0.id == actionIdentifier }) { link = button.url ?? link }
        let tapped = HailbellNotification(copying: note, actionId: actionIdentifier, url: link)
        Task { try? await Hailbell.shared.client?.post("/v1/push/opened", body: note.ping, authorized: false) }
        if onOpen?(tapped) == true { return true }
        if let link { UIApplication.shared.open(link) }
        return true
    }

    // MARK: internal

    /// Registers (or refreshes) this device. Runs on every launch, so Hailbell knows the time zone,
    /// app version and the hours the app is used.
    func subscribe(token: String, sandbox: Bool) async {
        guard let client = Hailbell.shared.client else { return }
        let info = Bundle.main.infoDictionary ?? [:]
        var body: [String: Any] = [
            "platform": "apns", "token": token, "sandbox": sandbox,
            "language": Locale.preferredLanguages.first ?? Locale.current.identifier,
            "timezone": TimeZone.current.identifier,
            "appVersion": "\(info["CFBundleShortVersionString"] ?? "?")",
            "device": Self.model, "osVersion": UIDevice.current.systemVersion,
        ]
        if let marketing { body["marketing"] = marketing }
        struct Out: Decodable { let subscriptionId: String }
        if let out: Out = try? await client.request("POST", "/v1/push/subscribe", body: body) { subscriptionId = out.subscriptionId }
    }

    /// The model code, e.g. "iPhone17,2". Hailbell turns it into "iPhone 16 Pro Max". The simulator
    /// reports the Mac's chip, so it uses the model it's simulating.
    static var model: String {
        if let simulated = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return simulated }
        var info = utsname()
        uname(&info)
        return withUnsafePointer(to: &info.machine) { $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) } }
    }
}

/// A Hailbell push as your app sees it.
public struct HailbellNotification {
    public struct Button { public let id: String; public let text: String; public let url: URL? }

    public let id: String
    public let url: URL?
    public let imageURL: URL?
    public let buttons: [Button]
    /// The `data` object sent with the notification.
    public let data: [String: Any]
    /// The button tapped, if any.
    public let actionId: String?
    let subscriptionId: String?
    let version: String?

    init?(userInfo: [AnyHashable: Any]) {
        guard let hb = userInfo["hb"] as? [String: Any], let id = hb["n"] as? String else { return nil }
        self.id = id
        url = (hb["u"] as? String).flatMap(URL.init(string:))
        imageURL = (hb["i"] as? String).flatMap(URL.init(string:))
        buttons = (hb["b"] as? [[String: Any]] ?? []).compactMap { b in
            guard let id = b["id"] as? String, let text = b["text"] as? String else { return nil }
            return Button(id: id, text: text, url: (b["url"] as? String).flatMap(URL.init(string:)))
        }
        data = hb["d"] as? [String: Any] ?? [:]
        subscriptionId = hb["s"] as? String
        version = hb["v"] as? String
        actionId = nil
    }

    init(copying n: HailbellNotification, actionId: String?, url: URL?) {
        id = n.id; self.url = url; imageURL = n.imageURL; buttons = n.buttons; data = n.data
        subscriptionId = n.subscriptionId; version = n.version; self.actionId = actionId
    }

    var ping: [String: Any] {
        var body: [String: Any] = ["n": id]
        if let subscriptionId { body["s"] = subscriptionId }
        if let version { body["v"] = version }
        return body
    }
}

public extension Hailbell {
    /// Push notifications. See `HailbellPush`.
    static var push: HailbellPush { HailbellPush.shared }
}

extension Client {
    func post(_ path: String, body: [String: Any], authorized: Bool = true) async throws {
        struct Ignored: Decodable {}
        let _: Ignored = try await request("POST", path, body: body, authorized: authorized)
    }
}
