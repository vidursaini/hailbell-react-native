import SwiftUI
import UserNotifications

/// After the customer's first message, if notifications are off, offers to let them know when the
/// team replies. Without this, a reply only reaches them if they reopen the app. Asked once per
/// install; set `Hailbell.asksForNotifications = false` if your app asks at a better moment.
@MainActor
final class NotifyAsk: ObservableObject {
    enum State { case hidden, ask, settings }

    @Published private(set) var state: State = .hidden

    private var answered: Bool {
        get { UserDefaults.standard.bool(forKey: "hailbell.notifyAsked") }
        set { UserDefaults.standard.set(newValue, forKey: "hailbell.notifyAsked") }
    }

    /// Called after a message of theirs reaches the server.
    func customerSent() {
        guard Hailbell.asksForNotifications, !answered, state == .hidden else { return }
        Task {
            switch await HailbellPush.shared.permission {
            case .notDetermined: state = .ask
            case .denied: state = .settings
            default: break
            }
        }
    }

    func allow() {
        let wasDenied = state == .settings
        finish()
        if wasDenied {
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        } else {
            Task { await HailbellPush.shared.requestPermission() }
        }
    }

    func finish() {
        answered = true
        withAnimation(.easeOut(duration: 0.2)) { state = .hidden }
    }
}

/// The ask itself: a line and two buttons, styled like the chat's cards.
struct NotifyAskRow: View {
    @ObservedObject var ask: NotifyAsk
    let theme: ChatTheme

    var body: some View {
        if ask.state != .hidden {
            VStack(spacing: 10) {
                Label(L10n.text("notifyAsk"), systemImage: "bell.badge.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(theme.incomingText)
                    .multilineTextAlignment(.center)
                Button { ask.allow() } label: {
                    Text(L10n.text(ask.state == .settings ? "notifySettings" : "notifyAllow"))
                        .font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .foregroundStyle(theme.onAccent)
                        .background(theme.accent, in: Capsule())
                }
                .buttonStyle(.plain)
                Button(L10n.text("notNow")) { ask.finish() }
                    .font(.footnote).foregroundStyle(.secondary)
            }
            .padding(14)
            .background(theme.incoming, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .padding(.horizontal, 24)
            .padding(.top, 8)
            .transition(.opacity)
        }
    }
}
