import ObjectiveC
import SwiftUI
import UIKit

// MARK: shake

/// Opens the report screen when the phone is shaken, while `Hailbell.shakeToReport` is on.
///
/// Shakes reach the key window as `motionEnded(_:with:)` near the end of the responder chain. UIWindow
/// gets its own copy of that method first, so only UIWindow's version is replaced and UIResponder's,
/// which every view and view controller shares, is left alone. The replacement calls the original
/// under its real name: UIResponder's version passes the event up the chain using the name it was
/// called with, so a plain method swap would send an unknown message to the window scene and crash.
@MainActor
enum ShakeDetector {
    private(set) static var installed = false

    private typealias MotionEnded = @convention(c) (AnyObject, Selector, UIEvent.EventSubtype, UIEvent?) -> Void

    static func install() {
        guard !installed else { return }
        installed = true
        let window: AnyClass = UIWindow.self
        let selector = #selector(UIResponder.motionEnded(_:with:))
        guard let inherited = class_getInstanceMethod(window, selector) else { return }
        let types = method_getTypeEncoding(inherited)
        // Adds nothing when UIWindow already has its own version; then that one is replaced.
        class_addMethod(window, selector, method_getImplementation(inherited), types)
        guard let own = class_getInstanceMethod(window, selector) else { return }
        let original = unsafeBitCast(method_getImplementation(own), to: MotionEnded.self)
        let replacement: @convention(block) (UIWindow, UIEvent.EventSubtype, UIEvent?) -> Void = { window, motion, event in
            original(window, selector, motion, event)
            guard motion == .motionShake else { return }
            MainActor.assumeIsolated { if Hailbell.shakeToReport { Hailbell.reportBug() } }
        }
        method_setImplementation(own, imp_implementationWithBlock(replacement))
    }
}

extension UIWindow {
    /// What the window shows right now, at the screen's scale.
    func hailbellSnapshot() -> UIImage? {
        guard bounds.width > 0, bounds.height > 0 else { return nil }
        let format = UIGraphicsImageRendererFormat(for: traitCollection)
        return UIGraphicsImageRenderer(bounds: bounds, format: format).image { _ in
            drawHierarchy(in: bounds, afterScreenUpdates: false)
        }
    }
}

// MARK: sheet

/// Hosts the report screen and notes when it has gone, so a later shake can open a new one.
final class ReportHostingController: UIHostingController<BugReportScreen> {
    private(set) var dismissed = false

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        // The markup editor covers the sheet without dismissing it; only a real dismissal counts.
        if isBeingDismissed || presentingViewController == nil { dismissed = true }
    }
}

/// The report screen in the chat's light or dark look.
struct BugReportScreen: View {
    let model: ChatModel
    let screenshot: UIImage?

    var body: some View {
        if let dark = Hailbell.darkMode {
            BugReportView(model: model, screenshot: screenshot).environment(\.colorScheme, dark ? .dark : .light)
        } else {
            BugReportView(model: model, screenshot: screenshot)
        }
    }
}

// MARK: report screen

/// "Report a problem": what went wrong, the screenshot with Mark up and Remove, then Send.
struct BugReportView: View {
    @ObservedObject var model: ChatModel
    @State private var screenshot: UIImage?
    @State private var text = ""
    @State private var sending = false
    @State private var error: String?
    @State private var markingUp = false
    @FocusState private var focused: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dynamicTypeSize) private var typeSize

    init(model: ChatModel, screenshot: UIImage?) {
        self.model = model
        _screenshot = State(initialValue: screenshot)
    }

    var body: some View {
        let theme = ChatTheme(model.appearance, dark: scheme == .dark)
        NavigationStack {
            Form {
                if let error {
                    Section { Label(error, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red) }
                }
                Section {
                    TextField(L10n.text("reportPlaceholder"), text: $text, axis: .vertical)
                        .lineLimit(4...12)
                        .focused($focused)
                } footer: {
                    Text(L10n.text("reportNote"))
                }
                if let screenshot { screenshotSection(screenshot) }
            }
            .navigationTitle(L10n.text("reportTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.text("cancel")) { dismiss() }.disabled(sending)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if sending {
                        ProgressView()
                    } else {
                        Button(L10n.text("send"), action: send).disabled(!canSend)
                    }
                }
            }
            .fullScreenCover(isPresented: $markingUp) {
                if let screenshot {
                    MarkupView(image: screenshot) { self.screenshot = $0 }
                }
            }
        }
        .tint(theme.accent)
        // A swipe down shouldn't throw away what they wrote; Cancel still closes it.
        .interactiveDismissDisabled(sending || !trimmed.isEmpty)
        .onAppear { focused = true }
    }

    private func screenshotSection(_ image: UIImage) -> some View {
        // Side by side, or stacked once the text is too big to fit beside the picture.
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 20))
        return Section(L10n.text("screenshot")) {
            layout {
                Button { markingUp = true } label: {
                    Image(uiImage: image).resizable().scaledToFit()
                        .frame(maxWidth: 130, maxHeight: 240)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Color.primary.opacity(0.15), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.text("screenshot"))
                .accessibilityHint(L10n.text("markUp"))
                VStack(alignment: .leading, spacing: 16) {
                    Button { markingUp = true } label: { Label(L10n.text("markUp"), systemImage: "pencil.tip.crop.circle") }
                    Button(role: .destructive) { withAnimation { screenshot = nil } } label: { Label(L10n.text("remove"), systemImage: "trash") }
                }
                .buttonStyle(.borderless)
                .disabled(sending)
            }
            .padding(.vertical, 6)
        }
    }

    private var trimmed: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }

    /// Something to send: words, a screenshot, or both.
    private var canSend: Bool { !sending && (!trimmed.isEmpty || screenshot != nil) }

    private func send() {
        guard canSend else { return }
        focused = false
        sending = true
        error = nil
        let report = Hailbell.shared.reportDetails()
        Task {
            do {
                try await model.sendReport(text, screenshot: screenshot, report: report)
                dismiss()
            } catch let failure {
                error = failure.localizedDescription
                sending = false
            }
        }
    }
}

// MARK: mark up

/// A red line drawn on the screenshot, in the image's own points.
struct MarkupStroke: Equatable {
    var points: [CGPoint]
    var width: CGFloat

    var path: Path {
        var p = Path()
        guard let first = points.first else { return p }
        p.move(to: first)
        if points.count == 1 {
            // A tap: a short line, so the round caps draw a dot.
            p.addLine(to: CGPoint(x: first.x + 0.1, y: first.y))
        } else {
            for point in points.dropFirst() { p.addLine(to: point) }
        }
        return p
    }

    static let color = UIColor(red: 1, green: 0.23, blue: 0.19, alpha: 1)

    /// The screenshot with the strokes drawn into it, at the screenshot's own size and scale.
    static func flatten(_ image: UIImage, _ strokes: [MarkupStroke]) -> UIImage {
        guard !strokes.isEmpty else { return image }
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = image.scale
        return UIGraphicsImageRenderer(size: image.size, format: format).image { context in
            image.draw(at: .zero)
            let cg = context.cgContext
            cg.setStrokeColor(color.cgColor)
            cg.setLineCap(.round)
            cg.setLineJoin(.round)
            for stroke in strokes {
                cg.setLineWidth(stroke.width)
                cg.addPath(stroke.path.cgPath)
                cg.strokePath()
            }
        }
    }
}

/// Full screen over the screenshot: the finger draws red lines. Undo takes back the last one, Done
/// draws them into the picture.
struct MarkupView: View {
    let image: UIImage
    let onDone: (UIImage) -> Void
    @State private var strokes: [MarkupStroke] = []
    @State private var current: MarkupStroke?
    @Environment(\.dismiss) private var dismiss

    /// How thick a line looks on screen, whatever the picture's size.
    private static let lineWidth: CGFloat = 5

    var body: some View {
        NavigationStack {
            GeometryReader { geo in
                let rect = Self.fit(image.size, in: geo.size)
                let zoom = rect.width / max(image.size.width, 1)
                ZStack(alignment: .topLeading) {
                    Image(uiImage: image).resizable()
                        .frame(width: rect.width, height: rect.height)
                        .offset(x: rect.minX, y: rect.minY)
                    Canvas { context, _ in
                        context.translateBy(x: rect.minX, y: rect.minY)
                        context.scaleBy(x: zoom, y: zoom)
                        for stroke in strokes + (current.map { [$0] } ?? []) {
                            context.stroke(stroke.path, with: .color(Color(uiColor: MarkupStroke.color)),
                                           style: StrokeStyle(lineWidth: stroke.width, lineCap: .round, lineJoin: .round))
                        }
                    }
                    .allowsHitTesting(false)
                }
                .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let point = CGPoint(x: (value.location.x - rect.minX) / zoom, y: (value.location.y - rect.minY) / zoom)
                            if current == nil { current = MarkupStroke(points: [], width: Self.lineWidth / zoom) }
                            current?.points.append(point)
                        }
                        .onEnded { _ in
                            if let current, !current.points.isEmpty { strokes.append(current) }
                            current = nil
                        }
                )
                .accessibilityElement()
                .accessibilityLabel(L10n.text("screenshot"))
            }
            .padding(12)
            .background(Color.black.ignoresSafeArea())
            .navigationTitle(L10n.text("markUp"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.text("cancel")) { dismiss() }
                }
                ToolbarItemGroup(placement: .confirmationAction) {
                    Button { _ = strokes.popLast() } label: { Label(L10n.text("undo"), systemImage: "arrow.uturn.backward") }
                        .disabled(strokes.isEmpty)
                    Button(L10n.text("done")) {
                        onDone(MarkupStroke.flatten(image, strokes))
                        dismiss()
                    }
                }
            }
        }
        .environment(\.colorScheme, .dark)
    }

    /// Where a picture of `size` sits when fitted, centred, into `bounds`.
    static func fit(_ size: CGSize, in bounds: CGSize) -> CGRect {
        guard size.width > 0, size.height > 0 else { return .zero }
        let scale = min(bounds.width / size.width, bounds.height / size.height)
        let fitted = CGSize(width: size.width * scale, height: size.height * scale)
        return CGRect(x: (bounds.width - fitted.width) / 2, y: (bounds.height - fitted.height) / 2,
                      width: fitted.width, height: fitted.height)
    }
}

/// Under a bug report in the chat: a small ladybug label, so it reads as a report, not a chat line.
struct BugReportTag: View {
    var body: some View {
        Label(L10n.text("bugReport"), systemImage: "ladybug")
            .font(.caption2)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 6)
    }
}
