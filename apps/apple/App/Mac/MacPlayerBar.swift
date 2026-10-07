#if os(macOS)
import MotifKit
import SwiftUI

struct MacPlayerBar: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let player = model.player
        HStack(spacing: 16) {
            HStack(spacing: 10) {
                if let track = player.current {
                    ArtTile(seed: track.artSeed, letter: track.monogram, size: 40, radius: 6)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(track.title).fontWeight(.semibold)
                        Text(track.subtitle).font(.system(size: 12)).foregroundStyle(Theme.secondary)
                    }
                    .lineLimit(1)
                } else {
                    Text("Nothing playing").foregroundStyle(Theme.secondary)
                }
            }
            .frame(minWidth: 200, maxWidth: .infinity, alignment: .leading)

            VStack(spacing: 4) {
                HStack(spacing: 6) {
                    Button { player.previous() } label: { Image(systemName: "backward.fill").frame(width: 32, height: 32) }
                        .accessibilityLabel("Previous")
                    Button { player.togglePlayPause() } label: {
                        Image(systemName: player.state == .playing ? "pause.fill" : "play.fill")
                            .foregroundStyle(Theme.macGround)
                            .frame(width: 36, height: 36)
                            .background(Theme.text, in: Circle())
                    }
                    .accessibilityLabel(player.state == .playing ? "Pause" : "Play")
                    Button { player.next() } label: { Image(systemName: "forward.fill").frame(width: 32, height: 32) }
                        .accessibilityLabel("Next")
                }
                HStack(spacing: 8) {
                    Text(formatTime(player.position))
                    WaveformScrubber(values: player.current?.waveform,
                                     progress: player.duration > 0 ? player.position / player.duration : 0,
                                     barCount: 90, unplayedColor: Color(hex: 0x2E3138)) { p in
                        player.seek(to: p * player.duration)
                    }
                    .frame(height: 18)
                    Text("-" + formatTime(max(0, player.duration - player.position)))
                }
                .font(Theme.mono(11))
                .foregroundStyle(Theme.secondary)
            }
            .frame(minWidth: 320, maxWidth: 560)
            .disabled(player.current == nil)

            HStack(spacing: 10) {
                if let track = player.current {
                    Text(track.shortQualityLabel + (track.isLossless ? " · lossless" : ""))
                        .font(Theme.mono(11))
                        .foregroundStyle(Theme.secondary)
                }
                Toggle("Mix", isOn: Binding(get: { player.mixIntoNext }, set: { model.setMixIntoNext($0) }))
                    .toggleStyle(.button)
                    .help(mixDetail(from: player.current, to: player.upNext, enabled: player.mixIntoNext))
                Image(systemName: "speaker.fill").font(.system(size: 11)).foregroundStyle(Theme.secondary)
                Slider(value: Binding(get: { player.volume }, set: { player.volume = $0 }), in: 0...1)
                    .frame(width: 80)
                    .controlSize(.mini)
                    .accessibilityLabel("Volume")
            }
            .frame(minWidth: 200, maxWidth: .infinity, alignment: .trailing)
        }
        .buttonStyle(.plain)
        .foregroundStyle(Theme.text)
        .padding(.horizontal, 20)
        .padding(.vertical, 10)
        .background(Theme.macChrome)
        .overlay(alignment: .top) { Rectangle().fill(Color(hex: 0x26292F)).frame(height: 1) }
    }
}
#endif
