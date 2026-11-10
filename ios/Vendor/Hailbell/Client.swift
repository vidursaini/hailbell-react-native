import Foundation
import Security

struct Session: Decodable {
    let token: String
    let conversationId: String
    let unread: Int
    let appName: String
    let assistant: Assistant?
    let appearance: Appearance?
    let team: [TeamMember]?
    let agentActiveAt: Double?
}

/// Set when an AI may answer before a person does, so the chat can say so (App Store guideline 5.1.2(i)).
struct Assistant: Codable, Equatable {
    let name: String
    let provider: String
}

/// The chat design published from the dashboard. Anything unset falls back to the built-in look.
struct Appearance: Codable, Equatable {
    struct Palette: Codable, Equatable {
        let accent: String?
        let onAccent: String?
        let background: String?
        let incoming: String?
        let incomingText: String?
    }

    var title: String?
    var subtitle: String?
    var attachments: Bool?
    var welcomeTitle: String?
    var welcomeText: String?
    var placeholder: String?
    var agentNames: Bool?
    var teamPhotos: Bool?
    /// "team" when every reply is signed with one team name and no personal photo, else "person".
    var agentIdentity: String?
    var avatarUrl: String?
    /// Quick topics on an empty chat. Nil means the built-in ones, an empty list turns them off.
    var topics: [String]?
    var light: Palette?
    var dark: Palette?
}

/// The person who answers, as customers see them in this app. `avatarUrl` is relative to the API's
/// base URL. `photo` is false when no personal photo may show in this app (replies signed as the team,
/// photos off, or the person chose no photo here), so a photo bundled in the app stays hidden too.
/// `profilePhoto` is true when `avatarUrl` is their Profile photo (no photo of its own for this app),
/// so a photo bundled in the app, of the same person, may stand in for it.
struct TeamMember: Codable, Equatable {
    let name: String
    let avatarUrl: String?
    var photo: Bool? = nil
    var profilePhoto: Bool? = nil
}

public struct Message: Codable, Identifiable, Equatable {
    public let id: String
    public let author: String          // "user" | "agent" | "bot" (the AI) | "system"
    public let authorName: String?
    /// The photo of the teammate who wrote this reply, in this app. Relative to the API's base URL.
    public let authorAvatarUrl: String?
    public let body: String
    public let attachments: [Attachment]
    public let clientId: String?
    public let createdAt: Double       // ms since 1970
    public var card: Card?
    /// Set on a bug report the customer sent from the report screen: their app, device and last screens.
    public let report: ReportDetails?

    var isMine: Bool { author == "user" }
    var date: Date { Date(timeIntervalSince1970: createdAt / 1000) }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        author = try c.decode(String.self, forKey: .author)
        authorName = try c.decodeIfPresent(String.self, forKey: .authorName)
        authorAvatarUrl = try c.decodeIfPresent(String.self, forKey: .authorAvatarUrl)
        body = try c.decodeIfPresent(String.self, forKey: .body) ?? ""   // empty for image-only messages
        attachments = try c.decodeIfPresent([Attachment].self, forKey: .attachments) ?? []
        clientId = try c.decodeIfPresent(String.self, forKey: .clientId)
        createdAt = try c.decode(Double.self, forKey: .createdAt)
        card = try c.decodeIfPresent(Card.self, forKey: .card)
        report = try c.decodeIfPresent(ReportDetails.self, forKey: .report)
    }
}

/// What a bug report says about where it came from. Every field is optional, since the server may
/// leave any of them out.
public struct ReportDetails: Codable, Equatable {
    public let appVersion: String?
    public let build: String?
    /// The model code, e.g. "iPhone17,2".
    public let device: String?
    /// The model's name, e.g. "iPhone 16 Pro Max", when the server knows it.
    public let deviceName: String?
    public let os: String?
    public let locale: String?
    /// The last screens the customer saw, oldest first.
    public let screens: [String]?
}

/// An image in a message. `url` is relative to the API's base URL.
public struct Attachment: Codable, Equatable {
    public let id: String
    public let url: String
    public let width: Double?
    public let height: Double?
}

/// A message from you with a button: a review request (opens `url`) or an offer (opens `placement`).
public struct Card: Codable, Equatable {
    public let type: String            // "review" | "offer"
    public let button: String
    public let url: String?
    public let placement: String?
    public var state: String           // "sent" | "dismissed" | "tapped" | "purchased"
}

struct APIError: LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { message }
}

final class Client {
    let appId: String
    let baseURL: URL
    var token: String?

    init(appId: String, baseURL: URL) {
        self.appId = appId
        self.baseURL = baseURL
    }

    func startSession(identity: Identity) async throws -> Session {
        var body: [String: Any] = ["appId": appId, "anonymousId": Self.installId, "metadata": deviceMetadata.merging(identity.metadata) { _, new in new }, "timeZone": TimeZone.current.identifier]
        if let v = identity.userId { body["userId"] = v }
        if let v = identity.name { body["name"] = v }
        if let v = identity.email { body["email"] = v }
        if let v = identity.identityToken { body["identityToken"] = v }
        // A fetch that fails (offline, Firebase busy) must not fall back to the anonymous chat: nothing
        // would sign them in again until the next launch, so the error fails the session and it is
        // retried with backoff. No token at all means they signed out: the install's anonymous chat.
        if let fetch = identity.firebaseIdToken, let v = try await fetch() { body["firebaseIdToken"] = v }
        // The caller stores session.token: only the newest of several overlapping sessions may.
        return try await request("POST", "/v1/session", body: body, authorized: false)
    }

    struct Page: Decodable {
        let messages: [Message]
        let hasMore: Bool?
        let agentReadAt: Double?
        let agentDeliveredAt: Double?
    }

    /// With `after`, what arrived since then; otherwise the latest page, or the page before `before`.
    func messages(after: Double = 0, before: Double? = nil) async throws -> Page {
        if after > 0 { return try await request("GET", "/v1/messages?after=\(Int(after))") }
        return try await request("GET", before.map { "/v1/messages?before=\(Int($0))" } ?? "/v1/messages")
    }

    func deleteData() async throws {
        let _: Empty = try await request("DELETE", "/v1/me")
    }

    /// Posts a message. `report` turns it into a bug report (see `Hailbell.reportDetails`); the text may
    /// then be empty.
    func send(_ text: String, attachments: [String] = [], clientId: String, report: [String: Any]? = nil) async throws -> Message {
        struct Out: Decodable { let message: Message }
        var body: [String: Any] = ["text": text, "attachments": attachments, "clientId": clientId]
        if let report { body["report"] = report }
        let out: Out = try await request("POST", "/v1/messages", body: body)
        return out.message
    }

    /// Uploads one JPEG and returns its id, which a following `send` attaches.
    func upload(jpeg: Data, width: Int, height: Int) async throws -> String {
        guard let url = URL(string: "/v1/attachments", relativeTo: baseURL), let token else {
            throw APIError(status: 401, message: "Chat is still connecting")
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 60
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")
        req.setValue("photo.jpg", forHTTPHeaderField: "X-Filename")
        req.setValue(String(width), forHTTPHeaderField: "X-Width")
        req.setValue(String(height), forHTTPHeaderField: "X-Height")
        let (data, response) = try await URLSession.shared.upload(for: req, from: jpeg)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw APIError(status: status, message: message ?? "Upload failed (\(status))")
        }
        struct Out: Decodable { let attachment: Attachment }
        return try JSONDecoder().decode(Out.self, from: data).attachment.id
    }

    func cardAction(messageId: String, action: String) async throws {
        let _: Empty = try await request("POST", "/v1/cards/\(messageId)", body: ["action": action])
    }

    func reportPlacements(_ placements: [String]) async throws {
        let _: Empty = try await request("POST", "/v1/placements", body: ["placements": placements])
    }

    func markRead() async throws {
        let _: Empty = try await request("POST", "/v1/read", body: [:])
    }

    func track(_ name: String, properties: [String: String]) async throws {
        let _: Empty = try await request("POST", "/v1/events", body: ["name": name, "props": properties])
    }

    func registerPush(token push: String, sandbox: Bool) async throws {
        let _: Empty = try await request("POST", "/v1/push-token", body: ["token": push, "platform": "apns", "environment": sandbox ? "sandbox" : "production"])
    }

    func socketURL() -> URL? {
        guard let token, var parts = URLComponents(url: baseURL.appendingPathComponent("v1/socket"), resolvingAgainstBaseURL: false) else { return nil }
        parts.scheme = parts.scheme == "http" ? "ws" : "wss"
        parts.queryItems = [URLQueryItem(name: "token", value: token)]
        return parts.url
    }

    private struct Empty: Decodable {}

    func request<T: Decodable>(_ method: String, _ path: String, body: [String: Any]? = nil, authorized: Bool = true) async throws -> T {
        guard let url = URL(string: path, relativeTo: baseURL) else { throw APIError(status: 0, message: "Bad URL") }
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.timeoutInterval = 20
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if authorized {
            guard let token else { throw APIError(status: 401, message: "Chat is still connecting") }
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body { req.httpBody = try JSONSerialization.data(withJSONObject: body) }
        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw APIError(status: status, message: message ?? "Request failed (\(status))")
        }
        return try JSONDecoder().decode(T.self, from: data)
    }

    private var deviceMetadata: [String: String] {
        let info = Bundle.main.infoDictionary ?? [:]
        return [
            "appVersion": "\(info["CFBundleShortVersionString"] ?? "?") (\(info["CFBundleVersion"] ?? "?"))",
            "os": "iOS \(ProcessInfo.processInfo.operatingSystemVersionString)",
            "locale": Locale.current.identifier,
            "device": HailbellPush.model,
        ]
    }

    /// A random id for this install, kept in the Keychain so the chat survives app reinstalls. Read once:
    /// a `static let` runs exactly once even when two sessions start at the same moment on a fresh
    /// install, which used to mint two ids and split the person into two contacts.
    static let installId: String = {
        let account = "hailbell.install-id"
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: account, kSecReturnData as String: true]
        var item: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data, let id = String(data: data, encoding: .utf8) {
            return id
        }
        let id = UUID().uuidString + "-" + UUID().uuidString
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrAccount as String: account,
            kSecValueData as String: Data(id.utf8), kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemAdd(add as CFDictionary, nil)
        return id
    }()
}
