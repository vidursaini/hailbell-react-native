import PhotosUI
import SwiftUI

/// The field at the bottom: a photo button, the text, and send. Liquid Glass on iOS 26 and later. Picked photos wait above the field
/// until sent, each with a button to remove it.
struct Composer: View {
    let placeholder: String
    let allowsPhotos: Bool
    let theme: ChatTheme
    let onTyping: () -> Void
    let onSend: (String, [UIImage]) -> Void
    @Binding var focused: Bool
    /// The message being answered, shown above the field.
    var reply: ReplyBarItem? = nil
    var onCancelReply: () -> Void = {}

    @State private var draft = ""
    @State private var images: [UIImage] = []
    @State private var picks: [PhotosPickerItem] = []
    @FocusState private var fieldFocused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static let maxImages = 4
    /// One line of text and the send button share this height, so both sit centred in the field.
    private static let fieldHeight: CGFloat = 36
    private static let sendSize: CGFloat = 28

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let reply {
                ReplyBar(item: reply, theme: theme, onCancel: onCancelReply)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
            if !images.isEmpty { previews }
            HStack(alignment: .bottom, spacing: 8) {
                if allowsPhotos {
                    PhotosPicker(selection: $picks, maxSelectionCount: Self.maxImages - images.count, matching: .images) {
                        Image(systemName: theme.glass ? "plus" : "paperclip").font(.system(size: 17, weight: .medium))
                            .foregroundStyle(theme.glass ? Color.primary : Color.secondary)
                            .frame(width: Self.fieldHeight, height: Self.fieldHeight)
                    }
                    .glassAttachButton(theme)
                    .disabled(images.count >= Self.maxImages)
                    .accessibilityLabel(L10n.text("attach"))
                }
                HStack(alignment: .bottom, spacing: 6) {
                    TextField(placeholder, text: $draft, axis: .vertical)
                        .font(.body)
                        .lineLimit(1...5)
                        .focused($fieldFocused)
                        .padding(.vertical, 8)
                        .frame(minHeight: Self.fieldHeight)
                        .onChange(of: draft) { value in if !value.isEmpty { onTyping() } }
                    // Like Messages, the send button only shows once there is something to send.
                    if canSend || !theme.glass { sendButton }
                }
                .padding(.leading, 12)
                .padding(.trailing, canSend || !theme.glass ? 4 : 12)
                .glassField(theme, focused: fieldFocused)
                .animation(.easeOut(duration: 0.15), value: canSend)
            }
        }
        .padding(.horizontal, theme.glass ? 16 : 12)
        .padding(.top, 8)
        .padding(.bottom, theme.glass ? 4 : 8)
        .background(theme.glass ? Color.clear : theme.surface)
        .onChange(of: picks) { items in load(items) }
        .onChange(of: fieldFocused) { focused = $0 }
        .animation(reduceMotion ? .easeOut(duration: 0.15) : .spring(response: 0.3, dampingFraction: 0.86), value: reply?.quote.id)
        // Picking a message to answer puts the cursor in the field.
        .onChange(of: reply?.quote.id) { id in if id != nil { fieldFocused = true } }
    }

    private var sendButton: some View {
        Button(action: send) {
            Image(systemName: "arrow.up").font(.system(size: 14, weight: .bold))
                .foregroundStyle(canSend ? theme.onAccent : Color.secondary)
                .frame(width: Self.sendSize, height: Self.sendSize)
        }
        .glassSendButton(theme, enabled: canSend)
        .disabled(!canSend)
        .accessibilityLabel(L10n.text("send"))
        .padding(.bottom, (Self.fieldHeight - Self.sendSize) / 2)
    }

    private var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !images.isEmpty
    }

    private var previews: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(images.indices, id: \.self) { i in
                    Image(uiImage: images[i]).resizable().scaledToFill()
                        .frame(width: 64, height: 64)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .overlay(alignment: .topTrailing) {
                            Button { images.remove(at: i) } label: {
                                Image(systemName: "xmark.circle.fill").symbolRenderingMode(.palette)
                                    .foregroundStyle(.white, .black.opacity(0.6)).font(.system(size: 20))
                            }
                            .padding(3)
                            .accessibilityLabel(L10n.text("removeImage"))
                        }
                }
            }
            .padding(.horizontal, 4)
        }
    }

    private func load(_ items: [PhotosPickerItem]) {
        guard !items.isEmpty else { return }
        picks = []
        Task {
            for item in items {
                if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data),
                   images.count < Self.maxImages {
                    images.append(image)
                }
            }
        }
    }

    private func send() {
        guard canSend else { return }
        onSend(draft, images)
        draft = ""
        images = []
    }
}
