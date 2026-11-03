import Foundation
import UserNotifications

/// Subclass this as your Notification Service Extension's principal class:
///
///     import HailbellNotificationService
///     class NotificationService: HailbellNotificationService {}
///
/// For Hailbell pushes it adds the image, the buttons, badge increments, and tells Hailbell the push
/// arrived. Other pushes pass through untouched (override `didReceive` and call super for those).
open class HailbellNotificationService: UNNotificationServiceExtension {
    /// An App Group shared with your app, if you use badge increments: the count is kept there.
    open var appGroup: String? { nil }
    /// Where receipts go. Set HailbellAPI in the extension's Info.plist to override.
    open var apiBase: URL {
        (Bundle.main.object(forInfoDictionaryKey: "HailbellAPI") as? String).flatMap(URL.init(string:)) ?? URL(string: "https://api.hailbell.com")!
    }

    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var content: UNMutableNotificationContent?

    open override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        guard let hb = request.content.userInfo["hb"] as? [String: Any], let id = hb["n"] as? String,
              let content = request.content.mutableCopy() as? UNMutableNotificationContent else {
            contentHandler(request.content)
            return
        }
        self.contentHandler = contentHandler
        self.content = content
        Task {
            await withTaskGroup(of: Void.self) { group in
                group.addTask { await self.attachImage(hb["i"] as? String, to: content) }
                group.addTask { await self.registerButtons(hb["b"] as? [[String: Any]], notificationId: id) }
                group.addTask { await self.sendReceipt(hb) }
            }
            if let increase = hb["bi"] as? Int { self.increaseBadge(by: increase, content: content) }
            self.finish()
        }
    }

    open override func serviceExtensionTimeWillExpire() {
        finish()
    }

    private func finish() {
        guard let handler = contentHandler, let content else { return }
        contentHandler = nil
        handler(content)
    }

    private func attachImage(_ link: String?, to content: UNMutableNotificationContent) async {
        guard let link, let url = URL(string: link), url.scheme == "https",
              let (file, response) = try? await URLSession.shared.download(from: url) else { return }
        let type = (response as? HTTPURLResponse)?.mimeType ?? ""
        let ext = ["image/png": "png", "image/gif": "gif", "image/webp": "webp"][type] ?? "jpg"
        let target = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).\(ext)")
        guard (try? FileManager.default.moveItem(at: file, to: target)) != nil,
              let attachment = try? UNNotificationAttachment(identifier: "image", url: target) else { return }
        content.attachments = [attachment]
    }

    /// Buttons need a category; Hailbell names it after the notification and adds it next to the app's own.
    private func registerButtons(_ buttons: [[String: Any]]?, notificationId: String) async {
        guard let buttons, !buttons.isEmpty else { return }
        let actions = buttons.compactMap { b -> UNNotificationAction? in
            guard let id = b["id"] as? String, let text = b["text"] as? String else { return nil }
            return UNNotificationAction(identifier: id, title: text, options: [.foreground])
        }
        let category = UNNotificationCategory(identifier: "hailbell.\(notificationId)", actions: actions, intentIdentifiers: [])
        let center = UNUserNotificationCenter.current()
        var categories = await center.notificationCategories()
        // Keep the app's categories and the latest Hailbell ones.
        let old = categories.filter { $0.identifier.hasPrefix("hailbell.") }
        if old.count >= 20 { categories.subtract(old) }
        categories.insert(category)
        center.setNotificationCategories(categories)
        // iOS reads categories asynchronously; give it a moment before the notification is shown.
        try? await Task.sleep(nanoseconds: 300_000_000)
    }

    /// Big sends ask only some devices to confirm (rate r); each confirmation then counts for 1/r.
    private func sendReceipt(_ hb: [String: Any]) async {
        guard let id = hb["n"] as? String, let sub = hb["s"] as? String else { return }
        let rate = hb["r"] as? Double
        if let rate, Double.random(in: 0..<1) >= rate { return }
        var body: [String: Any] = ["n": id, "s": sub, "w": rate.map { Int(($0 > 0 ? 1 / $0 : 1).rounded()) } ?? 1]
        if let v = hb["v"] as? String { body["v"] = v }
        var req = URLRequest(url: apiBase.appendingPathComponent("v1/push/received"))
        req.httpMethod = "POST"
        req.timeoutInterval = 5
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)
        _ = try? await URLSession.shared.data(for: req)
    }

    private func increaseBadge(by amount: Int, content: UNMutableNotificationContent) {
        guard let group = appGroup, let defaults = UserDefaults(suiteName: group) else { return }
        let count = defaults.integer(forKey: "hailbell.badge") + amount
        defaults.set(count, forKey: "hailbell.badge")
        content.badge = NSNumber(value: count)
    }
}
