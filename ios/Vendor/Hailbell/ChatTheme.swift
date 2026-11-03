import SwiftUI

/// The chat's colors for the current light or dark mode, from the appearance set in the dashboard.
/// Anything unset falls back to the system's own colors, so the chat sits on the host app's surfaces.
struct ChatTheme {
    let accent: Color
    let onAccent: Color
    let background: Color
    let incoming: Color
    let incomingText: Color
    /// What the sheet and its bars are painted with: the solid background, so the sheet never changes look as it moves.
    let surface: Color
    /// True when the composer and buttons use Liquid Glass (iOS 26 and later).
    let glass: Bool

    init(_ appearance: Appearance, dark: Bool) {
        let p = dark ? appearance.dark : appearance.light
        accent = Color(hex: p?.accent) ?? .accentColor
        onAccent = Color(hex: p?.onAccent) ?? .white
        background = Color(hex: p?.background) ?? Color(.systemBackground)
        incoming = Color(hex: p?.incoming) ?? Color(.secondarySystemBackground)
        incomingText = Color(hex: p?.incomingText) ?? .primary
        if #available(iOS 26.0, *) { glass = true } else { glass = false }
        surface = background
    }
}

/// A round photo: the bundled one, else one from the server, else the bell.
struct Avatar: View {
    let image: UIImage?
    let url: URL?
    let size: CGFloat
    let theme: ChatTheme

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else if let url {
                AsyncImage(url: url) { $0.resizable().scaledToFill() } placeholder: { theme.incoming }
            } else {
                Image(systemName: "bubble.left.and.bubble.right.fill")
                    .font(.system(size: size * 0.42))
                    .foregroundStyle(theme.accent)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(theme.accent.opacity(0.14))
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }
}

/// A headset in a tinted circle of the accent colour: support, not a person or an AI.
struct SupportMark: View {
    let size: CGFloat
    let theme: ChatTheme

    /// "headset" arrived in iOS 18; earlier systems get headphones.
    private static let symbol = UIImage(systemName: "headset") != nil ? "headset" : "headphones"

    var body: some View {
        Image(systemName: Self.symbol)
            .font(.system(size: size * 0.45, weight: .semibold))
            .foregroundStyle(theme.onAccent)
            .frame(width: size, height: size)
            .background(theme.accent, in: Circle())
            .accessibilityHidden(true)
    }
}

/// "Today", "Yesterday" or the date, between messages from different days.
struct DayChip: View {
    let date: Date
    let theme: ChatTheme

    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.doesRelativeDateFormatting = true
        f.dateStyle = .medium
        f.timeStyle = .none
        return f
    }()

    var body: some View {
        Text(Self.formatter.string(from: date))
            .font(.caption.weight(.medium))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(theme.incoming, in: Capsule())
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
    }
}
