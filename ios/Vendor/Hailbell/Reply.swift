import SwiftUI
import UIKit

/// The message being answered, as the reply bar above the field shows it.
struct ReplyBarItem: Equatable {
    let name: String
    let quote: ReplyTo
}

/// The quoted message at the top of a reply: a thin bar, who wrote it, and up to two lines of it (or
/// "Photo"). Inside the customer's own accent bubble it takes the bubble's text colour. Tapping it
/// jumps to the original when it is loaded.
struct QuoteBlock: View {
    let name: String
    let quote: ReplyTo
    /// Drawn inside the customer's accent bubble.
    let onAccent: Bool
    let theme: ChatTheme
    /// Drawn on its own, above images or a card, rather than inside a text bubble.
    var standalone = false
    var onTap: (() -> Void)?

    var body: some View {
        Button { onTap?() } label: {
            HStack(spacing: 8) {
                Capsule().fill(barColor).frame(width: 3)
                VStack(alignment: .leading, spacing: 1) {
                    Text(name).font(.caption.weight(.semibold)).foregroundStyle(nameColor).lineLimit(1)
                    QuoteSnippet(quote: quote, lines: 2).foregroundStyle(snippetColor)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.vertical, 6)
            .padding(.leading, 6)
            .padding(.trailing, 10)
            .background(fill, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }

    private var barColor: Color { onAccent ? theme.onAccent : theme.accent }
    private var nameColor: Color { onAccent ? theme.onAccent : theme.accent }
    private var snippetColor: Color { onAccent ? theme.onAccent.opacity(0.85) : theme.incomingText.opacity(0.7) }
    private var fill: Color {
        if standalone { return theme.incoming }
        return onAccent ? theme.onAccent.opacity(0.16) : theme.accent.opacity(0.1)
    }
}

/// One or two lines of a quote, or a photo icon with "Photo" when it was only images.
struct QuoteSnippet: View {
    let quote: ReplyTo
    let lines: Int

    var body: some View {
        if let text = quote.snippet {
            Text(text).font(.footnote).lineLimit(lines)
        } else if quote.isPhoto {
            Label(L10n.text("photo"), systemImage: "photo").font(.footnote).lineLimit(1)
        }
    }
}

/// Above the field while answering a message: "Replying to {name}", a line of it, and × to cancel.
struct ReplyBar: View {
    let item: ReplyBarItem
    let theme: ChatTheme
    let onCancel: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Capsule().fill(theme.accent).frame(width: 3)
            VStack(alignment: .leading, spacing: 2) {
                Text(L10n.text("replyingTo", ["name": item.name]))
                    .font(.footnote.weight(.semibold)).foregroundStyle(theme.accent).lineLimit(1)
                QuoteSnippet(quote: item.quote, lines: 1).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            Button(action: onCancel) {
                Image(systemName: "xmark").font(.system(size: 11, weight: .bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 24, height: 24)
                    .background(Color.primary.opacity(0.08), in: Circle())
                    .frame(width: 44, height: 40)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.text("cancelReply"))
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.leading, 10)
        .padding(.vertical, 6)
        .background(theme.incoming, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// Lays children out top to bottom at the width of the widest one, so a quote and the text under it
/// in a bubble share one width without stretching the bubble across the screen.
struct BubbleStack: Layout {
    var spacing: CGFloat = 4

    private func width(_ proposal: ProposedViewSize, _ subviews: Subviews) -> CGFloat {
        subviews.map { s -> CGFloat in
            let ideal = s.sizeThatFits(.unspecified).width
            guard let limit = proposal.width else { return ideal }
            return min(ideal, s.sizeThatFits(ProposedViewSize(width: limit, height: nil)).width)
        }.max() ?? 0
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = width(proposal, subviews)
        let heights = subviews.map { $0.sizeThatFits(ProposedViewSize(width: w, height: nil)).height }
        return CGSize(width: w, height: heights.reduce(0, +) + spacing * CGFloat(max(subviews.count - 1, 0)))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for s in subviews {
            let h = s.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil)).height
            s.place(at: CGPoint(x: bounds.minX, y: y), proposal: ProposedViewSize(width: bounds.width, height: h))
            y += h + spacing
        }
    }
}

/// Swipe a message to the right to answer it, as in Messages and WhatsApp. The message follows the
/// finger with resistance, a reply arrow fades in behind it, one light tap is felt when the drag goes
/// far enough, and letting go springs it back (answering only if it went far enough). Only clearly
/// sideways drags count, so the chat still scrolls, and drags from the screen's edge are left to the
/// system's back swipe.
struct SwipeToReply: ViewModifier {
    let enabled: Bool
    let theme: ChatTheme
    /// The message's text, offered as Copy in the menu, since the menu takes the long press that
    /// would otherwise select it.
    var copyText: String? = nil
    let onReply: () -> Void

    @State private var offset: CGFloat = 0
    @State private var horizontal: Bool?
    @State private var armed = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    static let threshold: CGFloat = 60
    private static let edge: CGFloat = 24

    func body(content: Content) -> some View {
        if enabled {
            content
                .offset(x: offset)
                .background(alignment: .leading) { arrow }
                .simultaneousGesture(drag)
                .contextMenu {
                    Button(action: onReply) { Label(L10n.text("reply"), systemImage: "arrowshape.turn.up.left") }
                    if let copyText, !copyText.isEmpty {
                        Button { UIPasteboard.general.string = copyText } label: { Label(Self.copyTitle, systemImage: "doc.on.doc") }
                    }
                }
                .accessibilityAction(named: Text(L10n.text("reply")), onReply)
        } else {
            content
        }
    }

    private var progress: CGFloat { min(offset / Self.threshold, 1) }

    private var arrow: some View {
        Image(systemName: "arrowshape.turn.up.left.fill")
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(armed ? theme.onAccent : Color.secondary)
            .frame(width: 30, height: 30)
            .background(armed ? theme.accent : Color.primary.opacity(0.08), in: Circle())
            .scaleEffect(reduceMotion ? 1 : 0.5 + 0.5 * progress)
            .opacity(Double(progress))
            // Slides in with the message, then waits at its spot while the message keeps going.
            .offset(x: min(offset, Self.threshold) - 40)
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: armed)
            .accessibilityHidden(true)
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .global)
            .onChanged { value in
                if horizontal == nil {
                    let dx = value.translation.width, dy = value.translation.height
                    horizontal = dx > 0 && dx > abs(dy) * 1.6 && value.startLocation.x > Self.edge
                    if horizontal == true { Self.haptic.prepare() }
                }
                guard horizontal == true else { return }
                let next = Self.resist(value.translation.width)
                let isArmed = next >= Self.threshold
                if isArmed && !armed { Self.haptic.impactOccurred() }
                armed = isArmed
                offset = next
            }
            .onEnded { _ in
                defer { horizontal = nil }
                guard horizontal == true else { return }
                if armed { onReply() }
                armed = false
                withAnimation(reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.32, dampingFraction: 0.78)) { offset = 0 }
            }
    }

    private static let haptic = UIImpactFeedbackGenerator(style: .light)

    /// "Copy" in the system's language, from UIKit's own strings.
    private static let copyTitle = Bundle(for: UIView.self).localizedString(forKey: "Copy", value: "Copy", table: nil)

    /// Follows the finger at first, then gives less and less past the threshold.
    static func resist(_ x: CGFloat) -> CGFloat {
        guard x > 0 else { return 0 }
        let near = min(x, threshold * 1.1) * 0.9
        let far = max(0, x - threshold * 1.1)
        return near + 40 * (1 - exp(-far / 80))
    }
}

extension View {
    func swipeToReply(enabled: Bool, theme: ChatTheme, copyText: String? = nil, onReply: @escaping () -> Void) -> some View {
        modifier(SwipeToReply(enabled: enabled, theme: theme, copyText: copyText, onReply: onReply))
    }
}
