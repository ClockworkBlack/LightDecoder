import BackgroundTasks
import SwiftUI

/// The iPhone app: LightDecoder's own chat (on Matrix), the sticker maker, and Discord's website.
@main
struct LightDecoderApp: App {
    @StateObject private var model = AppModel()
    @StateObject private var stickers = StickerLibrary()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(stickers)
                .task { model.start() }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: model.becameActive()
            case .background: model.wentToBackground()
            default: break
            }
        }
        // Now and then iOS lets the app look for new messages while it is closed.
        .backgroundTask(.appRefresh(AppModel.refreshTaskId)) {
            await model.backgroundRefresh()
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if model.session == nil {
            LoginView()
        } else {
            MainTabs()
        }
    }
}

struct MainTabs: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        TabView {
            ChatListView()
                .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }
                .badge(model.state.totalUnread + model.state.invites.count)
            NavigationStack { StickersView() }
                .tabItem { Label("Stickers", systemImage: "face.smiling") }
            DiscordTab()
                .tabItem { Label("Discord", systemImage: "gamecontroller") }
            NavigationStack { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
    }
}
