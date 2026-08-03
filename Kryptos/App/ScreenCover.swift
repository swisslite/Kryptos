import UIKit

@MainActor
enum ScreenCover {
    private static weak var cover: UIView?

    static func set(_ visible: Bool) {
        visible ? show() : hide()
    }

    static func dismissSharePresentations() {
        guard let root = topWindow()?.rootViewController else { return }
        var chain: [UIViewController] = []
        var next = root.presentedViewController
        while let current = next {
            chain.append(current)
            next = current.presentedViewController
        }
        guard let index = chain.lastIndex(where: { $0 is UIActivityViewController }) else { return }
        chain[index].presentingViewController?.dismiss(animated: false)
    }

    private static func show() {
        guard cover == nil, let window = topWindow() else { return }
        let view = UIView(frame: window.bounds)
        view.backgroundColor = KTheme.bgUI
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.isUserInteractionEnabled = false

        let config = UIImage.SymbolConfiguration(pointSize: 56, weight: .semibold)
        let mark = UIImageView(image: UIImage(systemName: "lock.shield.fill", withConfiguration: config))
        mark.tintColor = KTheme.accentUI
        mark.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(mark)
        NSLayoutConstraint.activate([
            mark.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            mark.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])

        window.addSubview(view)
        cover = view
    }

    private static func hide() {
        cover?.removeFromSuperview()
        cover = nil
    }

    private static func topWindow() -> UIWindow? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let active = scenes.first { $0.activationState == .foregroundActive }
            ?? scenes.first { $0.activationState == .foregroundInactive }
            ?? scenes.first
        guard let scene = active else { return nil }
        return scene.windows.first { $0.isKeyWindow } ?? scene.windows.first
    }
}
