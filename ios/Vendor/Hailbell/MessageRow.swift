import SwiftUI

/// One message: the sender's name over the first of a run, then its images and text. Replies from a
/// person carry their photo beside the last message of a run, when the dashboard shows team photos.
struct MessageRow: View {
    let message: Message
    let caption: String?
    let photo: URL?
    let bundledPhoto: UIImage?
    let showsFace: Bool
    let baseURL: URL?
    let theme: ChatTheme
    /// Who wrote the message this one quotes, already named for the customer ("You", a teammate…).
    var quoteName: String? = nil
    /// Whether it can be swiped (or long-pressed) to answer it.
    var canReply = false
    var onReply: () -> Void = {}
    /// Tapped the quote: jump to the original.
    var onQuote: (String) -> Void = { _ in }
    var onCard: (String) -> Void = { _ in }

    var body: some View {
        let mine = message.isMine
        VStack(alignment: mine ? .trailing : .leading, spacing: 4) {
            if let caption {
                Text(caption).font(.caption2).foregroundStyle(.secondary)
                    .padding(.leading, hasFace ? 42 : 6)
            }
            HStack(alignment: .bottom, spacing: 8) {
                if message.author == "bot" {
                    AIAvatar(size: 28, theme: theme).opacity(showsFace ? 1 : 0)
                } else if !mine, hasFace {
                    Avatar(image: bundledPhoto, url: photo, size: 28, theme: theme).opacity(showsFace ? 1 : 0)
                }
                VStack(alignment: mine ? .trailing : .leading, spacing: 4) {
                    if let quote, quoteStandsAlone {
                        // As wide as the quote needs, up to the width of a photo.
                        BubbleStack {
                            QuoteBlock(name: quote.name, quote: quote.reply, onAccent: false, theme: theme, standalone: true) { onQuote(quote.reply.id) }
                        }
                        .frame(maxWidth: 260, alignment: mine ? .trailing : .leading)
                    }
                    ForEach(Array(message.attachments.enumerated()), id: \.offset) { _, a in
                        if let base = baseURL, let url = URL(string: a.url, relativeTo: base) { Photo(url: url) }
                    }
                    if let card = message.card, card.isShown {
                        CardBubble(text: message.body, card: card, theme: theme, act: onCard)
                    } else if !message.body.isEmpty {
                        Bubble(text: message.body, mine: mine, theme: theme, quote: quote) { onQuote($0) }
                    }
                    if mine, message.report != nil { BugReportTag() }
                }
            }
            .swipeToReply(enabled: canReply, theme: theme, copyText: message.body, onReply: onReply)
        }
        .frame(maxWidth: Self.maxWidth, alignment: mine ? .trailing : .leading)
        .frame(maxWidth: .infinity, alignment: mine ? .trailing : .leading)
        .padding(mine ? .leading : .trailing, 48)
    }

    private var quote: (name: String, reply: ReplyTo)? {
        guard let reply = message.replyTo else { return nil }
        return (quoteName ?? "", reply)
    }

    /// Image-only messages and cards show the quote above them rather than inside a text bubble.
    private var quoteStandsAlone: Bool {
        message.body.isEmpty || message.card?.isShown == true
    }

    /// AI replies carry the sparkles mark; yours carry your photo when there is one to show.
    private var hasFace: Bool { message.author == "bot" || bundledPhoto != nil || photo != nil }

    /// Keeps lines readable on wide screens (an open iPhone Duo, iPad, landscape).
    static let maxWidth: CGFloat = 520
}

/// The AI's mark beside its replies: sparkles in an accent circle, so they never look like a person's.
struct AIAvatar: View {
    let size: CGFloat
    let theme: ChatTheme

    var body: some View {
        Image(systemName: "sparkles")
            .font(.system(size: size * 0.46, weight: .semibold))
            .foregroundStyle(theme.onAccent)
            .frame(width: size, height: size)
            .background(theme.accent, in: Circle())
            .accessibilityHidden(true)
    }
}

private struct Photo: View {
    let url: URL

    var body: some View {
        AsyncImage(url: url) { image in
            image.resizable().scaledToFit()
        } placeholder: {
            Color(.secondarySystemBackground)
        }
        .frame(maxWidth: 220, maxHeight: 280)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        // A faint edge so white images don't melt into a light sheet.
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.primary.opacity(0.1), lineWidth: 1))
    }
}

struct Bubble: View {
    let text: String
    let mine: Bool
    let theme: ChatTheme
    var sending = false
    var failed = false
    /// The message this one answers, shown at the top of the bubble.
    var quote: (name: String, reply: ReplyTo)? = nil
    var onQuote: (String) -> Void = { _ in }

    var body: some View {
        VStack(alignment: mine ? .trailing : .leading, spacing: 2) {
            content
                .foregroundStyle(mine ? theme.onAccent : theme.incomingText)
                .background(mine ? theme.accent : theme.incoming, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .opacity(sending ? 0.6 : 1)
            if failed { Text(L10n.text("notSent")).font(.caption2).foregroundStyle(.red).padding(.horizontal, 6) }
        }
    }

    @ViewBuilder private var content: some View {
        if let quote {
            BubbleStack(spacing: 4) {
                QuoteBlock(name: quote.name, quote: quote.reply, onAccent: mine, theme: theme) { onQuote(quote.reply.id) }
                Text(text).textSelection(.enabled).padding(.horizontal, 6).padding(.bottom, 2)
            }
            .padding(6)
        } else {
            Text(text)
                .textSelection(.enabled)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
        }
    }
}

/// Your message with its button inside the bubble, like an iMessage app card: the text, then a
/// full-width pill with a gift for an offer or a star for a review, and a chevron for where it goes.
/// "Not now" sits under the button until they choose.
private struct CardBubble: View {
    let text: String
    let card: Card
    let theme: ChatTheme
    let act: (String) -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !text.isEmpty {
                Text(text).textSelection(.enabled).foregroundStyle(theme.incomingText).padding(.horizontal, 4)
            }
            Button(action: tap) {
                HStack(spacing: 10) {
                    Image(systemName: card.type == "offer" ? "gift.fill" : "star.fill").font(.system(size: 17, weight: .semibold))
                    Text(card.button).font(.body.weight(.semibold)).lineLimit(2).multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                    Image(systemName: card.placement != nil ? "chevron.right" : "arrow.up.right").font(.system(size: 14, weight: .bold))
                }
                .padding(.horizontal, 18)
                .frame(minHeight: 48)
                .frame(maxWidth: .infinity)
                .foregroundStyle(theme.onAccent)
                .background(theme.accent, in: Capsule())
            }
            .buttonStyle(PressStyle())
            if card.state == "sent" {
                Button(L10n.text("notNow")) { act("dismissed") }
                    .font(.footnote).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(10)
        .padding(.top, text.isEmpty ? 0 : 4)
        .frame(minWidth: 250, alignment: .leading)
        .background(theme.incoming, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func tap() {
        act("tapped")
        if let placement = card.placement {
            Hailbell.onPaywall?(placement) { act("purchased") }
        } else if let url = card.url.flatMap(URL.init(string:)) {
            openURL(url)
        }
    }
}

/// A small press-in, like a system button.
private struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.scaleEffect(configuration.isPressed ? 0.97 : 1).animation(.easeOut(duration: 0.1), value: configuration.isPressed)
    }
}

/// Under an offer they bought: a short thank-you line.
struct CardStatus: View {
    let card: Card

    var body: some View {
        Label(L10n.text("thanks"), systemImage: "gift")
            .font(.caption).foregroundStyle(.secondary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 2)
    }
}

extension Card {
    /// Cards open a paywall placement or an https link; anything else shows as a plain message.
    var isShown: Bool { placement != nil || url.flatMap(URL.init(string:))?.scheme == "https" }
}

extension Color {
    /// A "#rrggbb" color from the dashboard.
    init?(hex: String?) {
        guard let hex, hex.count == 7, hex.hasPrefix("#"), let v = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        self.init(red: Double(v >> 16 & 0xff) / 255, green: Double(v >> 8 & 0xff) / 255, blue: Double(v & 0xff) / 255)
    }
}
