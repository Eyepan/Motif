import MotifKit
import SwiftUI

struct NowPlayingBar: View {
    @Environment(AppModel.self) private var model
    @State private var scrubbing: Double?

    var body: some View {
        let player = model.player
        VStack(spacing: 6) {
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(player.current?.title ?? "").font(.subheadline.weight(.semibold))
                    Text(player.current?.qualityLabel ?? "").font(.caption2.monospaced()).foregroundStyle(.secondary)
                }
                .lineLimit(1)
                Spacer()
                Button { player.previous() } label: { Image(systemName: "backward.fill") }
                Button { player.togglePlayPause() } label: {
                    Image(systemName: player.state == .playing ? "pause.fill" : "play.fill").font(.title2)
                }
                Button { player.next() } label: { Image(systemName: "forward.fill") }
            }
            .buttonStyle(.borderless)
            Slider(
                value: Binding(get: { scrubbing ?? player.position }, set: { scrubbing = $0 }),
                in: 0...max(player.duration, 1)
            ) { editing in
                if !editing, let target = scrubbing {
                    player.seek(to: target)
                    scrubbing = nil
                }
            }
            .controlSize(.mini)
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(.bar)
    }
}
