import SwiftUI
import UIKit

/// In-app support chat. Replies come from Discord or the Hailbell dashboard.
///
///     Hailbell.configure(appId: "app_...")
///     Hailbell.shared.open()
@MainActor
public final class Hailbell: ObservableObject {
    public static let shared = Hailbell()

    /// Replies the user has not read yet. Use it for a badge on your support button.
    @Published public private(set) var unreadCount = 0

    let chat = ChatModel()
    private(set) var client: Client?
    private var identity = Identity()
    private var pushToken: (token: String, sandbox: Bool)?
    private var placements: [String] = []
    private var retryTask: Task<Void, Never>?
    private var retryDelay: UInt64 = 2

    /// Opens a paywall when the user taps an offer you sent in the chat. Call `purchased` if they buy,
    /// so you can see which offers worked. With Superwall:
    ///
    ///     Hailbell.onPaywall = { placement, purchased in
    ///         let handler = PaywallPresentationHandler()
    ///         handler.onDismiss { _, result in if case .purchased = result { purchased() } }
    ///         Superwall.shared.register(placement: placement, handler: handler)
    ///     }
    public static var onPaywall: ((_ placement: String, _ purchased: @escaping () -> Void) -> Void)?

    /// Deprecated: set the photo in the dashboard instead (Profile, per app), which you can change
    /// without an app release. A photo bundled here shows only until the chat first hears from
    /// Hailbell, and after that only while the dashboard has no photo of its own for this app. It
    /// never shows once the app signs replies as the team, turns photos off, or picks a photo (or no
    /// photo) for this app in the dashboard.
    public static var teamPhoto: UIImage?

    /// After the customer's first message, the chat offers to turn on notifications if they're off,
    /// so they see the reply. Set to false if your app asks for notification permission itself.
    public static var asksForNotifications = true

    /// Whether the chat is dark. Nil (the default) follows the phone's setting. Set it when your app has
    /// its own fixed look, so the chat matches the app rather than the system: an always-dark app sets true.
    public static var darkMode: Bool?

    private init() {}

    /// Call once at launch, before anything else.
    public static func configure(appId: String, baseURL: URL = URL(string: "https://api.hailbell.com")!) {
        let hb = shared
        hb.client = Client(appId: appId, baseURL: baseURL)
        hb.chat.attach(to: hb)
        Task { await hb.refreshSession() }
    }

    /// Tells Hailbell who the user is. Pass `identityToken` (an HS256 JWT from your backend, signed with
    /// the app's identity secret) to link the chat to the account across devices. Without it the name and
    /// email are shown to you but the chat stays tied to this install.
    public static func identify(userId: String? = nil, name: String? = nil, email: String? = nil, identityToken: String? = nil) {
        shared.identity.userId = userId
        shared.identity.name = name
        shared.identity.email = email
        shared.identity.identityToken = identityToken
        shared.identity.firebaseIdToken = nil
        Task { await shared.refreshSession() }
    }

    /// Links the chat to a Firebase Auth user, so it follows them to a reinstall or a new phone. Hailbell
    /// checks the token against Google's keys and the Firebase project set in the dashboard's push
    /// settings, and the Firebase uid becomes the user's id. Pass a closure such as
    /// `{ try await Auth.auth().currentUser?.getIDToken() }`: ID tokens last an hour, so Hailbell asks
    /// for a fresh one each time it starts a session. Call `reset()` on sign-out.
    public static func identify(name: String? = nil, email: String? = nil, firebaseIdToken: @escaping () async throws -> String?) {
        shared.identity.userId = nil
        shared.identity.identityToken = nil
        shared.identity.name = name
        shared.identity.email = email
        shared.identity.firebaseIdToken = firebaseIdToken
        Task { await shared.refreshSession() }
    }

    /// Extra context shown next to the conversation, e.g. ["plan": "free", "appVersion": "2.3"].
    public static func setMetadata(_ metadata: [String: String]) {
        shared.identity.metadata.merge(metadata) { _, new in new }
        Task { await shared.refreshSession() }
    }

    /// Clears the identity, for example on sign-out. The next session starts as this install's anonymous chat.
    public static func reset() {
        shared.identity = Identity()
        shared.chat.reset()
        Task { await shared.refreshSession() }
    }

    /// Deletes this user's chat, images and details from Hailbell, for example when they delete their
    /// account in your app. It also clears `identify`, so the next session starts a fresh, empty,
    /// anonymous chat. Call `identify` again if the person stays signed in to a new account.
    public static func deleteData() async throws {
        guard let client = shared.client else { return }
        try await client.deleteData()
        // Forget who they were too. Otherwise the next session signs the same person straight back in
        // and recreates the contact, name and email that were just deleted.
        shared.identity = Identity(metadata: shared.identity.metadata)
        shared.chat.reset()
        await shared.refreshSession()
    }

    /// Records a signal such as "paywall_decline". Signals show next to the chat and drive nudges later.
    public static func track(_ event: String, properties: [String: String] = [:]) {
        Task { try? await shared.client?.track(event, properties: properties) }
    }

    /// The paywall placements an offer can open, so you can pick them by name in the dashboard and Discord.
    public static func setPaywallPlacements(_ placements: [String]) {
        shared.placements = placements
        Task { try? await shared.client?.reportPlacements(placements) }
    }

    /// Pass the token from `application(_:didRegisterForRemoteNotificationsWithDeviceToken:)`. Leave
    /// `sandbox` out to read it from the build: Xcode development builds get sandbox tokens, while App
    /// Store, TestFlight and ad hoc builds get production ones.
    public static func setPushToken(_ deviceToken: Data, sandbox: Bool? = nil) {
        let sandbox = sandbox ?? apnsSandbox
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        shared.pushToken = (token, sandbox)
        Task {
            try? await shared.client?.registerPush(token: token, sandbox: sandbox)
            await HailbellPush.shared.subscribe(token: token, sandbox: sandbox)
        }
    }

    /// Returns true when the notification came from Hailbell, and opens the chat.
    @discardableResult
    public static func handleNotification(_ userInfo: [AnyHashable: Any]) -> Bool {
        guard userInfo["hailbell"] != nil else { return false }
        shared.open()
        return true
    }

    /// True when the app was signed with a development profile, or runs in the Simulator (which gets
    /// sandbox tokens but carries no profile). App Store builds carry no profile.
    static let apnsSandbox: Bool = {
        #if targetEnvironment(simulator)
        return true
        #else
        guard let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .isoLatin1) else { return false }
        return text.range(of: #"<key>aps-environment</key>\s*<string>development</string>"#, options: .regularExpression) != nil
        #endif
    }()

    /// Presents the chat as a sheet over the top-most view controller. With the chat already open,
    /// for example a reply tapped while the app was in the background with the chat on screen, it
    /// catches that chat up instead of stacking a second one on top.
    public func open() {
        if chat.isOnScreen {
            chat.catchUp()
            return
        }
        guard let top = topViewController() else { return }
        let host = UIHostingController(rootView: HailbellChatView())
        host.modalPresentationStyle = .pageSheet
        // The sheet, its grabber and the status bar follow the chat's look too, not only the SwiftUI content.
        if let dark = Self.darkMode { host.overrideUserInterfaceStyle = dark ? .dark : .light }
        if let sheet = host.sheetPresentationController {
            sheet.prefersGrabberVisible = true
            // Full height straight away: a shorter glass sheet turned solid with a visible jump when the keyboard came up.
            sheet.detents = [.large()]
        }
        present(host, top)
    }

    /// Shows the chat sheet. Tests swap it to count presentations without a window.
    var present: (UIViewController, UIViewController) -> Void = { sheet, top in top.present(sheet, animated: true) }

    /// Where the sheet goes. Tests swap it, since their host has no window.
    var topViewController: () -> UIViewController? = { UIApplication.shared.topViewController }

    // MARK: internal

    private var sessionGeneration = 0

    func setUnread(_ count: Int) {
        unreadCount = count
    }

    func refreshSession() async {
        guard let client else { return }
        // configure, setMetadata and identify each start a session, often in the same instant at launch.
        // Only the newest may win: an older anonymous session finishing last would send the signed-in
        // person's messages to the install's anonymous chat.
        sessionGeneration += 1
        let generation = sessionGeneration
        do {
            let session = try await client.startSession(identity: identity)
            guard generation == sessionGeneration else { return }
            client.token = session.token
            unreadCount = session.unread
            chat.sessionStarted(session)
            if let push = pushToken {
                try? await client.registerPush(token: push.token, sandbox: push.sandbox)
                await HailbellPush.shared.subscribe(token: push.token, sandbox: push.sandbox)
            }
            if !placements.isEmpty { try? await client.reportPlacements(placements) }
            retryDelay = 2
        } catch {
            guard generation == sessionGeneration else { return }
            chat.failed(error)
            scheduleRetry(after: error)
        }
    }

    /// The chat opened while offline: try now instead of waiting for the next retry.
    func reconnectNow() {
        retryTask?.cancel()
        retryTask = nil
        retryDelay = 2
        Task { await refreshSession() }
    }

    /// Offline or the server is down: try again after 2, 4, 8… up to 60 seconds. A request the server
    /// refused (a wrong App ID, a blocked user) is not retried.
    private func scheduleRetry(after error: Error) {
        if let api = error as? APIError, (400..<500).contains(api.status), api.status != 408, api.status != 429 { return }
        guard retryTask == nil else { return }
        let delay = retryDelay
        retryDelay = min(retryDelay * 2, 60)
        retryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
            guard let self, !Task.isCancelled else { return }
            self.retryTask = nil
            await self.refreshSession()
        }
    }
}

struct Identity {
    var userId: String?
    var name: String?
    var email: String?
    var identityToken: String?
    /// Fetches a current Firebase ID token for each session. Nil when the app doesn't use Firebase Auth.
    var firebaseIdToken: (() async throws -> String?)?
    var metadata: [String: String] = [:]
}

/// The chat screen, for apps that want to place it themselves (in a NavigationStack, a tab, a sheet).
public struct HailbellChatView: View {
    public init() {}
    public var body: some View {
        if let dark = Hailbell.darkMode {
            ChatView(model: Hailbell.shared.chat).environment(\.colorScheme, dark ? .dark : .light)
        } else {
            ChatView(model: Hailbell.shared.chat)
        }
    }
}

extension UIApplication {
    var topViewController: UIViewController? {
        let scene = connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
            ?? connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}
