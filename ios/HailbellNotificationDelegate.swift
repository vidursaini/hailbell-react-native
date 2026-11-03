import UIKit
import UserNotifications

/// Receives taps on Hailbell pushes on iOS. React Native push libraries don't pass them all to JS:
/// React Native Firebase only reports pushes sent through Firebase, and Hailbell sends through APNs
/// directly. So at launch, if no other code has set a notification center delegate, Hailbell sets this
/// one. React Native Firebase and expo-notifications both keep it in their chain when they take over,
/// so taps reach it whichever library the app uses, or with none.
///
/// Other notifications pass through untouched: it only acts on Hailbell's own payloads.
final class HailbellNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
  /// The notification center holds its delegate weakly.
  static let shared = HailbellNotificationDelegate()

  /// Sets the delegate when the slot is empty. Called from `application(_:didFinishLaunchingWithOptions:)`,
  /// before React Native Firebase looks for an existing delegate to chain to.
  static func installIfUnclaimed() {
    let center = UNUserNotificationCenter.current()
    guard center.delegate == nil else { return }
    center.delegate = shared
  }

  private static let willPresent = #selector(
    UNUserNotificationCenterDelegate.userNotificationCenter(_:willPresent:withCompletionHandler:))

  /// React Native Firebase stops using its own foreground settings (`messaging_ios_foreground_presentation_options`
  /// in firebase.json) for every push once the delegate it chains to answers `willPresent`. So this
  /// delegate only answers when React Native Firebase isn't in the app.
  override func responds(to aSelector: Selector!) -> Bool {
    if aSelector == Self.willPresent { return NSClassFromString("RNFBMessagingUNUserNotificationCenter") == nil }
    return super.responds(to: aSelector)
  }

  func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    willPresent notification: UNNotification,
    withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
  ) {
    let info = notification.request.content.userInfo
    // Hailbell pushes show in the foreground too, as in the native setup. Others keep iOS's default.
    completionHandler(info["hailbell"] != nil || info["hb"] != nil ? [.banner, .list, .sound] : [])
  }

  func userNotificationCenter(
    _ center: UNUserNotificationCenter,
    didReceive response: UNNotificationResponse,
    withCompletionHandler completionHandler: @escaping () -> Void
  ) {
    let info = response.notification.request.content.userInfo
    let action = response.actionIdentifier == UNNotificationDefaultActionIdentifier ? nil : response.actionIdentifier
    if response.actionIdentifier != UNNotificationDismissActionIdentifier {
      HailbellRN.onMain { HailbellRN.routeTap(info, actionId: action, fromJS: false) }
    }
    completionHandler()
  }
}
