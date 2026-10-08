#if os(macOS)
import MotifKit
import SwiftUI

/// First launch on the Mac: brand panel on the left, sign in or create account on the right.
struct MacWelcomeView: View {
    @Environment(AppModel.self) private var model
    @State private var mode: AuthMode = .signIn

    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 24) {
                HStack(spacing: 10) {
                    MotifMark(size: 40)
                    Text("Motif").font(.system(size: 24, weight: .bold))
                }
                VStack(alignment: .leading, spacing: 12) {
                    Text("One listening history across your Mac, iPhone and Android")
                        .font(.system(size: 26, weight: .bold))
                    Text("An account syncs plays, crates and playlists. Audio files stay on each device and are never uploaded.")
                        .font(.system(size: 14)).foregroundStyle(Theme.secondary)
                }
                Spacer()
            }
            .padding(40)
            .frame(width: 360)
            .frame(maxHeight: .infinity, alignment: .top)
            .background(Theme.macChrome)

            VStack(alignment: .leading, spacing: 22) {
                Picker("Mode", selection: $mode) {
                    Text("Sign in").tag(AuthMode.signIn)
                    Text("Create account").tag(AuthMode.signUp)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
                AuthForm(mode: mode)
                    .id(mode)
                    .frame(maxWidth: 380)
                Spacer(minLength: 0)
                Divider()
                HStack {
                    Text("Everything works without an account. Sign in later from Settings.")
                        .font(.system(size: 13)).foregroundStyle(Theme.secondary)
                    Spacer()
                    Button("Continue Without an Account") { model.account.continueOffline() }
                }
            }
            .padding(.horizontal, 56)
            .padding(.top, 48)
            .padding(.bottom, 24)
        }
        .foregroundStyle(Theme.text)
        .frame(width: 940, height: 600)
        .background(Theme.ground)
    }
}

/// Motif › Settings: General, History, Account, Server.
struct MacSettingsView: View {
    var body: some View {
        TabView {
            MacGeneralSettings()
                .tabItem { Label("General", systemImage: "gearshape") }
            NavigationStack { ListeningHistoryView() }
                .tabItem { Label("History", systemImage: "clock") }
            MacAccountSettings()
                .tabItem { Label("Account", systemImage: "person.crop.circle") }
            NavigationStack { ServerDetailsView() }
                .tabItem { Label("Server", systemImage: "server.rack") }
        }
        .frame(width: 720, height: 600)
        .background(Theme.ground)
    }
}

private struct MacGeneralSettings: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        let account = model.account
        Form {
            Section {
                LabeledContent("Account") {
                    if account.isSignedIn {
                        Text("Signed in as \(account.account?.username ?? "…") · \(account.lastSync.map { "synced \(relativeTime($0))" } ?? "not synced yet")")
                    } else {
                        Button("Sign In…") { account.showWelcome = true }
                    }
                }
            }
            Section("Playback") {
                Toggle("Mix into next by default", isOn: Binding(
                    get: { model.player.mixIntoNext },
                    set: { model.setMixIntoNext($0) }))
            }
            Section("Library") {
                Toggle("Analyze for mixing on import", isOn: $model.analyzeOnImport)
                Toggle("Read from Downloads", isOn: $model.readFromDownloads)
            }
            Section {
                Text("Only listening history, crates and playlists sync. Music files never leave this Mac.")
                    .foregroundStyle(Theme.secondary)
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
    }
}

private struct MacAccountSettings: View {
    @Environment(AppModel.self) private var model
    @State private var mode: AuthMode = .signIn

    var body: some View {
        if model.account.isSignedIn {
            NavigationStack { AccountDetailsView() }
        } else {
            VStack(alignment: .leading, spacing: 18) {
                Text("Sign in to sync your listening").font(.system(size: 20, weight: .bold))
                Text("Your history is on this Mac only until you sign in.")
                    .foregroundStyle(Theme.secondary)
                Picker("Mode", selection: $mode) {
                    Text("Sign in").tag(AuthMode.signIn)
                    Text("Create account").tag(AuthMode.signUp)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
                AuthForm(mode: mode).id(mode)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: 420, maxHeight: .infinity, alignment: .topLeading)
            .padding(32)
        }
    }
}
#endif
