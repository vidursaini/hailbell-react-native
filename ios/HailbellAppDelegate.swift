import ExpoModulesCore
import UIKit

/// Hooks into the app delegate, so a React Native app needs no native code of its own:
/// - At launch it configures Hailbell from the App ID the config plugin wrote to Info.plist, before any
///   JS runs. A tapped reply push on a cold start then finds the SDK ready.
/// - It passes the APNs device token to Hailbell as soon as iOS hands it over. That is what makes
///   `Hailbell.push.requestPermission()` enough for chat replies, with no push library at all.
/// - It sets `HailbellNotificationDelegate` when nothing else has, so taps on Hailbell pushes open the
///   chat or the push's link.
public class HailbellAppDelegate: ExpoAppDelegateSubscriber {
  public func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    MainActor.assumeIsolated { HailbellRN.configureFromInfoPlist() }
    HailbellNotificationDelegate.installIfUnclaimed()
    return true
  }

  public func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
    MainActor.assumeIsolated { HailbellRN.setPushToken(deviceToken) }
  }
}
