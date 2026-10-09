import AVFoundation
import MotifDSP

/// Results of on-device analysis, as stored on a Track.
public struct TrackAnalysis: Sendable, Equatable {
    public var bpm: Double?
    public var loudnessDb: Double?
    /// Camelot notation, e.g. "8A".
    public var musicalKey: String?
    public var waveform: [UInt8]
    /// Seconds to the first downbeat of the beat grid.
    public var firstDownbeat: Double?
}

/// Decodes a file in chunks and runs it through the shared DSP core
/// (core/dsp): tempo, key, loudness and a waveform overview. Memory stays
/// flat regardless of track length.
public enum TrackAnalyzer {
    public enum AnalyzerError: Error { case unsupportedFormat }

    private static let chunkFrames: AVAudioFrameCount = 32_768

    /// Stored with each analysis. 1: the first that finds beat grids.
    public static let version = 1

    /// Synchronous and CPU-bound: call it off the main actor.
    public static func analyze(_ url: URL) throws -> TrackAnalysis {
        let file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: true)
        let format = file.processingFormat
        guard let analyzer = motif_analyzer_new(UInt32(format.sampleRate), format.channelCount),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: chunkFrames)
        else { throw AnalyzerError.unsupportedFormat }
        defer { motif_analyzer_free(analyzer) }

        while file.framePosition < file.length {
            try file.read(into: buffer)
            guard buffer.frameLength > 0, let samples = buffer.floatChannelData?[0] else { break }
            motif_analyzer_push(analyzer, samples, Int(buffer.frameLength) * Int(format.channelCount))
        }

        var result = MotifAnalysis()
        motif_analyzer_finish(analyzer, &result)
        var overview = [Float](repeating: 0, count: LibraryStore.waveformLength)
        overview.withUnsafeMutableBufferPointer { _ = motif_analyzer_overview(analyzer, $0.baseAddress, $0.count) }

        return TrackAnalysis(
            // Unrounded: the beat grid needs the fitted tempo to stay on the beat minutes in.
            bpm: result.bpm > 0 ? Double(result.bpm) : nil,
            loudnessDb: result.rms_db.isFinite ? Double(result.rms_db) : nil,
            musicalKey: result.camelot_number > 0 ? "\(result.camelot_number)\(result.camelot_minor == 1 ? "A" : "B")" : nil,
            waveform: overview.map { UInt8(max(0, min(255, ($0 * 255).rounded()))) },
            firstDownbeat: result.first_downbeat >= 0 && result.bpm > 0 ? Double(result.first_downbeat) : nil
        )
    }
}

/// Gains for a crossfade, from the DSP core so every platform fades identically.
public enum Crossfade {
    public static func equalPowerGains(at t: Double) -> (outgoing: Float, incoming: Float) {
        var a: Float = 1, b: Float = 0
        motif_crossfade_gains(Float(t), UInt32(MOTIF_CURVE_EQUAL_POWER.rawValue), &a, &b)
        return (a, b)
    }
}
