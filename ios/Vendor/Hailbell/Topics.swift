import SwiftUI

/// Common reasons to write, shown on an empty chat. Tapping one sends it as the customer's first
/// message, so they start faster and the team (or the AI) knows the topic before reading on. The
/// dashboard sets them under Chat design: none set means these defaults, an empty list turns them off.
struct TopicChips: View {
    let topics: [String]
    let theme: ChatTheme
    let onTap: (String) -> Void

    static func resolve(_ custom: [String]?) -> [String] {
        let list = custom ?? L10n.text("topics").components(separatedBy: "|")
        return Array(list.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.prefix(4))
    }

    var body: some View {
        VStack(alignment: .trailing, spacing: 8) {
            ForEach(topics, id: \.self) { topic in
                Button { onTap(topic) } label: {
                    Text(topic)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(theme.accent)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .overlay(Capsule().strokeBorder(theme.accent.opacity(0.5), lineWidth: 1))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.bottom, 8)
    }
}
