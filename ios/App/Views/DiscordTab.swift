import SwiftUI
import UIKit
import WebKit

/**
 The Discord tab: Discord's own website, as Discord serves it, signed in by the
 person. Nothing is injected into the page, read out of it or changed in it,
 and its cookies are in a data store of its own, apart from the rest of the
 app. Only discord.com loads here; other links open in the browser.
 */
struct DiscordTab: View {
    @State private var reloadToken = 0

    var body: some View {
        NavigationStack {
            DiscordWebView(reloadToken: reloadToken)
                .ignoresSafeArea(.keyboard)
                .navigationTitle("Discord")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            Button { reloadToken += 1 } label: { Label("Reload", systemImage: "arrow.clockwise") }
                            Button {
                                UIApplication.shared.open(DiscordPage.appLink)
                            } label: {
                                Label("Open the Discord app", systemImage: "arrow.up.forward.app")
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                    }
                }
        }
    }
}

/// The one page view, kept while the app runs, so coming back to the tab shows Discord where it was left.
@MainActor
final class DiscordPage: NSObject, WKNavigationDelegate, WKUIDelegate {
    static let shared = DiscordPage()
    static let start = URL(string: "https://discord.com/app")!
    static let appLink = URL(string: "https://discord.com/channels/@me")!
    /// Discord's own data store: its cookies and site storage are kept apart from everything else.
    private static let storeId = UUID(uuidString: "6C1D7C0E-9B1A-4E7B-8A52-D15C02D00001")!

    private(set) lazy var webView: WKWebView = {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = WKWebsiteDataStore(forIdentifier: Self.storeId)
        configuration.allowsInlineMediaPlayback = true
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = self
        view.uiDelegate = self
        view.allowsBackForwardNavigationGestures = true
        view.isOpaque = false
        view.backgroundColor = .systemBackground
        view.scrollView.bounces = false
        view.load(URLRequest(url: Self.start))
        return view
    }()

    static func isDiscord(_ url: URL?) -> Bool {
        guard let url, url.scheme?.lowercased() == "https", let host = url.host?.lowercased() else { return false }
        return host == "discord.com" || host.hasSuffix(".discord.com")
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        // Embedded frames (the "are you human" checks) load untouched.
        guard action.targetFrame?.isMainFrame != false else {
            decisionHandler(.allow)
            return
        }
        if Self.isDiscord(action.request.url) || action.request.url?.scheme == "about" {
            decisionHandler(.allow)
        } else {
            // Any other site opens in the browser; other kinds of link are dropped.
            if let url = action.request.url, url.scheme == "https" || url.scheme == "http" { UIApplication.shared.open(url) }
            decisionHandler(.cancel)
        }
    }

    /// A link that asks for a new window opens here if it is Discord's, else in the browser.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = action.request.url {
            if Self.isDiscord(url) { webView.load(action.request) } else if url.scheme == "https" { UIApplication.shared.open(url) }
        }
        return nil
    }

    /// The page's process was ended by iOS (memory): load it again instead of showing a blank page.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        webView.reload()
    }
}

struct DiscordWebView: UIViewRepresentable {
    let reloadToken: Int

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        context.coordinator.lastReload = reloadToken
        return DiscordPage.shared.webView
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        if context.coordinator.lastReload != reloadToken {
            context.coordinator.lastReload = reloadToken
            if view.url == nil { view.load(URLRequest(url: DiscordPage.start)) } else { view.reload() }
        }
    }

    final class Coordinator {
        var lastReload = 0
    }
}
