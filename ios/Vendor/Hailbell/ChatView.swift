import SwiftUI

struct ChatView: View {
    @ObservedObject var model: ChatModel
    @State private var composing = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let theme = ChatTheme(model.appearance, dark: scheme == .dark)
        VStack(spacing: 0) {
            header(theme)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        if model.messages.isEmpty && model.pending.isEmpty {
                            intro(theme)
                            TopicChips(topics: TopicChips.resolve(model.appearance.topics), theme: theme) { model.send($0) }
                        }
                        if model.hasMore {
                            Button(L10n.text("earlier")) { model.loadEarlier() }
                                .font(.footnote)
                                .padding(.bottom, 8)
                        }
                        ForEach(model.messages) { m in
                            if startsDay(m) { DayChip(date: m.date, theme: theme) }
                            MessageRow(message: m, caption: caption(m), photo: m.isMine ? nil : model.teamPhoto,
                                       bundledPhoto: m.isMine ? nil : Hailbell.teamPhoto,
                                       showsFace: (m.author == "agent" || m.author == "bot") && model.endsRun(m), baseURL: model.baseURL,
                                       theme: theme) { model.cardAction(m, $0) }
                                .id(m.id)
                            if let delivery = model.delivery(of: m) { Ticks(delivery: delivery) }
                            // Thanks only for a real purchase; a tap may have closed the paywall, and iOS never says
                            // whether a review was left.
                            if let card = m.card, card.isShown, card.state == "purchased" {
                                CardStatus(card: card)
                            }
                        }
                        if model.agentTyping {
                            Text(L10n.text("typing")).font(.footnote).foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 6)
                        }
                        ForEach(model.pending) { p in
                            PendingRow(item: p, theme: theme)
                                .id(p.id)
                                .onTapGesture { if p.failed { model.retry(p) } }
                        }
                        NotifyAskRow(ask: model.notifyAsk, theme: theme)
                        Color.clear.frame(height: 1).id(Self.bottom)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 12)
                }
                .scrollDismissesKeyboard(.interactively)
                // New messages, a reply arriving, "Typing…" or a sent message: keep the newest in view.
                .onChange(of: tail) { _ in
                    withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
                }
                .onChange(of: composing) { focused in
                    guard focused else { return }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
                }
                .onAppear {
                    // Twice: the lazy list only knows its full height after the first layout pass.
                    DispatchQueue.main.async { proxy.scrollTo(Self.bottom, anchor: .bottom) }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { proxy.scrollTo(Self.bottom, anchor: .bottom) }
                }
                // The composer floats over the list, so messages scroll under the glass, as in Messages.
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    VStack(spacing: 4) {
                        if let error = model.errorText {
                            Text(error).font(.footnote).foregroundStyle(.secondary).padding(.horizontal)
                        }
                        Composer(placeholder: model.appearance.placeholder ?? L10n.text("placeholder"),
                                 allowsPhotos: model.appearance.attachments != false, theme: theme,
                                 onTyping: { model.typing() }, onSend: { model.send($0, images: $1) }, focused: $composing)
                    }
                }
            }
        }
        .background(theme.surface.ignoresSafeArea())
        .tint(theme.accent)
        .onAppear { model.appeared() }
        .onDisappear { model.disappeared() }
    }

    private static let bottom = "hailbell-bottom"

    /// Changes whenever something new appears at the bottom of the chat.
    private var tail: String {
        "\(model.messages.count)|\(model.messages.last?.id ?? "")|\(model.pending.count)|\(model.agentTyping)"
    }

    private var title: String {
        model.appearance.title ?? L10n.text("supportTitle", ["name": model.appName.isEmpty ? "" : model.appName])
            .trimmingCharacters(in: .whitespaces)
    }

    /// "We read every message.", unless the dashboard set its own line.
    private var subtitle: String {
        if let s = model.appearance.subtitle { return s }
        return L10n.text("subtitle")
    }

    /// Like the web widget: the support mark and photo of who replies, the app's name, and who reads the messages.
    private func header(_ theme: ChatTheme) -> some View {
        HStack(spacing: 12) {
            // The support mark, with the photo of who replies just overlapping it, like Intercom's team stack.
            HStack(spacing: -8) {
                if hasPhoto {
                    SupportMark(size: 40, theme: theme)
                    Avatar(image: Hailbell.teamPhoto, url: model.teamPhoto ?? avatarURL, size: 40, theme: theme)
                        .padding(2)
                        .background(theme.background, in: Circle())
                        .overlay(alignment: .bottomTrailing) { presenceDot(theme) }
                } else {
                    SupportMark(size: 40, theme: theme)
                        .overlay(alignment: .bottomTrailing) { presenceDot(theme) }
                }
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.headline).lineLimit(1)
                Text(subtitle).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
            }
            Spacer(minLength: 0)
            Button { dismiss() } label: {
                Image(systemName: "xmark").font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 32, height: 32)
            }
            .glassCircleButton(theme)
            .accessibilityLabel(L10n.text("close"))
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .background(theme.surface)
        .overlay(alignment: .bottom) { if !theme.glass { Divider() } }
    }

    private var hasPhoto: Bool { Hailbell.teamPhoto != nil || (model.teamPhoto ?? avatarURL) != nil }

    /// Green while the team was active in the last 5 minutes, grey after. Rechecked every minute.
    private func presenceDot(_ theme: ChatTheme) -> some View {
        TimelineView(.periodic(from: .now, by: 60)) { context in
            if let online = model.agentOnline(at: context.date) {
                Circle().fill(online ? Color.green : Color.gray)
                    .frame(width: 12, height: 12)
                    .overlay(Circle().stroke(theme.background, lineWidth: 2))
                    .accessibilityLabel(L10n.text(online ? "online" : "away"))
            }
        }
    }

    private var avatarURL: URL? {
        guard let path = model.appearance.avatarUrl, let base = model.baseURL else { return nil }
        return URL(string: path, relativeTo: base)
    }

    /// Only on an empty chat: what to write about, and the AI note customers must see before the AI
    /// answers: "If we're away, an AI may answer first." The app's privacy policy names the provider.
    /// AI replies are also labeled "AI" in the chat.
    private func intro(_ theme: ChatTheme) -> some View {
        VStack(spacing: 6) {
            Text(model.appearance.welcomeTitle ?? L10n.text("welcomeTitle")).font(.headline)
            if let text = model.appearance.welcomeText {
                Text(text).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            if model.assistant != nil {
                Text(L10n.text("aiShort")).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 28)
        .frame(maxWidth: .infinity)
    }

    private func startsDay(_ m: Message) -> Bool {
        guard let i = model.messages.firstIndex(where: { $0.id == m.id }) else { return false }
        return i == 0 || !Calendar.current.isDate(model.messages[i - 1].date, inSameDayAs: m.date)
    }

    /// Names over the first of a run of replies, unless the dashboard hides them. AI replies
    /// always say they're from an AI.
    private func caption(_ m: Message) -> String? {
        if m.isMine || !model.startsRun(m) { return nil }
        if m.author == "bot" { return "\(m.authorName ?? "Assistant") · \(L10n.text("ai"))" }
        return model.appearance.agentNames == false ? nil : m.authorName
    }
}

/// A message on its way: its photos and text, faded until the server has it.
private struct PendingRow: View {
    let item: ChatModel.Pending
    let theme: ChatTheme

    var body: some View {
        VStack(alignment: .trailing, spacing: 4) {
            ForEach(item.images.indices, id: \.self) { i in
                Image(uiImage: item.images[i]).resizable().scaledToFit()
                    .frame(maxWidth: 220, maxHeight: 280)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .opacity(item.failed ? 1 : 0.6)
            }
            if !item.body.isEmpty {
                Bubble(text: item.body, mine: true, theme: theme, sending: !item.failed, failed: item.failed)
            } else if item.failed {
                Text(L10n.text("notSent")).font(.caption2).foregroundStyle(.red).padding(.horizontal, 6)
            }
        }
        .frame(maxWidth: MessageRow.maxWidth, alignment: .trailing)
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 48)
    }
}

/// One tick once we have the message, two once it reached the team, blue once they read it.
private struct Ticks: View {
    let delivery: ChatModel.Delivery

    var body: some View {
        TickShape(double: delivery != .sent)
            .stroke(style: StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round))
            .foregroundStyle(delivery == .seen ? Color(red: 0.2, green: 0.59, blue: 0.98) : Color.secondary)
            .frame(width: 16, height: 11)
            .padding(.horizontal, 6)
            .frame(maxWidth: .infinity, alignment: .trailing)
            .accessibilityElement()
            .accessibilityLabel(L10n.text(delivery == .seen ? "seen" : delivery == .delivered ? "delivered" : "sent"))
    }
}

private struct TickShape: Shape {
    let double: Bool

    func path(in r: CGRect) -> Path {
        var p = Path()
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: r.minX + x / 22 * r.width, y: r.minY + y / 12 * r.height) }
        if double {
            p.move(to: pt(0.5, 6.5)); p.addLine(to: pt(4.5, 10.5)); p.addLine(to: pt(14, 1))
            p.move(to: pt(9.5, 9.5)); p.addLine(to: pt(10.5, 10.5)); p.addLine(to: pt(20, 1))
        } else {
            p.move(to: pt(5, 6.5)); p.addLine(to: pt(9, 10.5)); p.addLine(to: pt(18.5, 1))
        }
        return p
    }
}
