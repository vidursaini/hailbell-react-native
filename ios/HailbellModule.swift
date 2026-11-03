import Combine
import ExpoModulesCore
import UIKit

/// The `Hailbell` native module that `@hailbell/react-native` calls. It is a thin layer over the Swift
/// SDK: each function forwards to the matching `Hailbell` or `Hailbell.push` call.
public final class HailbellModule: Module {
  private var unreadSubscription: AnyCancellable?
  private let unreadLock = NSLock()
  private var unread = 0
  /// `purchased` callbacks for offers whose paywall JS is showing, by request id. Main thread only.
  private var paywalls: [(id: String, purchased: () -> Void)] = []
  /// Firebase ID token requests waiting for JS, by request id. Main thread only.
  private var tokenRequests: [String: CheckedContinuation<String?, Never>] = [:]
  private var ownsPaywallHandler = false

  public func definition() -> ModuleDefinition {
    Name("Hailbell")

    Events("onUnreadCountChange", "onPaywall", "onFirebaseTokenRequest", "onPushOpen")

    OnCreate {
      HailbellRN.onMain { [weak self] in
        if let self { HailbellRN.module = self }
        self?.unreadSubscription = Hailbell.shared.$unreadCount.removeDuplicates().sink { [weak self] count in
          guard let self else { return }
          self.unreadLock.withLock { self.unread = count }
          self.sendEvent("onUnreadCountChange", ["count": count])
        }
      }
    }

    OnDestroy {
      HailbellRN.onMain { [weak self] in
        guard let self else { return }
        if HailbellRN.module === self {
          HailbellRN.module = nil
          HailbellRN.jsHandlesOpens = false
        }
        self.unreadSubscription = nil
        if self.ownsPaywallHandler { Hailbell.onPaywall = nil }
        let waiting = self.tokenRequests.values
        self.tokenRequests = [:]
        waiting.forEach { $0.resume(returning: nil) }
      }
    }

    // MARK: Hailbell

    Function("configure") { (appId: String, baseUrl: String?) in
      HailbellRN.onMain { HailbellRN.configure(appId: appId, baseURL: baseUrl) }
    }

    Function("open") {
      HailbellRN.onMain { HailbellRN.open() }
    }

    Function("close") {
      HailbellRN.onMain { HailbellRN.close() }
    }

    Function("identify") { (userId: String?, name: String?, email: String?, identityToken: String?) in
      HailbellRN.onMain { Hailbell.identify(userId: userId, name: name, email: email, identityToken: identityToken) }
    }

    Function("identifyWithFirebase") { (name: String?, email: String?) in
      HailbellRN.onMain { [weak self] in
        // The SDK calls this for every new session; JS asks Firebase for a current token each time.
        Hailbell.identify(name: name, email: email) { [weak self] in
          guard let self else { return nil }
          return await self.firebaseToken()
        }
      }
    }

    Function("provideFirebaseToken") { (requestId: String, token: String?) in
      HailbellRN.onMain { [weak self] in self?.finishTokenRequest(requestId, token: token) }
    }

    Function("setMetadata") { (metadata: [String: String]) in
      HailbellRN.onMain { Hailbell.setMetadata(metadata) }
    }

    Function("track") { (event: String, properties: [String: String]?) in
      HailbellRN.onMain { Hailbell.track(event, properties: properties ?? [:]) }
    }

    Function("setPaywallPlacements") { (placements: [String]) in
      HailbellRN.onMain { Hailbell.setPaywallPlacements(placements) }
    }

    Function("setPaywallHandler") { (enabled: Bool) in
      HailbellRN.onMain { [weak self] in
        guard let self else { return }
        self.ownsPaywallHandler = enabled
        guard enabled else {
          Hailbell.onPaywall = nil
          return
        }
        Hailbell.onPaywall = { [weak self] placement, purchased in
          guard let self else { return }
          let id = UUID().uuidString
          self.paywalls.append((id, purchased))
          // Only the latest offers can still be bought; older callbacks are let go.
          if self.paywalls.count > 20 { self.paywalls.removeFirst(self.paywalls.count - 20) }
          self.sendEvent("onPaywall", ["placement": placement, "requestId": id])
        }
      }
    }

    Function("paywallPurchased") { (requestId: String) in
      HailbellRN.onMain { [weak self] in
        guard let self, let index = self.paywalls.firstIndex(where: { $0.id == requestId }) else { return }
        let purchased = self.paywalls.remove(at: index).purchased
        purchased()
      }
    }

    Function("setPushToken") { (token: String, sandbox: Bool?) -> Bool in
      guard let data = HailbellRN.tokenData(hex: token) else { return false }
      HailbellRN.onMain { HailbellRN.setPushToken(data, sandbox: sandbox) }
      return true
    }

    Function("reset") {
      HailbellRN.onMain { Hailbell.reset() }
    }

    AsyncFunction("deleteData") { () async throws in
      try await HailbellRN.deleteData()
    }

    Function("getUnreadCount") { () -> Int in
      self.unreadLock.withLock { self.unread }
    }

    AsyncFunction("setTeamPhoto") { (uri: String?) async -> Bool in
      var image: UIImage?
      if let uri { image = await HailbellRN.loadImage(uri) }
      await HailbellRN.setTeamPhoto(image)
      return uri == nil || image != nil
    }

    Function("setAsksForNotifications") { (asks: Bool) in
      HailbellRN.onMain { Hailbell.asksForNotifications = asks }
    }

    Function("setDarkMode") { (dark: Bool?) in
      HailbellRN.onMain { Hailbell.darkMode = dark }
    }

    // MARK: Hailbell.push

    AsyncFunction("requestPushPermission") { (provisional: Bool) async -> Bool in
      await HailbellRN.requestPermission(provisional: provisional)
    }

    AsyncFunction("canNotify") { () async -> Bool in
      await HailbellRN.canNotify()
    }

    Function("setTags") { (set: [String: String], remove: [String]) in
      var tags: [String: String?] = set.mapValues { Optional($0) }
      for key in remove { tags[key] = .some(nil) }
      HailbellRN.onMain { Hailbell.push.setTags(tags) }
    }

    Function("setMarketing") { (enabled: Bool) in
      HailbellRN.onMain { Hailbell.push.setMarketing(enabled) }
    }

    Function("optOut") {
      HailbellRN.onMain { Hailbell.push.optOut() }
    }

    Function("setPushOpenHandler") { (enabled: Bool) in
      HailbellRN.onMain { [weak self] in
        guard let self else { return }
        HailbellRN.setJSHandlesOpens(enabled, module: self)
      }
    }

    // Android only: the channel for campaign pushes.
    Function("setOffersChannel") { (_: String) in }

    // iOS shows pushes itself; there is nothing to draw. Tells JS whether the push is Hailbell's.
    AsyncFunction("handleMessage") { (data: [String: Any], _: String?, _: String?) -> Bool in
      data["hailbell"] != nil || data["hb"] != nil
    }

    Function("clearBadge") { (appGroup: String?) in
      HailbellRN.onMain { Hailbell.push.clearBadge(appGroup: appGroup) }
    }

    AsyncFunction("handleTap") { (userInfo: [String: Any], actionId: String?) -> [String: Any]? in
      // runOnQueue(.main) below: this already runs on the main thread.
      var result: [String: Any]?
      MainActor.assumeIsolated { result = HailbellRN.routeTap(userInfo, actionId: actionId, fromJS: true) }
      return result
    }
    .runOnQueue(.main)
  }

  // MARK: Firebase ID tokens

  /// Asks JS for a current Firebase ID token. Gives up after 15 seconds, and the session then starts
  /// without one, as it does on native when the closure returns nil.
  @MainActor
  private func firebaseToken() async -> String? {
    let id = UUID().uuidString
    return await withCheckedContinuation { (continuation: CheckedContinuation<String?, Never>) in
      tokenRequests[id] = continuation
      sendEvent("onFirebaseTokenRequest", ["requestId": id])
      DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
        MainActor.assumeIsolated { self?.finishTokenRequest(id, token: nil) }
      }
    }
  }

  @MainActor
  private func finishTokenRequest(_ id: String, token: String?) {
    tokenRequests.removeValue(forKey: id)?.resume(returning: token?.isEmpty == false ? token : nil)
  }
}
