#if os(iOS)
import MotifKit
import SwiftUI

/// Add Music: pick files (Files app covers iCloud, On My iPhone, USB drives
/// and SD cards, SMB shares) or open catalogs; shows per-file progress.
struct ImportSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var picking = false
    @State private var pickingFolder = false

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            List {
                Section("From") {
                    Button { picking = true } label: {
                        SourceRow(glyph: "folder", title: "Files",
                                  detail: "iCloud Drive, On My iPhone, USB drives, SMB shares")
                    }
                    if let folder = model.watchedFolder {
                        SourceRow(glyph: "arrow.down.circle", title: folder.lastPathComponent,
                                  detail: "Read on every open. Albums, folders and .zip files; best copy kept")
                            .swipeActions {
                                Button("Stop", role: .destructive) { model.stopWatchingFolder() }
                            }
                    } else {
                        Button { pickingFolder = true } label: {
                            SourceRow(glyph: "arrow.down.circle", title: "Read from Downloads",
                                      detail: "Pick a folder. Albums, folders and .zip files; best copy kept")
                        }
                        .fileImporter(isPresented: $pickingFolder, allowedContentTypes: [.folder]) { result in
                            switch result {
                            case .success(let url): model.watch(url)
                            case .failure(let error): model.errorMessage = error.localizedDescription
                            }
                        }
                    }
                    NavigationLink {
                        DiscoverView()
                    } label: {
                        SourceRow(glyph: "building.columns", title: "Open music catalogs",
                                  detail: "Free, openly licensed FLAC and WAV")
                    }
                }
                .listRowBackground(Theme.raised)

                if !model.importJobs.isEmpty {
                    Section {
                        ForEach(model.importJobs) { ImportJobRow(job: $0) }
                    } header: {
                        HStack {
                            Text("Importing · \(model.importJobs.count - model.activeImportCount) of \(model.importJobs.count)")
                            Spacer()
                            if model.activeImportCount == 0 {
                                Button("Clear") { model.clearFinishedImports() }.textCase(nil)
                            }
                        }
                    }
                    .listRowBackground(Theme.raised)
                }

                Section("On import") {
                    VStack(alignment: .leading, spacing: 2) {
                        LabeledContent("Keep original files", value: "Always")
                        Text("No transcoding. Bit-perfect copies.").font(.caption).foregroundStyle(Theme.secondary)
                    }
                    Toggle(isOn: $model.analyzeOnImport) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Analyze for mixing")
                            Text("BPM, key and loudness. Runs on device.").font(.caption).foregroundStyle(Theme.secondary)
                        }
                    }
                }
                .listRowBackground(Theme.raised)
            }
            .scrollContentBackground(.hidden)
            .background(Theme.surface)
            .navigationTitle("Add Music")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }.fontWeight(.semibold)
                }
            }
            .fileImporter(isPresented: $picking, allowedContentTypes: [.audio, .folder], allowsMultipleSelection: true) { result in
                switch result {
                case .success(let urls): Task { await model.importLocal(urls) }
                case .failure(let error): model.errorMessage = error.localizedDescription
                }
            }
        }
        .presentationDragIndicator(.visible)
    }
}

private struct SourceRow: View {
    let glyph: String
    let title: String
    let detail: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: glyph)
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: 32, height: 32)
                .background(Theme.hairline, in: RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(Theme.text)
                Text(detail).font(.caption).foregroundStyle(Theme.secondary)
            }
        }
        .frame(minHeight: 44)
    }
}

struct ImportJobRow: View {
    let job: ImportJob

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(job.fileName).lineLimit(1)
                Spacer()
                if case .done(let track) = job.stage {
                    Text(track.shortQualityLabel).font(Theme.mono(11)).foregroundStyle(Theme.badge)
                }
            }
            .font(.system(size: 15))
            ProgressView(value: fraction)
                .tint(isDone ? Theme.done : isSkipped ? Theme.secondary : Theme.accent)
            Text(status).font(.caption).foregroundStyle(isFailed ? .red : Theme.secondary)
        }
        .padding(.vertical, 4)
    }

    private var isDone: Bool { if case .done = job.stage { true } else { false } }
    private var isFailed: Bool { if case .failed = job.stage { true } else { false } }
    private var isSkipped: Bool { if case .skipped = job.stage { true } else { false } }

    private var fraction: Double {
        switch job.stage {
        case .copying: 0.25
        case .analyzing: 0.6
        case .done, .skipped, .failed: 1
        }
    }

    private var status: String {
        switch job.stage {
        case .copying: "Copying to library…"
        case .analyzing: "Analyzing tempo and key…"
        case .failed(let message), .skipped(let message): message
        case .done(let track):
            ["Added", track.bpm.map { "\(Int($0.rounded())) BPM" }, track.musicalKey]
                .compactMap { $0 }.joined(separator: " · ")
        }
    }
}
#endif
