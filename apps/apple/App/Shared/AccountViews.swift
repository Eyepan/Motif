import MotifKit
import SwiftUI
import UniformTypeIdentifiers

// Sign in, account, listening history and server screens shared by iPhone and Mac.
// Layout follows the approved mockups (https://claude.ai/artifact/M4dkYzjciFdeCqP9L1782W).

#if os(iOS)
typealias TextContent = UITextContentType
#else
typealias TextContent = NSTextContentType
#endif

extension Theme {
    static let danger = Color(hex: 0xFF6B6B)
    static let warning = Color(hex: 0xF2B544)
}

/// The Motif mark: waveform bars on an accent square.
struct MotifMark: View {
    var size: CGFloat = 36

    var body: some View {
        RoundedRectangle(cornerRadius: size / 4)
            .fill(Theme.accent)
            .frame(width: size, height: size)
            .overlay {
                HStack(spacing: size * 0.08) {
                    ForEach([0.2, 0.45, 0.7, 0.4, 0.15], id: \.self) { h in
                        Capsule().fill(Theme.onAccent).frame(width: size * 0.07, height: size * h)
                    }
                }
            }
            .accessibilityHidden(true)
    }
}

// MARK: - Sign in and create account

enum AuthMode: Hashable { case signIn, signUp }

/// Username, password (and confirmation when creating an account), with the server's
/// messages shown inline. Usernames are checked while typing on sign up.
struct AuthForm: View {
    @Environment(AppModel.self) private var model
    let mode: AuthMode
    @State private var username = ""
    @State private var password = ""
    @State private var confirm = ""
    @State private var availability: Availability = .unknown
    @State private var error: String?
    @State private var busy = false

    enum Availability: Equatable { case unknown, available, taken, invalid(String) }

    private var usernameValid: Bool {
        username.range(of: #"^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$"#, options: .regularExpression) != nil
    }

    private var canSubmit: Bool {
        guard !busy, usernameValid, password.count >= 8 else { return false }
        return mode == .signIn || (password == confirm && availability != .taken)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 6) {
                LabeledField(label: "Username", highlighted: availability == .available) {
                    TextField("Username", text: $username)
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                        .onSubmit(submit)
                }
                if mode == .signUp {
                    switch availability {
                    case .available: Text("Available").foregroundStyle(Theme.accent)
                    case .taken: Text("That username is taken").foregroundStyle(Theme.danger)
                    case .invalid(let reason): Text(reason).foregroundStyle(Theme.danger)
                    case .unknown: EmptyView()
                    }
                    Text("3 to 32 letters, numbers, dots, dashes or underscores.").foregroundStyle(Theme.secondary)
                }
            }
            .font(.system(size: 13))

            VStack(alignment: .leading, spacing: 6) {
                LabeledField(label: "Password") {
                    SecureField("Password", text: $password)
                        .textContentType(mode == .signUp ? TextContent.newPassword : TextContent.password)
                        .onSubmit(submit)
                }
                if mode == .signUp {
                    LabeledField(label: "Confirm password") {
                        SecureField("Confirm password", text: $confirm)
                            .textContentType(.newPassword)
                            .onSubmit(submit)
                    }
                    Group {
                        if !confirm.isEmpty, confirm != password {
                            Text("The passwords don't match.").foregroundStyle(Theme.danger)
                        } else {
                            Text("At least 8 characters.").foregroundStyle(Theme.secondary)
                        }
                    }
                    .font(.system(size: 13))
                }
            }

            if mode == .signUp {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "exclamationmark.circle").foregroundStyle(Theme.warning)
                    Text("There is no password reset without an email. If you forget it, your music and history are still on this \(AccountModel.deviceKind) and upload again to a new account.")
                        .font(.system(size: 13)).foregroundStyle(Theme.badge)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(12)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 10))
            }

            if let error {
                Text(error).font(.system(size: 13)).foregroundStyle(Theme.danger)
            }

            Button(action: submit) {
                Group {
                    if busy { ProgressView().tint(Theme.onAccent) } else { Text(mode == .signIn ? "Sign in" : "Create account") }
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(AccentButtonStyle())
            .disabled(!canSubmit)
        }
        .task(id: username) { await checkAvailability() }
    }

    private func checkAvailability() async {
        guard mode == .signUp else { return }
        availability = .unknown
        guard usernameValid else { return }
        // Wait for a pause in typing.
        try? await Task.sleep(for: .milliseconds(400))
        guard !Task.isCancelled else { return }
        do {
            let check = try await model.account.checkUsername(username)
            availability = check.available ? .available : .taken
        } catch let error as APIError where error.status == 400 {
            availability = .invalid(error.message)
        } catch {
            availability = .unknown
        }
    }

    private func submit() {
        guard canSubmit else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                if mode == .signIn {
                    try await model.account.signIn(username: username, password: password)
                } else {
                    try await model.account.register(username: username, password: password)
                }
                await model.refreshCrates()
            } catch let failure as APIError {
                error = failure.status == 409 ? "That username is taken." : failure.message
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

/// A rounded field with a small label above the text, as in the mockups.
struct LabeledField<Field: View>: View {
    let label: String
    var highlighted = false
    @ViewBuilder let field: Field

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.system(size: 12)).foregroundStyle(Theme.secondary)
            field
                .textFieldStyle(.plain)
                .labelsHidden()
                .font(.system(size: 16))
                .foregroundStyle(Theme.text)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(highlighted ? Theme.accent : Theme.hairline))
    }
}

struct AccentButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(Theme.onAccent)
            .frame(minHeight: 50)
            .background(Theme.accent.opacity(isEnabled ? (configuration.isPressed ? 0.8 : 1) : 0.4),
                        in: RoundedRectangle(cornerRadius: 14))
            .contentShape(Rectangle())
    }
}

struct QuietButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(Theme.text)
            .frame(maxWidth: .infinity, minHeight: 50)
            .background(Theme.surface.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Theme.hairline))
            .contentShape(Rectangle())
    }
}

/// What works without an account, shown before continuing offline.
struct OfflineSummary: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 10) {
                Text("WORKS OFFLINE").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.secondary)
                ForEach(["Playback, lossless and in the background", "Import, BPM and key analysis",
                         "Crates, DJ Mix and Mix into next", "Listening history and your yearly recap"], id: \.self) { item in
                    Label(item, systemImage: "checkmark").foregroundStyle(Theme.text)
                        .labelStyle(TintedIconLabelStyle(tint: Theme.accent))
                }
            }
            VStack(alignment: .leading, spacing: 10) {
                Text("NEEDS AN ACCOUNT").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.secondary)
                Label("Syncing history, crates and playlists with your other devices", systemImage: "icloud")
                    .foregroundStyle(Theme.badge)
                    .labelStyle(TintedIconLabelStyle(tint: Theme.secondary))
            }
        }
        .font(.system(size: 15))
    }
}

struct TintedIconLabelStyle: LabelStyle {
    let tint: Color

    func makeBody(configuration: Configuration) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            configuration.icon.foregroundStyle(tint).frame(width: 20)
            configuration.title
        }
    }
}

// MARK: - Account details

extension DeviceSession {
    var symbol: String {
        switch platform {
        case "ios": "iphone"
        case "ipados": "ipad"
        case "macos": "laptopcomputer"
        case "android": "candybarphone"
        default: "desktopcomputer"
        }
    }

    var platformName: String {
        switch platform {
        case "ios": "iOS"
        case "ipados": "iPadOS"
        case "macos": "macOS"
        case "android": "Android"
        default: "Other"
        }
    }
}

func relativeTime(ms: Int64) -> String {
    relativeTime(Date(timeIntervalSince1970: Double(ms) / 1000))
}

func relativeTime(_ date: Date) -> String {
    if Date.now.timeIntervalSince(date) < 60 { return "just now" }
    return date.formatted(.relative(presentation: .named))
}

func memberSince(_ ms: Int64) -> String {
    Date(timeIntervalSince1970: Double(ms) / 1000).formatted(.dateTime.day().month(.abbreviated).year())
}

/// Edit name and username.
struct EditProfileSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var displayName = ""
    @State private var username = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $displayName)
                    TextField("Username", text: $username)
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                } footer: {
                    Text(error ?? "Your name is shown only to you. Changing your username changes how you sign in.")
                        .foregroundStyle(error == nil ? Theme.secondary : Theme.danger)
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Edit Profile")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save).disabled(busy || username.count < 3)
                }
            }
        }
        .onAppear {
            displayName = model.account.account?.displayName ?? ""
            username = model.account.account?.username ?? ""
        }
    }

    private func save() {
        busy = true
        Task {
            defer { busy = false }
            let current = model.account.account
            do {
                try await model.account.updateAccount(
                    username: username.lowercased() == current?.username ? nil : username,
                    displayName: displayName == (current?.displayName ?? "") ? nil : displayName)
                dismiss()
            } catch let failure as APIError {
                error = failure.status == 409 ? "That username is taken." : failure.message
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

struct ChangePasswordSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var current = ""
    @State private var new = ""
    @State private var confirm = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("Current password", text: $current).textContentType(.password)
                    SecureField("New password", text: $new).textContentType(.newPassword)
                    SecureField("Confirm new password", text: $confirm).textContentType(.newPassword)
                } footer: {
                    Text(error ?? "At least 8 characters. Your other devices are signed out.")
                        .foregroundStyle(error == nil ? Theme.secondary : Theme.danger)
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Change Password")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Change", action: save).disabled(busy || current.isEmpty || new.count < 8 || new != confirm)
                }
            }
        }
    }

    private func save() {
        busy = true
        Task {
            defer { busy = false }
            do {
                try await model.account.changePassword(current: current, new: new)
                dismiss()
            } catch let failure as APIError {
                error = failure.status == 403 ? "The current password is wrong." : failure.message
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

/// Typing the password deletes the account and its synced history on the server.
struct DeleteAccountSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("Password", text: $password).textContentType(.password)
                } header: {
                    Text("Delete \(model.account.account?.username ?? "account")?")
                } footer: {
                    Text(error ?? "This removes your account and the synced copy of your history from the server, for every device. Music and history on this \(AccountModel.deviceKind) stay.")
                        .foregroundStyle(error == nil ? Theme.secondary : Theme.danger)
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Delete Account")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .destructiveAction) {
                    Button("Delete", role: .destructive, action: delete).disabled(busy || password.isEmpty)
                }
            }
        }
    }

    private func delete() {
        busy = true
        Task {
            defer { busy = false }
            do {
                try await model.account.deleteAccount(password: password)
                dismiss()
            } catch let failure as APIError {
                error = failure.status == 403 ? "That password is wrong." : failure.message
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

/// Name, username, sync, signed-in devices, security and the account's end.
struct AccountDetailsView: View {
    @Environment(AppModel.self) private var model
    @State private var editing = false
    @State private var changingPassword = false
    @State private var deleting = false
    @State private var confirmSignOut = false

    var body: some View {
        let account = model.account
        Form {
            Section {
                HStack(spacing: 14) {
                    Avatar(letter: account.account?.displayName ?? account.account?.username ?? "?", size: 56)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(account.account?.displayName ?? account.account?.username ?? "Signed in")
                            .font(.system(size: 20, weight: .bold))
                        if let username = account.account?.username, let created = account.account?.createdAtMs {
                            Text("@\(username) · member since \(memberSince(created))")
                                .font(.system(size: 13)).foregroundStyle(Theme.secondary)
                        }
                    }
                    Spacer()
                    Button("Edit") { editing = true }
                }
            }

            Section("Sync") {
                SyncStatusRow()
                LabeledContent("Plays in your history") {
                    Text((account.summary?.plays ?? account.playCount).formatted()).font(Theme.mono(15))
                }
                if account.unsyncedCount > 0 {
                    LabeledContent("Waiting to upload") {
                        Text("\(account.unsyncedCount)").font(Theme.mono(15)).foregroundStyle(Theme.warning)
                    }
                }
            }

            Section("Signed-in devices") {
                ForEach(account.sessions) { session in
                    HStack(spacing: 12) {
                        Image(systemName: session.symbol).foregroundStyle(Theme.secondary).frame(width: 24)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(session.deviceName ?? session.platformName)
                            Text(session.current ? "This \(AccountModel.deviceKind)" : "\(session.platformName) · active \(relativeTime(ms: session.lastUsedAtMs))")
                                .font(.system(size: 13))
                                .foregroundStyle(session.current ? Theme.accent : Theme.secondary)
                        }
                        Spacer()
                        if !session.current {
                            Button("Sign Out", role: .destructive) { Task { await account.signOut(session) } }
                                .buttonStyle(.borderless)
                                .foregroundStyle(Theme.danger)
                        }
                    }
                }
                if account.sessions.count > 1 {
                    Button("Sign out of other devices") { Task { await account.signOutOtherDevices() } }
                }
            }

            Section {
                Button("Change password") { changingPassword = true }
                Button("Sign out", role: .destructive) { confirmSignOut = true }
                    .foregroundStyle(Theme.danger)
                Button("Delete account", role: .destructive) { deleting = true }
                    .foregroundStyle(Theme.danger)
            } footer: {
                Text("Signing out keeps your music and history on this \(AccountModel.deviceKind). Deleting the account removes the synced copy from the server only.")
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(Theme.ground)
        .navigationTitle("Account")
        .task { await account.refreshAccount() }
        .refreshable { await account.refreshAccount() }
        .sheet(isPresented: $editing) { EditProfileSheet() }
        .sheet(isPresented: $changingPassword) { ChangePasswordSheet() }
        .sheet(isPresented: $deleting) { DeleteAccountSheet() }
        .confirmationDialog("Sign out of Motif on this \(AccountModel.deviceKind)?", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("Sign Out", role: .destructive) { Task { await account.signOut() } }
        } message: {
            Text("Your music and history stay here and upload again when you sign back in.")
        }
    }
}

struct Avatar: View {
    let letter: String
    var size: CGFloat = 44

    var body: some View {
        Circle()
            .fill(Theme.raised)
            .overlay(Circle().strokeBorder(Theme.hairline))
            .overlay {
                Text(String(letter.prefix(1)).uppercased())
                    .font(.system(size: size * 0.42, weight: .bold))
                    .foregroundStyle(Theme.accent)
            }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// "Up to date · last synced 2 min ago" with Sync now.
struct SyncStatusRow: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let account = model.account
        HStack(spacing: 10) {
            Circle().fill(account.syncError == nil ? Theme.accent : Theme.warning).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(account.isSyncing ? "Syncing…" : account.syncError == nil ? (account.unsyncedCount == 0 ? "Up to date" : "Waiting to upload") : "Not synced")
                Text(account.syncError ?? account.lastSync.map { "Last synced \(relativeTime($0))" } ?? "Not synced yet")
                    .font(.system(size: 13)).foregroundStyle(Theme.secondary)
            }
            Spacer()
            Button("Sync Now") { Task { await account.syncNow() } }
                .buttonStyle(.bordered)
                .disabled(account.isSyncing)
        }
    }
}

// MARK: - Listening history

/// Plays from every device, newest first, with pause, export and delete.
struct ListeningHistoryView: View {
    @Environment(AppModel.self) private var model
    @State private var rows: [HistoryRow] = []
    @State private var filter: DeviceFilter = .all
    @State private var confirmDelete = false
    @State private var exporting = false
    @State private var export: JSONLinesFile?

    enum DeviceFilter: Hashable { case all, this }

    var body: some View {
        @Bindable var account = model.account
        List {
            Section {
                Toggle(isOn: $account.historyPaused) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Pause history")
                        Text("Until you next open Motif").font(.system(size: 13)).foregroundStyle(Theme.secondary)
                    }
                }
                Picker("Devices", selection: $filter) {
                    Text("All devices").tag(DeviceFilter.all)
                    Text("This \(AccountModel.deviceKind)").tag(DeviceFilter.this)
                }
                .pickerStyle(.segmented)
            } footer: {
                Text(summaryLine)
            }
            .listRowBackground(Theme.surface)

            if rows.isEmpty {
                Section {
                    Text("No plays yet. Everything you listen to shows up here.")
                        .foregroundStyle(Theme.secondary)
                }
                .listRowBackground(Theme.surface)
            }
            ForEach(days, id: \.0) { group in
                Section(group.0) {
                    ForEach(group.1) { HistoryRowView(row: $0) }
                }
                .listRowBackground(Theme.ground)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.ground)
        .navigationTitle("Listening History")
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                Button {
                    Task {
                        export = JSONLinesFile(data: await model.account.exportHistory())
                        exporting = true
                    }
                } label: { Label("Export", systemImage: "square.and.arrow.up") }
                Button(role: .destructive) { confirmDelete = true } label: { Label("Delete…", systemImage: "trash") }
            }
        }
        .confirmationDialog("Delete listening history", isPresented: $confirmDelete, titleVisibility: .visible) {
            ForEach(AccountModel.DeleteRange.allCases) { range in
                Button(range.rawValue, role: .destructive) {
                    Task {
                        await model.account.deleteHistory(range)
                        await reload()
                    }
                }
            }
        } message: {
            Text(model.account.isSignedIn
                 ? "Plays are removed from every signed-in device and the server. Your library and crates stay."
                 : "Plays are removed from this \(AccountModel.deviceKind). Your library and crates stay.")
        }
        .fileExporter(isPresented: $exporting, document: export, contentType: .jsonLines,
                      defaultFilename: "motif-history.jsonl") { _ in export = nil }
        .task(id: filter) { await reload() }
        .onReceive(NotificationCenter.default.publisher(for: .historyDidSync)) { _ in Task { await reload() } }
    }

    private var summaryLine: String {
        let account = model.account
        var parts = ["\(account.playCount.formatted()) plays"]
        if account.unsyncedCount > 0 { parts.append("\(account.unsyncedCount) waiting to upload") }
        if !account.isSignedIn { parts.append("kept on this \(AccountModel.deviceKind) only") }
        return parts.joined(separator: " · ")
    }

    private var days: [(String, [HistoryRow])] {
        var order: [String] = []
        var groups: [String: [HistoryRow]] = [:]
        for row in rows {
            let day = Calendar.current.isDateInToday(row.at) ? "Today"
                : Calendar.current.isDateInYesterday(row.at) ? "Yesterday"
                : row.at.formatted(.dateTime.weekday(.wide).day().month(.abbreviated))
            if groups[day] == nil { order.append(day) }
            groups[day, default: []].append(row)
        }
        return order.map { ($0, groups[$0] ?? []) }
    }

    private func reload() async {
        let history = model.account.history
        let device = filter == .this ? history.deviceID : nil
        let events = (try? await history.recent(types: ["play"], limit: 500, deviceID: device)) ?? []
        rows = events.map { HistoryRow(event: $0, track: model.track(forKey: $0.trackKey), thisDevice: history.deviceID) }
        await model.account.refreshCounts()
    }
}

struct HistoryRowView: View {
    let row: HistoryRow

    var body: some View {
        HStack(spacing: 12) {
            if let track = row.track {
                ArtTile(seed: track.artSeed, letter: track.monogram, size: 44, radius: 6, artwork: track.id)
            } else {
                RoundedRectangle(cornerRadius: 6).fill(Theme.raised).frame(width: 44, height: 44)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(.system(size: 15)).foregroundStyle(row.track == nil ? Theme.secondary : Theme.text)
                Text([row.artist, row.fromThisDevice ? "This \(AccountModel.deviceKind)" : "Another device"]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 13)).foregroundStyle(Theme.secondary)
            }
            .lineLimit(1)
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 2) {
                Text(row.at.formatted(date: .omitted, time: .shortened)).font(Theme.mono(13)).foregroundStyle(Theme.secondary)
                Text(row.detail).font(.system(size: 12)).foregroundStyle(row.detail.hasPrefix("DJ") ? Theme.accent : Theme.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

extension UTType {
    static let jsonLines = UTType(filenameExtension: "jsonl", conformingTo: .json) ?? .json
}

/// History export: one event per line, the server's envelope.
struct JSONLinesFile: FileDocument {
    static let readableContentTypes: [UTType] = [.jsonLines]
    let data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

// MARK: - Server

/// Where this device syncs to, whether it's reachable, and a self-hosted address.
struct ServerDetailsView: View {
    @Environment(AppModel.self) private var model
    @State private var customAddress = ""
    @State private var useCustom = false
    @State private var addressError: String?

    var body: some View {
        let account = model.account
        Form {
            Section {
                HStack(spacing: 12) {
                    Image(systemName: "server.rack").font(.system(size: 20)).foregroundStyle(Theme.accent)
                        .frame(width: 44, height: 44)
                        .background(Theme.raised, in: RoundedRectangle(cornerRadius: 10))
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 8) {
                            Circle().fill(statusColor).frame(width: 8, height: 8)
                            Text(statusTitle).font(.system(size: 17, weight: .semibold))
                        }
                        Text(statusDetail).font(.system(size: 13)).foregroundStyle(Theme.secondary)
                    }
                    Spacer()
                    Button("Test") { Task { await account.checkHealth() } }.buttonStyle(.bordered)
                }
            }

            Section("Details") {
                LabeledContent("Address") { Text(account.serverURL.host() ?? account.serverURL.absoluteString).font(Theme.mono(14)) }
                if let region = account.health?.region { LabeledContent("Region", value: Self.regionName(region)) }
                if let version = account.health?.version { LabeledContent("Server version") { Text(version).font(Theme.mono(14)) } }
                if let upload = account.summary?.lastUploadAtMs { LabeledContent("Last upload", value: relativeTime(ms: upload)) }
                LabeledContent("Waiting to upload") {
                    Text("\(account.unsyncedCount)").font(Theme.mono(14))
                        .foregroundStyle(account.unsyncedCount > 0 ? Theme.warning : Theme.secondary)
                }
            }

            Section {
                Picker("Server", selection: $useCustom) {
                    Text("Motif server").tag(false)
                    Text("Self-hosted").tag(true)
                }
                .pickerStyle(.segmented)
                if useCustom {
                    TextField("https://music.example.com", text: $customAddress)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textContentType(.URL)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        #endif
                        .onSubmit(apply)
                    Button("Use This Server", action: apply)
                        .disabled(customAddress.isEmpty || AccountModel.parseServer(customAddress) == account.serverURL)
                }
            } header: {
                Text("Use a different server")
            } footer: {
                Text(addressError ?? "Switching servers signs you out. Your history stays on this \(AccountModel.deviceKind) and uploads to the new server once you sign in there.")
                    .foregroundStyle(addressError == nil ? Theme.secondary : Theme.danger)
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(Theme.ground)
        .navigationTitle("Server")
        .onAppear {
            useCustom = account.isCustomServer
            if account.isCustomServer { customAddress = account.serverURL.absoluteString }
        }
        .onChange(of: useCustom) { _, custom in
            if !custom, account.isCustomServer { Task { await account.useServer(nil) } }
        }
        .task { await account.checkHealth() }
    }

    private var statusColor: Color {
        guard let health = model.account.health else { return model.account.healthError == nil ? Theme.secondary : Theme.danger }
        return health.isOK ? Theme.accent : Theme.warning
    }

    private var statusTitle: String {
        guard let health = model.account.health else { return model.account.healthError == nil ? "Checking…" : "Unreachable" }
        return health.isOK ? "Connected" : "Degraded"
    }

    private var statusDetail: String {
        let account = model.account
        if let error = account.healthError { return error }
        guard let checked = account.healthCheckedAt else { return "Checking the server" }
        return ["Checked \(relativeTime(checked))", account.latencyMs.map { "\($0) ms" }].compactMap { $0 }.joined(separator: " · ")
    }

    private func apply() {
        guard let url = AccountModel.parseServer(customAddress) else {
            addressError = "Enter an https:// address."
            return
        }
        addressError = nil
        Task { await model.account.useServer(url) }
    }

    /// Vercel region codes the Motif server runs in.
    static func regionName(_ code: String) -> String {
        let names = ["sin1": "Singapore", "bom1": "Mumbai", "hnd1": "Tokyo", "fra1": "Frankfurt", "iad1": "Washington, D.C.",
                     "sfo1": "San Francisco", "lhr1": "London", "syd1": "Sydney"]
        return names[code].map { "\($0) (\(code))" } ?? code
    }
}
