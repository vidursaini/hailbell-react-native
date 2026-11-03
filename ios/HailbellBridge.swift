import Foundation
import SwiftUI
import UIKit
import UserNotifications

// The vendored Swift SDK (Vendor/Hailbell) compiles into this same pod, so `Hailbell`, `HailbellPush`
// and `HailbellNotification` are used directly, without an import. Only their public API is used here.

/// Everything the React Native module does on the main actor. The Swift SDK is `@MainActor`; React
/// Native calls arrive on its own threads, so every call is queued on the main queue, in order.
enum HailbellRN {
  /// Runs `body` on the main actor after anything already queued, so calls keep the order JS made them in.
  static func onMain(_ body: @escaping @MainActor () -> Void) {
    DispatchQueue.main.async { MainActor.assumeIsolated { body() } }
  }

  // MARK: configure

  /// The App ID and server the SDK was configured with, so a second identical call does nothing. The
  /// config plugin configures at launch from Info.plist, and the app's JS usually configures again.
  @MainActor private static var configured: (appId: String, baseURL: String)?

  static let defaultBaseURL = "https://api.hailbell.com"

  @MainActor
  static func configure(appId: String, baseURL: String?) {
    let base = baseURL.flatMap { $0.isEmpty ? nil : $0 } ?? defaultBaseURL
    guard !appId.isEmpty, let url = URL(string: base) else {
      NSLog("[Hailbell] configure: the App ID is empty or the base URL is not a URL.")
      return
    }
    if let configured, configured.appId == appId, configured.baseURL == base { return }
    if configured != nil {
      NSLog("[Hailbell] configure: switching to App ID \(appId). Configure once, with one App ID.")
    }
    configured = (appId, base)
    Hailbell.configure(appId: appId, baseURL: url)
  }

  /// `HailbellAppId` (and optionally `HailbellBaseURL`) in Info.plist, written by the config plugin.
  @MainActor
  static func configureFromInfoPlist() {
    guard let appId = Bundle.main.object(forInfoDictionaryKey: "HailbellAppId") as? String, !appId.isEmpty else { return }
    configure(appId: appId, baseURL: Bundle.main.object(forInfoDictionaryKey: "HailbellBaseURL") as? String)
  }

  // MARK: chat screen

  /// Opens the chat. Right after a cold launch (a tapped reply push) the window may not have a root
  /// view controller yet, so it tries again for a few seconds. It never stacks a second chat.
  @MainActor
  static func open(attempt: Int = 0) {
    guard let top = topViewController() else {
      if attempt < 20 {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { MainActor.assumeIsolated { open(attempt: attempt + 1) } }
      }
      return
    }
    if isChat(top) { return }
    // The chat presents over the top-most view controller. A view controller that is still appearing
    // (a React Native modal mid-animation) can't present yet.
    if top.isBeingPresented || top.isBeingDismissed, attempt < 20 {
      DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { MainActor.assumeIsolated { open(attempt: attempt + 1) } }
      return
    }
    Hailbell.shared.open()
  }

  /// Closes the chat if it is on screen, for example before showing a paywall drawn in React Native.
  @MainActor
  static func close() {
    var controller = rootViewController()
    while let presented = controller?.presentedViewController {
      if isChat(presented) {
        presented.dismiss(animated: true)
        return
      }
      controller = presented
    }
  }

  @MainActor
  private static func isChat(_ controller: UIViewController) -> Bool {
    controller is UIHostingController<HailbellChatView>
  }

  @MainActor
  private static func rootViewController() -> UIViewController? {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    let window = scene?.windows.first { $0.isKeyWindow } ?? scene?.windows.first
    return window?.rootViewController
  }

  @MainActor
  private static func topViewController() -> UIViewController? {
    var top = rootViewController()
    while let presented = top?.presentedViewController { top = presented }
    return top
  }

  // MARK: push

  /// The last token passed on, so the token from the app delegate and the same token from JS
  /// (`getDevicePushTokenAsync`, `getAPNSToken`) register once, not twice.
  @MainActor private static var lastToken: (token: Data, sandbox: Bool?)?

  @MainActor
  static func setPushToken(_ token: Data, sandbox: Bool? = nil) {
    if let lastToken, lastToken.token == token, lastToken.sandbox == sandbox { return }
    lastToken = (token, sandbox)
    Hailbell.setPushToken(token, sandbox: sandbox)
  }

  /// An APNs device token as hex, the way `expo-notifications` and React Native Firebase hand it over.
  static func tokenData(hex: String) -> Data? {
    let clean = hex.trimmingCharacters(in: .whitespaces).lowercased()
    guard clean.count >= 32, clean.count % 2 == 0, clean.allSatisfy(\.isHexDigit) else { return nil }
    var data = Data(capacity: clean.count / 2)
    var index = clean.startIndex
    while index < clean.endIndex {
      let next = clean.index(index, offsetBy: 2)
      guard let byte = UInt8(clean[index..<next], radix: 16) else { return nil }
      data.append(byte)
      index = next
    }
    return data
  }

  @MainActor
  static func deleteData() async throws {
    try await Hailbell.deleteData()
  }

  @MainActor
  static func setTeamPhoto(_ image: UIImage?) {
    Hailbell.teamPhoto = image
  }

  @MainActor
  static func requestPermission(provisional: Bool) async -> Bool {
    await Hailbell.push.requestPermission(provisional: provisional)
  }

  @MainActor
  static func canNotify() async -> Bool {
    switch await Hailbell.push.permission {
    case .authorized, .provisional, .ephemeral: return true
    default: return false
    }
  }

  // MARK: taps

  /// The module, for sending tapped campaign pushes to JS. Set while the JS runtime is alive.
  @MainActor static weak var module: HailbellModule?
  /// True while JS has an `onOpen` handler for campaign taps.
  @MainActor static var jsHandlesOpens = false
  /// Campaign taps from before JS was ready (a cold start), waiting for an `onOpen` handler.
  @MainActor private static var pendingOpens: [(key: String, payload: [String: Any], url: URL?)] = []
  /// Recently handled campaign taps. One tap can arrive twice, from the delegate and from a JS push
  /// library, and must count and open once.
  @MainActor private static var recentTaps: [(key: String, at: Date)] = []

  /// A tapped push, from the notification delegate or from JS. A chat reply opens the chat. A campaign
  /// push is counted as opened, then handed to JS's `onOpen`, or its link opens.
  ///
  /// From JS, the campaign comes back as the result and JS routes it. From the delegate it goes to JS
  /// as an `onPushOpen` event when JS has an `onOpen` handler; otherwise its link opens here, after up
  /// to 3 seconds on a cold start while JS loads.
  @MainActor
  @discardableResult
  static func routeTap(_ userInfo: [AnyHashable: Any], actionId: String?, fromJS: Bool) -> [String: Any]? {
    if userInfo["hailbell"] != nil {
      open()
      return ["kind": "chat"]
    }
    guard let hb = userInfo["hb"] as? [String: Any], let id = hb["n"] as? String else { return nil }
    let key = "\(id)|\(actionId ?? "")"
    recentTaps.removeAll { $0.at.timeIntervalSinceNow < -30 }
    if recentTaps.contains(where: { $0.key == key }) { return ["kind": "duplicate"] }
    recentTaps.append((key, Date()))

    var tapped: HailbellNotification?
    let previous = Hailbell.push.onOpen
    Hailbell.push.onOpen = { note in
      tapped = note
      return true
    }
    defer { Hailbell.push.onOpen = previous }
    // Counts the open, then calls onOpen above with the link of the tapped button.
    guard Hailbell.push.handle(userInfo: userInfo, actionIdentifier: actionId), let note = tapped else { return nil }
    let payload = serialize(note)
    if fromJS { return payload }

    if jsHandlesOpens, let module {
      module.sendEvent("onPushOpen", payload)
      return payload
    }
    if module != nil {
      // JS is running and has no onOpen handler: open the link now, as the native SDK does.
      if let url = note.url { UIApplication.shared.open(url) }
      return payload
    }
    // A cold start: JS isn't running yet. Wait a moment for it to set onOpen.
    pendingOpens.append((key, payload, note.url))
    DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
      MainActor.assumeIsolated {
        guard let index = pendingOpens.firstIndex(where: { $0.key == key }) else { return }
        let waiting = pendingOpens.remove(at: index)
        if let url = waiting.url { UIApplication.shared.open(url) }
      }
    }
    return payload
  }

  /// JS set or cleared `Hailbell.push.onOpen`. Taps that waited for it are delivered now.
  @MainActor
  static func setJSHandlesOpens(_ handles: Bool, module: HailbellModule) {
    jsHandlesOpens = handles
    self.module = module
    guard handles else { return }
    let waiting = pendingOpens
    pendingOpens = []
    waiting.forEach { module.sendEvent("onPushOpen", $0.payload) }
  }

  @MainActor
  private static func serialize(_ note: HailbellNotification) -> [String: Any] {
    // Only values JS can hold: a nil is left out rather than sent as an empty Optional.
    var out: [String: Any] = ["kind": "campaign", "id": note.id, "data": note.data]
    if let url = note.url { out["url"] = url.absoluteString }
    if let image = note.imageURL { out["imageUrl"] = image.absoluteString }
    if let action = note.actionId { out["actionId"] = action }
    out["buttons"] = note.buttons.map { button -> [String: Any] in
      var b: [String: Any] = ["id": button.id, "text": button.text]
      if let url = button.url { b["url"] = url.absoluteString }
      return b
    }
    return out
  }

  // MARK: team photo

  /// Loads the team photo from what `Image.resolveAssetSource` returns: an http URL from Metro in
  /// development, a file URL in a release build, or an asset name.
  static func loadImage(_ uri: String) async -> UIImage? {
    if let url = URL(string: uri), let scheme = url.scheme?.lowercased() {
      switch scheme {
      case "http", "https":
        guard let (data, response) = try? await URLSession.shared.data(from: url),
              (response as? HTTPURLResponse)?.statusCode ?? 200 < 400 else { return nil }
        return UIImage(data: data, scale: scale(of: uri))
      case "file":
        guard let data = try? Data(contentsOf: url) else { return nil }
        return UIImage(data: data, scale: scale(of: uri))
      case "data":
        guard let data = try? Data(contentsOf: url) else { return nil }
        return UIImage(data: data)
      default:
        break
      }
    }
    return UIImage(named: uri)
  }

  /// `photo@2x.png` is a 2x image, so the header shows it at its point size.
  private static func scale(of uri: String) -> CGFloat {
    if uri.contains("@3x") { return 3 }
    if uri.contains("@2x") { return 2 }
    return 1
  }
}
