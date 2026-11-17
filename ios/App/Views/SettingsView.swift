import SwiftUI
import UIKit

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var displayName = ""
    @State private var savedName = ""
    @State private var notify = Prefs.notify
    @State private var readReceipts = Prefs.sendReadReceipts
    @State private var typing = Prefs.sendTyping
    @State private var confirmSignOut = false

    var body: some View {
        Form {
            Section("Your account") {
                HStack {
                    Text(model.me).textSelection(.enabled)
                    Spacer()
                    ShareLink(item: model.me) { Image(systemName: "square.and.arrow.up") }
                }
                TextField("The name friends see", text: $displayName)
                if displayName != savedName, !displayName.trimmingCharacters(in: .whitespaces).isEmpty {
                    Button("Save name") {
                        Task { if await model.setDisplayName(displayName) { savedName = displayName } }
                    }
                }
            }
            Section {
                Toggle("Notify me about new messages", isOn: $notify)
                    .onChange(of: notify) { _, on in Prefs.notify = on }
            } footer: {
                Text("Notifications arrive while LightDecoder is open or was used recently. iOS only lets a closed app look for messages now and then, so they can be late.")
            }
            Section {
                Toggle("Tell others when I've read their messages", isOn: $readReceipts)
                    .onChange(of: readReceipts) { _, on in Prefs.sendReadReceipts = on }
                Toggle("Show others when I'm typing", isOn: $typing)
                    .onChange(of: typing) { _, on in Prefs.sendTyping = on }
            }
            Section("About") {
                Text("Chats use Matrix, an open network. In this version messages are not end-to-end encrypted: they are protected on their way, and your server stores them. Stickers and their pictures stay on this iPhone until you send them.")
                    .font(.footnote).foregroundStyle(.secondary)
                LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
            }
            Section {
                Button("Sign out", role: .destructive) { confirmSignOut = true }
            }
        }
        .navigationTitle("Settings")
        .task {
            if let name = await model.myDisplayName() {
                displayName = name
                savedName = name
            }
        }
        .confirmationDialog("Sign out of \(model.me)?", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { Task { await model.signOut() } }
        } message: {
            Text("The conversations kept on this iPhone are removed from it. They stay on the server.")
        }
    }
}
