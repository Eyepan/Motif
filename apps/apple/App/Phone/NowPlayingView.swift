#if os(iOS)
import AVKit
import MotifKit
import SwiftUI

struct NowPlayingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var showQueue = false

    var body: some View {
        let player = model.player
        VStack(spacing: 20) {
            HStack {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.down").font(.system(size: 22, weight: .semibold)).frame(width: 44, height: 44)
                }
                .accessibilityLabel("Close")
                Spacer()
                Label("On this iPhone", systemImage: "checkmark")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.badge)
                    .labelStyle(OfflineLabelStyle())
                Spacer()
                Button { showQueue = true } label: {
                    Image(systemName: "ellipsis").font(.system(size: 20)).frame(width: 44, height: 44)
                }
                .accessibilityLabel("Queue")
            }
            .foregroundStyle(Theme.badge)

            if let track = player.current {
                ArtTile(seed: track.artSeed, letter: track.monogram, size: 334, radius: 14, artwork: track.id)
                    .shadow(color: .black.opacity(0.55), radius: 30, y: 24)
                    .scaleEffect(player.state == .playing ? 1 : 0.86)
                    .animation(.easeOut(duration: 0.35), value: player.state)
                    .frame(maxWidth: .infinity)

                VStack(alignment: .leading, spacing: 4) {
                    Text(track.title).font(.system(size: 22, weight: .bold))
                    Text(track.subtitle).font(.system(size: 17)).foregroundStyle(Color(hex: 0xB5BAC3))
                }
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)

                VStack(spacing: 6) {
                    WaveformScrubber(values: track.waveform,
                                     progress: player.duration > 0 ? player.position / player.duration : 0) { p in
                        player.seek(to: p * player.duration)
                    }
                    .frame(height: 40)
                    HStack {
                        Text(formatTime(player.position))
                        Spacer()
                        Text(track.qualityLabel).foregroundStyle(Theme.badge)
                        Spacer()
                        Text("-" + formatTime(max(0, player.duration - player.position)))
                    }
                    .font(Theme.mono(12))
                    .foregroundStyle(Theme.secondary)
                }
            }

            HStack {
                Button { player.previous() } label: { Image(systemName: "backward.fill").font(.system(size: 30)).frame(width: 56, height: 56) }
                    .accessibilityLabel("Previous")
                Spacer()
                Button { player.togglePlayPause() } label: {
                    Image(systemName: player.state == .playing ? "pause.fill" : "play.fill").font(.system(size: 44)).frame(width: 76, height: 76)
                }
                .accessibilityLabel(player.state == .playing ? "Pause" : "Play")
                Spacer()
                Button { player.next() } label: { Image(systemName: "forward.fill").font(.system(size: 30)).frame(width: 56, height: 56) }
                    .accessibilityLabel("Next")
            }
            .padding(.horizontal, 18)

            HStack(spacing: 10) {
                Image(systemName: "speaker.fill").font(.system(size: 13))
                Slider(value: Binding(get: { player.volume }, set: { player.volume = $0 }), in: 0...1)
                    .tint(Theme.badge)
                    .accessibilityLabel("Volume")
                Image(systemName: "speaker.wave.3.fill").font(.system(size: 13))
            }
            .foregroundStyle(Theme.secondary)

            MixToggleRow()

            HStack {
                Spacer()
                AirPlayButton().frame(width: 44, height: 44)
                Spacer()
                Button { showQueue = true } label: { Image(systemName: "list.bullet").font(.system(size: 20)).frame(width: 44, height: 44) }
                    .accessibilityLabel("Queue")
                Spacer()
            }
            .foregroundStyle(Theme.secondary)
            .padding(.top, 4)
        }
        .buttonStyle(.plain)
        .foregroundStyle(Theme.text)
        .padding(.horizontal, 28)
        .padding(.top, 8)
        .padding(.bottom, 12)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Theme.nowPlayingGround)
        .sheet(isPresented: $showQueue) { QueueSheet() }
        .onChange(of: player.current == nil) { _, empty in if empty { dismiss() } }
    }
}

struct MixToggleRow: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let player = model.player
        let on = player.mixIntoNext
        Toggle(isOn: Binding(get: { on }, set: { model.setMixIntoNext($0) })) {
            HStack(spacing: 12) {
                Image(systemName: "dial.medium")
                    .font(.system(size: 20))
                    .foregroundStyle(on ? Theme.accent : Color(hex: 0x6B7280))
                VStack(alignment: .leading, spacing: 2) {
                    Text(player.upNext.map { "Mix into next: \($0.title)" } ?? "Mix into next")
                        .font(.system(size: 15, weight: .semibold))
                        .lineLimit(1)
                    Text(player.isMixing ? "Blending now" : mixDetail(from: player.current, to: player.upNext, enabled: on))
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.secondary)
                }
            }
        }
        .toggleStyle(.switch)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(Color(hex: 0x18202B), in: RoundedRectangle(cornerRadius: 14))
    }
}

private struct OfflineLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 6) {
            configuration.icon.foregroundStyle(Theme.done).font(.system(size: 12, weight: .bold))
            configuration.title
        }
    }
}

private struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let view = AVRoutePickerView()
        view.tintColor = UIColor(Theme.secondary)
        view.activeTintColor = UIColor(Theme.accent)
        return view
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

struct QueueSheet: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let player = model.player
        NavigationStack {
            List {
                ForEach(Array(player.queue.enumerated()), id: \.element.id) { index, track in
                    Button { player.play(player.queue, startAt: index, context: .queue) } label: {
                        TrackRow(track: track, isCurrent: index == player.currentIndex)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Theme.surface)
            .navigationTitle("Up Next")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }
}
#endif
