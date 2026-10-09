import MotifKit
import SwiftUI

extension DeckID {
    var color: Color { self == .a ? Theme.deckA : Theme.deckB }
}

/// DJ Mix: stacked deck waveforms, two deck panels (CUE, play, SYNC, tempo,
/// EQ and filter knobs, beat loops), the crossfader with BLEND, and a library
/// browser that loads tracks onto either deck with harmonic-match suggestions.
/// The same screen on iPhone (Mix tab) and Mac (sidebar).
struct DJMixView: View {
    @Environment(AppModel.self) private var model
    @State private var matchesOnly = false

    var body: some View {
        let dj = model.dj
        let reference = dj.lead?.track
        let shown = matchesOnly && reference != nil ? model.tracks.filter { harmonicMatch(reference!, $0) } : model.tracks

        VStack(spacing: 0) {
            VStack(spacing: 6) {
                ForEach(DeckID.allCases) { DeckWaveform(deck: dj[$0]) }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)

            HStack(alignment: .top, spacing: 10) {
                ForEach(DeckID.allCases) { DeckPanel(deck: dj[$0]) }
            }
            .padding(16)

            HStack(spacing: 8) {
                Text("A").font(Theme.mono(13, weight: .bold)).foregroundStyle(Theme.deckA)
                Slider(value: Binding(get: { dj.crossfader }, set: { dj.setCrossfader($0) }), in: 0...1)
                    .tint(Theme.hairline)
                    .accessibilityLabel("Crossfader")
                Text("B").font(Theme.mono(13, weight: .bold)).foregroundStyle(Theme.deckB)
            }
            .padding(.horizontal, 16)

            HStack {
                Text(dj.notice ?? blendLabel(dj.blend))
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.badge)
                    .lineLimit(1)
                Spacer()
                // Blends from the deck being heard into the other, on the beat.
                DeckButton(title: dj.blend == nil ? "BLEND" : "STOP BLEND", selected: dj.blend != nil,
                           color: Theme.accent, enabled: dj.anyPlaying) { dj.toggleBlend() }
            }
            .frame(minHeight: 40)
            .padding(.horizontal, 20)
            .task(id: dj.notice) {
                guard dj.notice != nil else { return }
                try? await Task.sleep(for: .seconds(2.5))
                dj.notice = nil
            }

            HStack {
                Text("Library").font(.system(size: 18, weight: .bold))
                Spacer()
                Toggle("Harmonic match", isOn: $matchesOnly)
                    .toggleStyle(.button)
                    .tint(Theme.accent)
                    .disabled(reference == nil)
            }
            .padding(.leading, 20)
            .padding(.trailing, 16)

            if model.tracks.isEmpty {
                Text("Add music to your library to start mixing.")
                    .foregroundStyle(Theme.secondary)
                    .padding(20)
                Spacer()
            } else {
                List(shown) { track in
                    BrowserRow(track: track, mixWith: reference.flatMap { $0.id == track.id ? nil : $0 })
                        .listRowBackground(Color.clear)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .foregroundStyle(Theme.text)
        .background(Theme.ground)
        .navigationTitle("Mix")
    }

    /// Mixes with the deck's track: compatible key and a tempo SYNC can match (the shared MixMatch rule).
    private func harmonicMatch(_ reference: Track, _ track: Track) -> Bool {
        guard track.id != reference.id else { return false }
        let keyOK = reference.musicalKey == nil || MixMatch.keys(reference, track)
        let bpmOK = reference.bpm == nil || MixMatch.tempos(reference, track)
        return keyOK && bpmOK
    }

    /// "Blending into B · 16 bars on the beat" while BLEND runs.
    private func blendLabel(_ blend: DJBlend?) -> String {
        guard let blend else { return "" }
        return "Blending into \(blend.to.name)" + (blend.bars.map { " · \($0) bars on the beat" } ?? "")
    }
}

private struct DeckWaveform: View {
    @Environment(AppModel.self) private var model
    let deck: DJDeck

    var body: some View {
        HStack(spacing: 8) {
            DeckBadge(id: deck.id)
            WaveformScrubber(values: deck.track?.waveform, progress: deck.progress, barCount: 96,
                             playedColor: deck.id.color) { model.dj.seek(deck.id, to: $0) }
                .frame(height: 36)
            BeatDots(beat: deck.beatInBar, color: deck.id.color)
        }
    }
}

private struct DeckBadge: View {
    let id: DeckID

    var body: some View {
        Text(id.name)
            .font(Theme.mono(12, weight: .bold))
            .foregroundStyle(Theme.onAccent)
            .frame(width: 22, height: 22)
            .background(id.color, in: RoundedRectangle(cornerRadius: 5))
            .accessibilityHidden(true)
    }
}

/// Four dots, the current beat of the bar lit: lining the decks' dots up by eye is how you check a mix.
private struct BeatDots: View {
    let beat: Int?
    let color: Color

    var body: some View {
        Grid(horizontalSpacing: 3, verticalSpacing: 3) {
            ForEach(0..<2, id: \.self) { row in
                GridRow {
                    ForEach(0..<2, id: \.self) { col in
                        Circle().fill(beat == row * 2 + col ? color : Theme.hairline).frame(width: 6, height: 6)
                    }
                }
            }
        }
        .accessibilityElement()
        .accessibilityLabel(beat.map { "Beat \($0 + 1)" } ?? "No beat grid")
    }
}

private struct DeckPanel: View {
    @Environment(AppModel.self) private var model
    let deck: DJDeck

    var body: some View {
        let dj = model.dj
        let id = deck.id
        let loaded = deck.track != nil
        VStack(alignment: .leading, spacing: 6) {
            Text(deck.track?.title ?? "Deck \(id.name)")
                .font(.system(size: 14, weight: .semibold)).lineLimit(1)
            Text(deck.track.map { $0.artist ?? "Unknown artist" } ?? "Load a track below")
                .font(.system(size: 12)).foregroundStyle(Theme.secondary).lineLimit(1)
            HStack(alignment: .lastTextBaseline, spacing: 0) {
                Text(deck.bpm.map { String(format: "%.1f", $0) } ?? "—").font(Theme.mono(20, weight: .bold))
                Text(" BPM").font(Theme.mono(10)).foregroundStyle(Theme.secondary)
                Spacer()
                Text(deck.track?.musicalKey ?? "").font(Theme.mono(13, weight: .bold))
                    .foregroundStyle(Theme.keyColor(deck.track?.musicalKey))
            }
            HStack {
                Text("-" + formatTime(max(0, deck.duration - deck.position))).font(Theme.mono(12)).foregroundStyle(Theme.secondary)
                Spacer()
                // Tap the tempo readout to reset the fader.
                Button { dj.setPitch(id, 0) } label: {
                    Text(String(format: "%+.1f%%", (deck.speed - 1) * 100))
                        .font(Theme.mono(12))
                        .foregroundStyle(abs(deck.speed - 1) < 0.0005 ? Theme.secondary : id.color)
                }
                .buttonStyle(.plain)
                .disabled(!loaded)
                .accessibilityLabel("Reset deck \(id.name) tempo")
            }
            Slider(value: Binding(get: { min(DJEngine.pitchRange, max(-DJEngine.pitchRange, deck.speed - 1)) },
                                  set: { dj.setPitch(id, $0) }),
                   in: -DJEngine.pitchRange...DJEngine.pitchRange)
                .tint(id.color)
                .disabled(!loaded)
                .accessibilityLabel("Deck \(id.name) tempo")
            HStack(spacing: 8) {
                DeckButton(title: "CUE", selected: false, color: id.color, enabled: loaded) { dj.cue(id) }
                Button { dj.togglePlay(id) } label: {
                    Image(systemName: deck.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(Theme.onAccent)
                        .frame(width: 44, height: 44)
                        .background(loaded ? id.color : Theme.hairline, in: Circle())
                }
                .buttonStyle(.plain)
                .disabled(!loaded)
                .accessibilityLabel(deck.isPlaying ? "Pause deck \(id.name)" : "Play deck \(id.name)")
                DeckButton(title: "SYNC", selected: deck.synced, color: id.color, enabled: loaded) { dj.toggleSync(id) }
            }
            HStack {
                Knob(label: "LOW", name: "Deck \(id.name) low", value: deck.fx.low, color: id.color) { v in
                    var fx = deck.fx; fx.low = v; dj.setFX(id, fx)
                }
                Spacer(minLength: 0)
                Knob(label: "MID", name: "Deck \(id.name) mid", value: deck.fx.mid, color: id.color) { v in
                    var fx = deck.fx; fx.mid = v; dj.setFX(id, fx)
                }
                Spacer(minLength: 0)
                Knob(label: "HI", name: "Deck \(id.name) high", value: deck.fx.high, color: id.color) { v in
                    var fx = deck.fx; fx.high = v; dj.setFX(id, fx)
                }
                Spacer(minLength: 0)
                Knob(label: "FILTER", name: "Deck \(id.name) filter", value: deck.fx.filter, color: id.color) { v in
                    var fx = deck.fx; fx.filter = v; dj.setFX(id, fx)
                }
            }
            HStack(spacing: 4) {
                Text("LOOP").font(Theme.mono(9, weight: .bold)).foregroundStyle(Theme.secondary)
                ForEach(DJEngine.loopBeats, id: \.self) { beats in
                    let on = deck.loop?.beats == beats
                    let usable = deck.track?.hasBeatGrid == true
                    Button { dj.toggleLoop(id, beats: beats) } label: {
                        Text("\(Int(beats))")
                            .font(Theme.mono(10, weight: .bold))
                            .foregroundStyle(on ? Theme.onAccent : usable ? Theme.text : Theme.secondary)
                            .frame(maxWidth: .infinity, minHeight: 26)
                            .background(on ? id.color : .clear, in: RoundedRectangle(cornerRadius: 6))
                            .overlay(RoundedRectangle(cornerRadius: 6).stroke(on ? id.color : Theme.hairline))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!usable)
                    .accessibilityLabel("Deck \(id.name) loop \(Int(beats)) \(beats == 1 ? "beat" : "beats")")
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.raised, in: RoundedRectangle(cornerRadius: 14))
    }
}

private struct DeckButton: View {
    let title: String
    let selected: Bool
    let color: Color
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(Theme.mono(11, weight: .bold))
                .foregroundStyle(selected ? Theme.onAccent : enabled ? Theme.text : Theme.secondary)
                .padding(.horizontal, 8)
                .frame(minHeight: 32)
                .background(selected ? color : .clear, in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(selected ? color : Theme.hairline))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A mixer knob, -1...1 with 0 at the top: drag up or down to turn it,
/// double-tap to centre it. The arc runs from the centre to the value.
private struct Knob: View {
    let label: String
    let name: String
    let value: Double
    let color: Color
    let onChange: (Double) -> Void

    @State private var dragStart: Double?
    /// Full left to full right over this much drag.
    private let travel: CGFloat = 80

    var body: some View {
        VStack(spacing: 2) {
            ZStack {
                // 270° of travel, from 7:30 to 4:30, centre at 12.
                Circle().trim(from: 0, to: 0.75)
                    .stroke(Theme.hairline, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(135))
                if abs(value) > 0.005 {
                    Circle().trim(from: value < 0 ? 0.375 * (1 + value) : 0.375, to: value < 0 ? 0.375 : 0.375 * (1 + value))
                        .stroke(color, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(135))
                }
                Capsule().fill(Theme.text).frame(width: 2, height: 10).offset(y: -6)
                    .rotationEffect(.degrees(value * 135))
            }
            .frame(width: 30, height: 30)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 1)
                    .onChanged { drag in
                        let start = dragStart ?? value
                        if dragStart == nil { dragStart = start }
                        onChange(min(1, max(-1, start - 2 * drag.translation.height / travel)))
                    }
                    .onEnded { _ in dragStart = nil }
            )
            .onTapGesture(count: 2) { onChange(0) }
            Text(label).font(Theme.mono(8, weight: .bold)).foregroundStyle(abs(value) > 0.005 ? color : Theme.secondary)
        }
        .accessibilityElement()
        .accessibilityLabel(name)
        .accessibilityValue(abs(value) < 0.005 ? "centre" : String(format: "%+.0f%%", value * 100))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: onChange(min(1, value + 0.1))
            case .decrement: onChange(max(-1, value - 0.1))
            @unknown default: break
            }
        }
    }
}

private struct BrowserRow: View {
    @Environment(AppModel.self) private var model
    let track: Track
    let mixWith: Track?

    var body: some View {
        HStack(spacing: 10) {
            ArtTile(seed: track.artSeed, letter: track.monogram, size: 36, artwork: track.id)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title).font(.system(size: 14)).lineLimit(1)
                Text(track.artist ?? "Unknown artist").font(.system(size: 12)).foregroundStyle(Theme.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            VStack(alignment: .trailing, spacing: 2) {
                MixValue(text: track.bpm.map { "\(Int($0.rounded()))" } ?? "—", color: Theme.bpmColor(track.bpm),
                         match: mixWith.map { MixMatch.tempos($0, track) } ?? false)
                MixValue(text: track.musicalKey ?? "—", color: Theme.keyColor(track.musicalKey),
                         match: mixWith.map { MixMatch.keys($0, track) } ?? false)
            }
            ForEach(DeckID.allCases) { id in
                let loaded = model.dj[id].track?.id == track.id
                Button { Task { await model.dj.load(track, on: id) } } label: {
                    Text(id.name)
                        .font(Theme.mono(12, weight: .bold))
                        .foregroundStyle(loaded ? Theme.onAccent : id.color)
                        .frame(width: 32, height: 32)
                        .background(loaded ? id.color : .clear, in: RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(id.color))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Load \(track.title) on deck \(id.name)")
            }
        }
        .padding(.vertical, 2)
    }
}
