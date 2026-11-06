import Foundation
import UIKit

/// Holds the conversation and the live socket. One per app, owned by `Hailbell.shared`.
@MainActor
final class ChatModel: ObservableObject {
    struct Pending: Identifiable, Equatable {
        let id: String
        let body: String
        var images: [UIImage] = []
        var failed = false
    }

    @Published private(set) var messages: [Message] = []
    @Published private(set) var pending: [Pending] = []
    @Published private(set) var appName = ""
    @Published private(set) var connected = false
    @Published private(set) var assistant: Assistant?
    @Published private(set) var appearance = Appearance()
    @Published private(set) var team: [TeamMember] = []
    /// Whether the last answer from Hailbell lets a photo bundled in the app show (see `bundledPhoto`).
    /// Kept across launches and sign-outs, so an offline launch follows the dashboard too.
    @Published private(set) var bundledPhotoAllowed = UserDefaults.standard.object(forKey: ChatModel.bundledPhotoKey) as? Bool ?? true
    @Published private(set) var hasMore = false
    /// When you last read the chat, and when the customer's messages last reached you, for the ticks under their messages.
    @Published private(set) var agentReadAt: Double = 0
    @Published private(set) var agentDeliveredAt: Double = 0
    @Published private(set) var agentTyping = false
    /// When someone on the team last did something (ms), for the dot on their photo. Nil until the
    /// server says.
    @Published private(set) var agentActiveAt: Double?
    @Published var errorText: String?
    let notifyAsk = NotifyAsk()

    private weak var owner: Hailbell?
    private var socket: URLSessionWebSocketTask?
    private var pingTask: Task<Void, Never>?
    private var typingClear: Task<Void, Never>?
    private var typingSentAt = Date.distantPast
    private var visible = false
    private var conversationId: String?

    func attach(to owner: Hailbell) {
        self.owner = owner
        restore()
        let center = NotificationCenter.default
        center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.backgrounded() }
        }
        center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.foregrounded() }
        }
    }

    var baseURL: URL? { owner?.client?.baseURL }

    enum Delivery { case sent, delivered, seen }

    /// The ticks under the customer's message: one once we have it, two once it reached you, blue
    /// once you read it. Only the last of a run of their messages shows them.
    func delivery(of m: Message) -> Delivery? {
        guard m.isMine, let i = messages.firstIndex(where: { $0.id == m.id }) else { return nil }
        if i + 1 < messages.count, messages[i + 1].isMine { return nil }
        if agentReadAt >= m.createdAt { return .seen }
        return agentDeliveredAt >= m.createdAt ? .delivered : .sent
    }

    /// The person who answers, when the dashboard shows their photo.
    var teamPhoto: URL? {
        guard appearance.teamPhotos != false, let path = team.first?.avatarUrl, let base = baseURL else { return nil }
        return URL(string: path, relativeTo: base)
    }

    /// The photo beside a reply: its teammate's photo in this app, else the header's. None when the
    /// dashboard turned photos off.
    func photo(for m: Message) -> URL? {
        guard !m.isMine, appearance.teamPhotos != false else { return nil }
        if let path = m.authorAvatarUrl, let base = baseURL { return URL(string: path, relativeTo: base) }
        return teamPhoto
    }

    /// `Hailbell.teamPhoto`, when the dashboard allows it. The server decides who customers see, so a
    /// photo bundled in the app shows only before the chat has heard from Hailbell, or while Hailbell
    /// says this app shows the owner's Profile photo (or none at all). Never once the app signs replies
    /// as the team, turns photos off, or sets its own photo or "no photo" for this app.
    var bundledPhoto: UIImage? { bundledPhotoAllowed ? Hailbell.teamPhoto : nil }

    /// The bundled photo beside one reply: only on replies with no photo of their own, or with the
    /// header's photo (the same person), so another teammate never gets the bundled face.
    func bundledPhoto(for m: Message) -> UIImage? {
        guard !m.isMine, appearance.teamPhotos != false, let image = bundledPhoto else { return nil }
        if let path = m.authorAvatarUrl, path != team.first?.avatarUrl { return nil }
        return image
    }

    nonisolated static let bundledPhotoKey = "hailbell.bundledPhotoAllowed"

    nonisolated static func allowsBundledPhoto(appearance: Appearance, team: [TeamMember]) -> Bool {
        if appearance.agentIdentity == "team" || appearance.teamPhotos == false { return false }
        guard let first = team.first else { return true }
        return first.photo != false && (first.avatarUrl == nil || first.profilePhoto == true)
    }

    /// The first and last message of a run from the same sender, for names and photos.
    func startsRun(_ m: Message) -> Bool {
        guard let i = messages.firstIndex(where: { $0.id == m.id }), i > 0 else { return true }
        return !Self.sameSender(messages[i - 1], m)
    }

    /// Online while the team was active in the last 5 minutes.
    func agentOnline(at now: Date) -> Bool? {
        agentActiveAt.map { now.timeIntervalSince1970 * 1000 - $0 < 5 * 60 * 1000 }
    }

    func endsRun(_ m: Message) -> Bool {
        guard let i = messages.firstIndex(where: { $0.id == m.id }), i + 1 < messages.count else { return true }
        return !Self.sameSender(m, messages[i + 1])
    }

    private static func sameSender(_ a: Message, _ b: Message) -> Bool {
        a.author == b.author && (a.isMine || a.authorName == b.authorName)
    }

    func sessionStarted(_ session: Session) {
        appName = session.appName
        assistant = session.assistant
        appearance = session.appearance ?? Appearance()
        team = session.team ?? []
        bundledPhotoAllowed = Self.allowsBundledPhoto(appearance: appearance, team: team)
        UserDefaults.standard.set(bundledPhotoAllowed, forKey: Self.bundledPhotoKey)
        agentActiveAt = session.agentActiveAt
        errorText = nil
        if conversationId != session.conversationId {
            // A different person (signed in or out): show their chat, starting from the cached copy.
            disconnect()
            conversationId = session.conversationId
            messages = Self.cached(session.conversationId)
            hasMore = false
            agentReadAt = 0
            agentDeliveredAt = 0
        }
        saveSnapshot()
        // The latest page, not just what is newer: cached cards may have changed state since.
        // The socket only runs while the chat is on screen, so replies at other times arrive as pushes.
        Task { await loadHistory(latest: true); if visible { connect() } }
    }

    func failed(_ error: Error) {
        errorText = error.localizedDescription
    }

    func reset() {
        if let id = conversationId { Self.clearCache(id) }
        if let url = Self.snapshotURL { try? FileManager.default.removeItem(at: url) }
        conversationId = nil
        messages = []
        pending = []
        hasMore = false
        agentReadAt = 0
        agentDeliveredAt = 0
        disconnect()
    }

    /// Loads the page of messages before the oldest one shown.
    func loadEarlier() {
        guard let client = owner?.client, let first = messages.first else { return }
        Task {
            do {
                let page = try await client.messages(before: first.createdAt)
                for m in page.messages { insert(m) }
                hasMore = page.hasMore ?? false
            } catch {
                errorText = error.localizedDescription
            }
        }
    }

    /// Tells you in the dashboard that the customer is typing, at most every 3 seconds.
    func typing() {
        guard let socket, Date().timeIntervalSince(typingSentAt) > 3 else { return }
        typingSentAt = Date()
        socket.send(.string(#"{"type":"typing"}"#)) { _ in }
    }

    // MARK: offline copy, so the chat opens instantly and shows history without a connection

    private static func cacheURL(_ conversationId: String) -> URL? {
        let safe = conversationId.filter { $0.isLetter || $0.isNumber || $0 == "_" }
        return FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
            .appendingPathComponent("hailbell-\(safe).json")
    }

    private static func cached(_ conversationId: String) -> [Message] {
        guard let url = cacheURL(conversationId), let data = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([Message].self, from: data)) ?? []
    }

    private static func clearCache(_ conversationId: String) {
        if let url = cacheURL(conversationId) { try? FileManager.default.removeItem(at: url) }
    }

    /// What the last session said about the chat, so it opens with its name and history while offline.
    private struct Snapshot: Codable {
        let conversationId: String
        let appName: String
        let assistant: Assistant?
        let appearance: Appearance
        let team: [TeamMember]
    }

    private static var snapshotURL: URL? {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("hailbell-session.json")
    }

    private func saveSnapshot() {
        guard let id = conversationId, let url = Self.snapshotURL else { return }
        let snap = Snapshot(conversationId: id, appName: appName, assistant: assistant, appearance: appearance, team: team)
        if let data = try? JSONEncoder().encode(snap) { try? data.write(to: url, options: [.atomic, .completeFileProtection]) }
    }

    private func restore() {
        guard conversationId == nil, let url = Self.snapshotURL, let data = try? Data(contentsOf: url),
              let snap = try? JSONDecoder().decode(Snapshot.self, from: data) else { return }
        conversationId = snap.conversationId
        appName = snap.appName
        assistant = snap.assistant
        appearance = snap.appearance
        team = snap.team
        messages = Self.cached(snap.conversationId)
    }

    private func saveCache() {
        guard let id = conversationId, let url = Self.cacheURL(id),
              let data = try? JSONEncoder().encode(Array(messages.suffix(100))) else { return }
        try? data.write(to: url, options: [.atomic, .completeFileProtection])
    }

    // MARK: visibility, used for read receipts

    func appeared() {
        visible = true
        markRead()
        // Replies that arrived while the chat was closed came as pushes, not over the socket, so
        // fetch them before reconnecting.
        if owner?.client?.token == nil { owner?.reconnectNow() } else if socket == nil { Task { await loadHistory(); connect() } }
    }

    func disappeared() {
        visible = false
        disconnect()
    }

    /// The app went to the background. Close the socket so the server stops counting the customer as
    /// in the chat and sends replies as pushes. iOS suspends the app without closing it otherwise.
    func backgrounded() { disconnect() }

    /// The chat is on screen, in the sheet `Hailbell.open()` presents or wherever the app placed
    /// `HailbellChatView`. Stays true while the app is in the background with the chat open.
    var isOnScreen: Bool { visible }

    /// Fetches what arrived while the chat wasn't listening, reconnects and marks it read.
    func catchUp() {
        guard visible else { return }
        Task { await loadHistory(); connect(); markRead() }
    }

    /// Back in front with the chat on screen: catch up on what arrived meanwhile, then reconnect.
    func foregrounded() { catchUp() }

    private func markRead() {
        guard visible, let client = owner?.client else { return }
        owner?.setUnread(0)
        Task { try? await client.markRead() }
    }

    // MARK: sending

    func send(_ raw: String, images: [UIImage] = []) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !images.isEmpty else { return }
        let item = Pending(id: UUID().uuidString, body: text, images: images)
        pending.append(item)
        deliver(item)
    }

    func retry(_ item: Pending) {
        guard let i = pending.firstIndex(of: item) else { return }
        pending[i].failed = false
        deliver(pending[i])
    }

    private func deliver(_ item: Pending) {
        guard let client = owner?.client else { return }
        let token = client.token
        Task {
            do {
                var ids: [String] = []
                for image in item.images {
                    guard let jpeg = image.chatJPEG() else { continue }
                    ids.append(try await client.upload(jpeg: jpeg.data, width: jpeg.width, height: jpeg.height))
                }
                let message = try await client.send(item.body, attachments: ids, clientId: item.id)
                pending.removeAll { $0.id == item.id }
                insert(message)
                saveCache()
                errorText = nil
                notifyAsk.customerSent()
            } catch {
                if let i = pending.firstIndex(where: { $0.id == item.id }) { pending[i].failed = true }
                showError(error, token: token)
            }
        }
    }

    /// The customer tapped a card's button ("tapped"), chose "Not now" ("dismissed") or bought from an
    /// offer ("purchased"). States only move forward.
    func cardAction(_ message: Message, _ action: String) {
        let rank = ["sent": 0, "dismissed": 1, "tapped": 2, "purchased": 3]
        guard let i = messages.firstIndex(where: { $0.id == message.id }), let card = messages[i].card,
              (rank[action] ?? 0) > (rank[card.state] ?? 0) else { return }
        messages[i].card?.state = action
        saveCache()
        guard let client = owner?.client else { return }
        Task { try? await client.cardAction(messageId: message.id, action: action) }
    }

    /// Adds a message, or updates the copy we have (a card's state can change after it was cached).
    private func insert(_ message: Message) {
        if let i = messages.firstIndex(where: { $0.id == message.id }) {
            if messages[i] != message { messages[i] = message }
            return
        }
        messages.append(message)
        messages.sort { $0.createdAt < $1.createdAt }
        if let cid = message.clientId { pending.removeAll { $0.id == cid } }
    }

    /// What arrived since the newest message we have, or with `latest` the newest page. A newest page
    /// that does not reach back to our copy replaces it, so the chat never shows a gap.
    private func loadHistory(latest: Bool = false) async {
        guard let client = owner?.client else { return }
        let token = client.token
        do {
            let page = try await client.messages(after: latest ? 0 : messages.last?.createdAt ?? 0)
            if latest, page.hasMore == true, let oldest = page.messages.first?.createdAt, oldest > messages.last?.createdAt ?? 0 {
                messages = []
            }
            if messages.isEmpty || latest { hasMore = page.hasMore ?? false }
            if let read = page.agentReadAt { agentReadAt = read }
            if let delivered = page.agentDeliveredAt { agentDeliveredAt = delivered }
            for m in page.messages { insert(m) }
            saveCache()
            errorText = nil
        } catch {
            showError(error, token: token)
        }
    }

    /// A request made with a session that has since been replaced (signing in moves the anonymous chat
    /// into the account's and deletes it) fails with "Conversation not found". That error belongs to the
    /// old session, so it is not shown over the new one.
    private func showError(_ error: Error, token: String?) {
        if owner?.client?.token == token { errorText = error.localizedDescription }
    }

    // MARK: live socket

    private func connect() {
        guard socket == nil, let url = owner?.client?.socketURL() else { return }
        let task = URLSession.shared.webSocketTask(with: url)
        socket = task
        task.resume()
        connected = true
        receive(on: task)
        pingTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                try? await task.send(.string("ping"))
            }
        }
    }

    private func disconnect() {
        pingTask?.cancel()
        // Tells the server the customer left, so a reply that comes in now goes out as a push at once
        // instead of waiting for the server to notice the socket is gone.
        // Close once the frame is written, so the cancel can't drop it.
        if let task = socket { task.send(.string(#"{"type":"bye"}"#)) { _ in task.cancel(with: .goingAway, reason: nil) } }
        socket = nil
        connected = false
    }

    private func receive(on task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            Task { @MainActor in
                guard let self, self.socket === task else { return }
                switch result {
                case .success(.string(let text)):
                    self.handle(text)
                    self.receive(on: task)
                case .success:
                    self.receive(on: task)
                case .failure:
                    self.disconnect()
                    // Reconnect and catch up on anything missed while offline.
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    guard self.visible else { return }
                    await self.loadHistory()
                    self.connect()
                }
            }
        }
    }

    private func handle(_ text: String) {
        guard text != "pong", let data = text.data(using: .utf8) else { return }
        struct Event: Decodable { let type: String; let message: Message?; let id: String?; let card: Card?; let at: Double?; let activeAt: Double? }
        guard let event = try? JSONDecoder().decode(Event.self, from: data) else { return }
        switch event.type {
        case "card":
            if let card = event.card, let i = messages.firstIndex(where: { $0.id == event.id }) { messages[i].card = card; saveCache() }
            return
        case "read":
            if let at = event.at { agentReadAt = at }
            return
        case "delivered":
            if let at = event.at { agentDeliveredAt = max(agentDeliveredAt, at) }
            return
        case "agent_presence":
            agentActiveAt = event.activeAt
            return
        case "typing":
            agentTyping = true
            typingClear?.cancel()
            typingClear = Task {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                if !Task.isCancelled { agentTyping = false }
            }
            return
        default:
            break
        }
        guard event.type == "message", let message = event.message else { return }
        insert(message)
        saveCache()
        if !message.isMine { agentTyping = false }
        if !message.isMine {
            if visible { markRead() } else { owner?.setUnread((owner?.unreadCount ?? 0) + 1) }
        }
    }
}

extension UIImage {
    /// A JPEG under the server's 1.5 MB limit: at most 1600 points on the long side, stepping the
    /// quality down until it fits.
    func chatJPEG() -> (data: Data, width: Int, height: Int)? {
        let longest = max(size.width, size.height)
        let scale = longest > 1600 ? 1600 / longest : 1
        let target = CGSize(width: (size.width * scale).rounded(), height: (size.height * scale).rounded())
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let resized = UIGraphicsImageRenderer(size: target, format: format).image { _ in draw(in: CGRect(origin: .zero, size: target)) }
        for quality in [0.8, 0.6, 0.4] as [CGFloat] {
            if let data = resized.jpegData(compressionQuality: quality), data.count < 1_400_000 {
                return (data, Int(target.width), Int(target.height))
            }
        }
        return nil
    }
}
