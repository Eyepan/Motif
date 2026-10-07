import MotifKit
import SwiftUI

/// Colours and type from the approved design (/mnt/project-files/design/apple-ui-decisions.md).
enum Theme {
    static let ground = Color(hex: 0x0B0C0F)
    static let nowPlayingGround = Color(hex: 0x10161F)
    static let macGround = Color(hex: 0x121317)
    static let macChrome = Color(hex: 0x1A1C21)
    static let surface = Color(hex: 0x16181D)
    static let raised = Color(hex: 0x1F2229)
    static let hairline = Color(hex: 0x2A2E37)
    static let text = Color(hex: 0xF2F3F5)
    static let secondary = Color(hex: 0x9AA0AB)
    static let badge = Color(hex: 0xC9CDD4)
    static let done = Color(hex: 0x9FD3C7)

    /// Neon green, Pan's pick. Text on accent fills uses `onAccent`.
    static let accent = Color(hex: 0xC6F432)
    static let onAccent = Color(hex: 0x0B0C0F)
    static let deckA = Color(hex: 0x5B9BFF)
    static let deckB = accent

    /// BPM, key, format and times.
    static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        .system(size: size, weight: weight, design: .monospaced)
    }

    /// Camelot key colours from `schemas/mix-colours.json`: neighbours on the
    /// wheel get neighbouring hues, majors (B) are lighter than their relative minors (A).
    static let keyColors: [String: UInt32] = [
        "1A": 0xEF958E, "1B": 0xFDB5AF,
        "2A": 0xE79E6B, "2B": 0xFEB98B,
        "3A": 0xD1AC5A, "3B": 0xF1C45E,
        "4A": 0xAFB965, "4B": 0xC8D56C,
        "5A": 0x84C485, "5B": 0x93E195,
        "6A": 0x58C8AD, "6B": 0x5AE6C6,
        "7A": 0x47C5D2, "7B": 0x40E2F3,
        "8A": 0x64BCED, "8B": 0x8FD5FD,
        "9A": 0x8FB0F8, "9B": 0xB3CAFC,
        "10A": 0xB6A3F0, "10B": 0xCEC0FD,
        "11A": 0xD599D8, "11B": 0xF5ADF9,
        "12A": 0xE893B5, "12B": 0xFDB1CE,
    ]

    /// BPM colours from `schemas/mix-colours.json`, one per 1/24 octave from 120 BPM.
    static let bpmColors: [UInt32] = [
        0xF8A49D, 0xF6A88D, 0xF0AD7F, 0xE7B375, 0xDBB970, 0xCDC072,
        0xBBC679, 0xA8CB86, 0x95CF96, 0x82D2A8, 0x71D3BA, 0x67D2CC,
        0x65D0DC, 0x6CCDEA, 0x7AC8F5, 0x8CC3FC, 0x9EBDFF, 0xB1B7FD,
        0xC2B1F8, 0xD2ACEE, 0xDFA8E1, 0xEAA4D2, 0xF2A3C1, 0xF6A3AF,
    ]

    static func keyColor(_ camelot: String?) -> Color {
        guard let camelot, let hex = keyColors[camelot] else { return secondary }
        return Color(hex: hex)
    }

    /// Tempos a few BPM apart get neighbouring hues; half and double time share a colour.
    static func bpmColor(_ bpm: Double?) -> Color {
        guard let bpm, bpm > 0 else { return secondary }
        let bin = Int((24 * log2(bpm / 120)).rounded())
        return Color(hex: bpmColors[((bin % 24) + 24) % 24])
    }

    /// Flat art placeholder colours (background, letter), stable per album.
    static func artColors(for seed: String) -> (Color, Color) {
        let pairs: [(UInt32, UInt32)] = [
            (0x2D4B73, 0xBFD6F5), (0x6B3A2E, 0xF3C9B8), (0x3E5A3A, 0xCFE3C4),
            (0x4A3D6B, 0xD9CCF5), (0x5E5A2E, 0xECE6B5), (0x2E5A5E, 0xB5E6EC),
        ]
        // djb2: String.hashValue changes every launch.
        let hash = seed.unicodeScalars.reduce(UInt32(5381)) { ($0 &<< 5) &+ $0 &+ $1.value }
        let pair = pairs[Int(hash % UInt32(pairs.count))]
        return (Color(hex: pair.0), Color(hex: pair.1))
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: 1)
    }
}

extension Track {
    var artSeed: String { album ?? artist ?? title }
    var monogram: String { String(title.first ?? "♪").uppercased() }
    var bpmText: String { bpm.map { "\(Int($0.rounded())) BPM" } ?? "— BPM" }
    var subtitle: String { [artist, album].compactMap { $0 }.joined(separator: " · ") }

    // Non-optional sort keys for tables.
    var sortTitle: String { title.localizedLowercase }
    var sortArtist: String { (artist ?? "").localizedLowercase }
    var sortAlbum: String { (album ?? "").localizedLowercase }
    var sortBPM: Double { bpm ?? .infinity }
    var sortKey: Int {
        guard let key = musicalKey, let n = Int(key.dropLast()) else { return .max }
        return n * 2 + (key.hasSuffix("B") ? 1 : 0)
    }
}

func formatTime(_ seconds: TimeInterval) -> String {
    guard seconds.isFinite else { return "0:00" }
    let s = Int(seconds.rounded(.down))
    return String(format: "%d:%02d", s / 60, s % 60)
}

/// Album art, or a flat colour tile with the title's first letter when there is none.
struct ArtTile: View {
    let seed: String
    let letter: String
    var size: CGFloat = 44
    var radius: CGFloat = 6
    /// The track whose art to show, if it has any.
    var artwork: UUID?

    @Environment(AppModel.self) private var model
    @Environment(\.displayScale) private var scale

    var body: some View {
        let (bg, fg) = Theme.artColors(for: seed)
        // Reading the revision redraws the tile when art arrives for a track already on screen.
        let image = model.artworkRevision >= 0
            ? artwork.flatMap { model.store.artwork.image(for: $0, maxPixels: Int(size * scale)) } : nil
        RoundedRectangle(cornerRadius: radius)
            .fill(bg)
            .frame(width: size, height: size)
            .overlay {
                if let image {
                    Image(decorative: image, scale: scale).resizable().scaledToFill()
                } else {
                    Text(letter).font(.system(size: size / 2.6, weight: .bold)).foregroundStyle(fg)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: radius))
            .accessibilityHidden(true)
    }
}

/// BPM or key in its colour; boxed when it mixes well with what's playing.
struct MixValue: View {
    let text: String
    let color: Color
    var match = false

    var body: some View {
        Text(text)
            .font(Theme.mono(12, weight: match ? .bold : .regular))
            .foregroundStyle(color)
            .padding(.horizontal, match ? 4 : 0)
            .background {
                if match {
                    RoundedRectangle(cornerRadius: 3).fill(color.opacity(0.14))
                    RoundedRectangle(cornerRadius: 3).stroke(color, lineWidth: 1)
                }
            }
            .accessibilityLabel(match ? "\(text), mixes well" : text)
    }
}

struct FormatBadge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .tracking(0.4)
            .foregroundStyle(Theme.badge)
            .padding(.horizontal, 4)
            .padding(.vertical, 1)
            .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(hex: 0x3A3F4A)))
    }
}

/// Bar waveform from the analysed overview; the played part is drawn in the accent.
/// Drag or tap to seek. Falls back to flat bars before a track is analysed.
struct WaveformScrubber: View {
    let values: [UInt8]?
    let progress: Double
    var barCount = 60
    var playedColor = Theme.accent
    var unplayedColor = Color(hex: 0x2E3746)
    let onSeek: (Double) -> Void

    @State private var dragProgress: Double?

    var body: some View {
        GeometryReader { geo in
            Canvas { context, size in
                let shown = dragProgress ?? progress
                let gap: CGFloat = 2
                let width = max(1, (size.width - gap * CGFloat(barCount - 1)) / CGFloat(barCount))
                for i in 0..<barCount {
                    let level = level(at: i)
                    let h = max(3, size.height * level)
                    let rect = CGRect(x: CGFloat(i) * (width + gap), y: (size.height - h) / 2, width: width, height: h)
                    let played = Double(i) / Double(barCount) < shown
                    context.fill(Path(roundedRect: rect, cornerRadius: 1), with: .color(played ? playedColor : unplayedColor))
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { dragProgress = min(1, max(0, $0.location.x / geo.size.width)) }
                    .onEnded { _ in
                        if let p = dragProgress { onSeek(p) }
                        dragProgress = nil
                    }
            )
        }
        .accessibilityElement()
        .accessibilityLabel("Playback position")
        .accessibilityValue("\(Int(progress * 100)) percent")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: onSeek(min(1, progress + 0.05))
            case .decrement: onSeek(max(0, progress - 0.05))
            @unknown default: break
            }
        }
    }

    private func level(at bar: Int) -> CGFloat {
        guard let values, !values.isEmpty else { return 0.35 }
        let start = bar * values.count / barCount
        let end = max(start + 1, (bar + 1) * values.count / barCount)
        let slice = values[start..<min(end, values.count)]
        let peak = slice.max() ?? 0
        return 0.15 + 0.85 * CGFloat(peak) / 255
    }
}

/// "122 to 124 BPM · 8A to 9A · 16-bar blend", or what Mix into next does when off.
func mixDetail(from current: Track?, to next: Track?, enabled: Bool) -> String {
    guard enabled else { return "Off · plays straight through" }
    guard let current, let next else { return "On · nothing queued next" }
    var parts: [String] = []
    if let a = current.bpm, let b = next.bpm { parts.append("\(Int(a.rounded())) to \(Int(b.rounded())) BPM") }
    if let a = current.musicalKey, let b = next.musicalKey { parts.append("\(a) to \(b)") }
    let length = PlaybackEngine.mixLength(for: current)
    if let bpm = current.bpm {
        parts.append("\(Int((bpm * length / 240).rounded()))-bar blend")
    } else {
        parts.append("\(Int(length)) s blend")
    }
    return parts.joined(separator: " · ")
}
