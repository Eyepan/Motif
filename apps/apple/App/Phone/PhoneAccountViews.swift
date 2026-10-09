#if os(iOS)
import MotifKit
import SwiftUI

/// First launch: sign in, create an account, or use Motif offline.
struct PhoneWelcomeView: View {
    @Environment(AppModel.self) private var model
    @State private var path: [Step] = []
    @State private var offline = false

    enum Step: Hashable { case signUp }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    HStack(spacing: 10) {
                        MotifMark()
                        Text("Motif").font(.system(size: 22, weight: .bold))
                    }
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Sign in to sync your listening")
                            .font(.system(size: 32, weight: .bold))
                        Text("History, crates and playlists follow you across iPhone, Mac and Android. Your music files stay on your devices.")
                            .font(.system(size: 15)).foregroundStyle(Theme.secondary)
                    }
                    AuthForm(mode: .signIn)
                    Button("Create an account") { path.append(.signUp) }
                        .buttonStyle(QuietButtonStyle())
                    VStack(spacing: 8) {
                        Button("Not now, use Motif offline") { offline = true }
                            .font(.system(size: 16, weight: .semibold))
                            .frame(minHeight: 44)
                        Text("Everything works without an account.\nYou can sign in later from Settings.")
                            .font(.system(size: 13)).foregroundStyle(Theme.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                }
                .foregroundStyle(Theme.text)
                .padding(.horizontal, 24)
                .padding(.top, 48)
                .padding(.bottom, 24)
            }
            .background(Theme.ground)
            .scrollDismissesKeyboard(.interactively)
            .navigationDestination(for: Step.self) { _ in PhoneSignUpView() }
            .sheet(isPresented: $offline) { PhoneOfflineSheet() }
        }
        .interactiveDismissDisabled()
    }
}

struct PhoneSignUpView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Create an account").font(.system(size: 32, weight: .bold))
                    Text("A username and a password. No email, nothing to pay.")
                        .font(.system(size: 15)).foregroundStyle(Theme.secondary)
                }
                AuthForm(mode: .signUp)
            }
            .foregroundStyle(Theme.text)
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
        }
        .background(Theme.ground)
        .scrollDismissesKeyboard(.interactively)
    }
}

/// "Use Motif without an account": what works offline, then continue.
struct PhoneOfflineSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Use Motif without an account").font(.system(size: 24, weight: .bold))
                Text("Nothing is locked. Motif keeps your listening history on this iPhone and uploads it if you sign in later.")
                    .font(.system(size: 15)).foregroundStyle(Theme.secondary)
            }
            OfflineSummary()
            Spacer(minLength: 0)
            Button("Continue offline") {
                dismiss()
                model.account.continueOffline()
            }
            .buttonStyle(AccentButtonStyle())
            Button("Back to sign in") { dismiss() }
                .font(.system(size: 16, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .foregroundStyle(Theme.text)
        .padding(24)
        .presentationDetents([.large])
        .presentationBackground(Theme.surface)
    }
}

/// Settings, opened from the Library. Only settings that do something today.
struct PhoneSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        @Bindable var model = model
        let account = model.account
        NavigationStack {
            List {
                Section {
                    if account.isSignedIn {
                        NavigationLink { AccountDetailsView() } label: {
                            HStack(spacing: 12) {
                                Avatar(letter: account.account?.displayName ?? account.account?.username ?? "?")
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(account.account?.displayName ?? account.account?.username ?? "Account")
                                        .font(.system(size: 17, weight: .semibold))
                                    HStack(spacing: 6) {
                                        Circle().fill(account.syncError == nil ? Theme.accent : Theme.warning).frame(width: 7, height: 7)
                                        Text(account.syncError == nil ? (account.lastSync.map { "Synced \(relativeTime($0))" } ?? "Not synced yet") : "Not synced")
                                    }
                                    .font(.system(size: 13)).foregroundStyle(Theme.secondary)
                                }
                            }
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Not signed in").font(.system(size: 17, weight: .semibold))
                            Text("Your history is on this iPhone only. Sign in to sync it with your other devices.")
                                .font(.system(size: 14)).foregroundStyle(Theme.secondary)
                            Button("Sign in or create account") {
                                dismiss()
                                model.account.showWelcome = true
                            }
                            .buttonStyle(AccentButtonStyle())
                        }
                        .padding(.vertical, 6)
                    }
                }

                Section("Playback") {
                    Toggle("Mix into next by default", isOn: Binding(
                        get: { model.player.mixIntoNext },
                        set: { model.setMixIntoNext($0) }))
                }

                Section("Library") {
                    Toggle("Analyze for mixing on import", isOn: $model.analyzeOnImport)
                }

                Section {
                    NavigationLink("Listening history") { ListeningHistoryView() }
                    NavigationLink { ServerDetailsView() } label: {
                        LabeledContent("Server", value: account.serverURL.host() ?? "")
                    }
                } header: {
                    Text("Sync")
                } footer: {
                    Text("Only listening history, crates and playlists sync. Music files never leave this iPhone.")
                }
            }
            .listRowBackground(Theme.surface)
            .scrollContentBackground(.hidden)
            .background(Theme.ground)
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }
}
#endif
