import AuthenticationServices
import LightDecoderCore
import SwiftUI
import UIKit

/// Signing in to a Matrix account: on the server's own page (where an account can also be made), or with a password.
struct LoginView: View {
    @EnvironmentObject private var model: AppModel
    @State private var server = Prefs.serverName
    @State private var user = ""
    @State private var password = ""
    @State private var busy = false
    @State private var problem: String?
    @State private var webSession: ASWebAuthenticationSession?
    private let anchor = WebAuthAnchor()

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("LightDecoder chat runs on Matrix, an open messaging network. Sign in to your account, or make one for free. Your friends find you by your Matrix name, like @anna:matrix.org.")
                        .font(.callout)
                }
                Section("Server") {
                    TextField("matrix.org", text: $server)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                }
                Section {
                    Button {
                        Task { await signInOnPage() }
                    } label: {
                        Label("Sign in or create an account", systemImage: "person.badge.key")
                    }
                    .disabled(busy)
                } footer: {
                    Text("Opens the server's own page. Passkeys and password managers work there.")
                }
                Section("Or with a password") {
                    TextField("Username", text: $user)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .textContentType(.username)
                    SecureField("Password", text: $password)
                        .textContentType(.password)
                    Button("Sign in") { Task { await signInWithPassword() } }
                        .disabled(busy || user.isEmpty || password.isEmpty)
                }
                if let problem {
                    Section { Text(problem).foregroundStyle(.red) }
                }
                Section {
                    Text("Messages in this version are not end-to-end encrypted: they are protected on their way, and the server you choose stores them.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("LightDecoder")
            .overlay { if busy { ProgressView() } }
        }
    }

    private func signInWithPassword() async {
        busy = true
        problem = nil
        defer { busy = false }
        do {
            try await model.signIn(server: server, user: user, password: password)
        } catch {
            problem = Self.describe(error)
        }
    }

    private func signInOnPage() async {
        busy = true
        problem = nil
        do {
            let page = try await model.signInPage(server: server)
            let chosen = server
            let web = ASWebAuthenticationSession(url: page, callbackURLScheme: "lightdecoder") { callback, error in
                Task { @MainActor in
                    defer { busy = false }
                    guard let callback else {
                        // Closed by the person: nothing to say.
                        if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin { return }
                        problem = "The sign-in page did not finish."
                        return
                    }
                    do {
                        try await model.signIn(server: chosen, callback: callback)
                    } catch {
                        problem = Self.describe(error)
                    }
                }
            }
            web.presentationContextProvider = anchor
            web.prefersEphemeralWebBrowserSession = false
            webSession = web
            if !web.start() {
                busy = false
                problem = "The sign-in page could not be opened."
            }
        } catch {
            busy = false
            problem = Self.describe(error)
        }
    }

    static func describe(_ error: Error) -> String {
        if let matrix = error as? MatrixError {
            switch matrix.errcode {
            case "M_FORBIDDEN": return "That username or password is not right."
            case "M_LIMIT_EXCEEDED": return "Too many tries. Wait a minute and try again."
            case "M_USER_DEACTIVATED": return "This account has been closed."
            default: return matrix.message
            }
        }
        return "Could not reach the server. Check the connection and the server name."
    }
}

/// Where the sign-in page is shown from.
final class WebAuthAnchor: NSObject, ASWebAuthenticationPresentationContextProviding {
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }
}
