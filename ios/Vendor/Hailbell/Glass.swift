import SwiftUI

// Liquid Glass on iOS 26 and later. Earlier systems keep the flat fills the chat had before.

extension View {
    /// A round button: glass, or the theme's incoming colour before iOS 26.
    @ViewBuilder
    func glassCircleButton(_ theme: ChatTheme) -> some View {
        if #available(iOS 26.0, *) {
            buttonStyle(.plain).glassEffect(.regular.interactive(), in: Circle())
        } else {
            background(theme.incoming, in: Circle())
        }
    }

    /// The photo button beside the field: its own glass circle on iOS 26, a bare icon before.
    @ViewBuilder
    func glassAttachButton(_ theme: ChatTheme) -> some View {
        if #available(iOS 26.0, *) {
            buttonStyle(.plain).glassEffect(.regular.interactive(), in: Circle())
        } else {
            self
        }
    }

    /// The send button: a small accent circle inside the end of the field.
    @ViewBuilder
    func glassSendButton(_ theme: ChatTheme, enabled: Bool) -> some View {
        if #available(iOS 26.0, *) {
            buttonStyle(.plain).background(theme.accent, in: Circle())
        } else {
            background(enabled ? theme.accent : Color(.tertiarySystemFill), in: Circle())
        }
    }

    /// The rounded field that holds the text and the send button.
    @ViewBuilder
    func glassField(_ theme: ChatTheme, focused: Bool) -> some View {
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        if #available(iOS 26.0, *) {
            glassEffect(.regular.interactive(), in: shape)
        } else {
            background(theme.incoming, in: shape)
                .overlay(shape.strokeBorder(focused ? theme.accent.opacity(0.6) : Color.primary.opacity(0.08), lineWidth: 1))
        }
    }
}
