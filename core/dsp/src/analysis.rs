//! Streaming track analysis: peak, RMS loudness, tempo and key.
//!
//! Audio is pushed in chunks, so a whole track never has to sit in memory.
//! Only a per-hop energy envelope is retained (one f32 per `FINE_HOP` frames,
//! roughly 1.4 MB for an hour of 44.1 kHz audio).

/// Frames per hop of the tempo and overview envelope.
pub const HOP: usize = 512;
/// Frames per hop of the envelope the beat grid is fitted to (about 3 ms).
pub const FINE_HOP: usize = 128;
const FINE_PER_HOP: usize = HOP / FINE_HOP;

use crate::beatgrid;
use crate::key::{Key, KeyDetector};

const MIN_BPM: f32 = 70.0;
const MAX_BPM: f32 = 180.0;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Analysis {
    /// Sample peak in dBFS (`-inf` for silence).
    pub peak_db: f32,
    /// RMS level in dBFS across all channels (`-inf` for silence).
    pub rms_db: f32,
    /// Estimated tempo, or 0 when no stable beat was found. Refined by the
    /// beat grid fit when one was found.
    pub bpm: f32,
    /// Seconds from the start of the track to the first downbeat of the beat
    /// grid; beats follow every `60 / bpm` seconds. `None` without a grid.
    pub first_downbeat: Option<f32>,
    pub key: Option<Key>,
}

pub struct Analyzer {
    sample_rate: u32,
    channels: usize,
    peak: f32,
    sum_squares: f64,
    samples_seen: u64,
    hop_energy: f64,
    hop_fill: usize,
    envelope: Vec<f32>,
    key: KeyDetector,
}

impl Analyzer {
    pub fn new(sample_rate: u32, channels: u32) -> Self {
        Self {
            sample_rate,
            channels: channels.max(1) as usize,
            peak: 0.0,
            sum_squares: 0.0,
            samples_seen: 0,
            hop_energy: 0.0,
            hop_fill: 0,
            envelope: Vec::new(),
            key: KeyDetector::new(sample_rate),
        }
    }

    /// Push interleaved samples. A trailing partial frame is ignored.
    pub fn push(&mut self, interleaved: &[f32]) {
        let ch = self.channels;
        for frame in interleaved.chunks_exact(ch) {
            let mut mono = 0.0f32;
            for &s in frame {
                self.peak = self.peak.max(s.abs());
                self.sum_squares += (s as f64) * (s as f64);
                mono += s;
            }
            self.samples_seen += ch as u64;
            mono /= ch as f32;
            self.key.push(mono);
            self.hop_energy += (mono as f64) * (mono as f64);
            self.hop_fill += 1;
            if self.hop_fill == FINE_HOP {
                self.envelope.push(self.hop_energy as f32);
                self.hop_energy = 0.0;
                self.hop_fill = 0;
            }
        }
    }

    pub fn finish(&self) -> Analysis {
        let rms = if self.samples_seen == 0 {
            0.0
        } else {
            (self.sum_squares / self.samples_seen as f64).sqrt() as f32
        };
        let coarse = self.coarse_envelope();
        let estimate = estimate_bpm(&coarse, self.sample_rate as f32 / HOP as f32);
        let grid = beatgrid::fit(&self.envelope, self.sample_rate as f64 / FINE_HOP as f64, estimate as f64);
        Analysis {
            peak_db: to_db(self.peak),
            rms_db: to_db(rms),
            bpm: grid.map_or(estimate, |g| g.bpm as f32),
            first_downbeat: grid.map(|g| g.first_downbeat as f32),
            key: self.key.finish(),
        }
    }

    /// Energy per `HOP` frames, summed from the fine envelope.
    fn coarse_envelope(&self) -> Vec<f32> {
        self.envelope.chunks_exact(FINE_PER_HOP).map(|c| c.iter().sum()).collect()
    }
}

impl Analyzer {
    /// Loudness overview for drawing a waveform: `buckets` values in 0...1,
    /// each the RMS of its slice of the track, normalised to the loudest slice.
    pub fn overview(&self, buckets: usize) -> Vec<f32> {
        let envelope = self.coarse_envelope();
        if buckets == 0 || envelope.is_empty() {
            return vec![0.0; buckets];
        }
        let n = envelope.len();
        let mut out: Vec<f32> = (0..buckets)
            .map(|b| {
                let (start, end) = (b * n / buckets, ((b + 1) * n / buckets).max(b * n / buckets + 1).min(n));
                if start >= n {
                    return 0.0;
                }
                let energy: f32 = envelope[start..end].iter().sum();
                (energy / ((end - start) * HOP) as f32).sqrt()
            })
            .collect();
        let max = out.iter().cloned().fold(0.0f32, f32::max);
        if max > 0.0 {
            out.iter_mut().for_each(|v| *v /= max);
        }
        out
    }
}

fn to_db(amplitude: f32) -> f32 {
    if amplitude <= 0.0 {
        f32::NEG_INFINITY
    } else {
        20.0 * amplitude.log10()
    }
}

/// Tempo from the autocorrelation of the half-wave rectified energy flux.
fn estimate_bpm(envelope: &[f32], hops_per_second: f32) -> f32 {
    if envelope.len() < 4 {
        return 0.0;
    }
    let mut flux: Vec<f32> = envelope
        .windows(2)
        .map(|w| (w[1] - w[0]).max(0.0))
        .collect();
    let mean = flux.iter().sum::<f32>() / flux.len() as f32;
    for v in &mut flux {
        *v -= mean;
    }

    let min_lag = (hops_per_second * 60.0 / MAX_BPM).floor().max(1.0) as usize;
    let max_lag = (hops_per_second * 60.0 / MIN_BPM).ceil() as usize;
    if max_lag + 1 >= flux.len() {
        return 0.0;
    }

    let acf = |lag: usize| -> f32 {
        flux.iter()
            .zip(&flux[lag..])
            .map(|(a, b)| a * b)
            .sum::<f32>()
            / (flux.len() - lag) as f32
    };
    let scores: Vec<f32> = (min_lag - 1..=max_lag + 1).map(acf).collect();

    let (best_i, &best) = scores[1..scores.len() - 1]
        .iter()
        .enumerate()
        .max_by(|a, b| a.1.total_cmp(b.1))
        .map(|(i, v)| (i + 1, v))
        .unwrap();
    if best <= 0.0 {
        return 0.0;
    }

    // Parabolic interpolation around the peak for sub-hop precision.
    let (l, c, r) = (scores[best_i - 1], scores[best_i], scores[best_i + 1]);
    let denom = l - 2.0 * c + r;
    let offset = if denom.abs() > f32::EPSILON { 0.5 * (l - r) / denom } else { 0.0 };
    let lag = (min_lag - 1 + best_i) as f32 + offset;
    60.0 * hops_per_second / lag
}

#[cfg(test)]
mod tests {
    use super::*;

    fn click_track(bpm: f32, sample_rate: u32, seconds: f32, channels: usize) -> Vec<f32> {
        let frames = (sample_rate as f32 * seconds) as usize;
        let period = (sample_rate as f32 * 60.0 / bpm) as usize;
        let click_len = sample_rate as usize / 100;
        let mut out = vec![0.0; frames * channels];
        for f in 0..frames {
            let pos = f % period;
            if pos < click_len {
                let decay = 1.0 - pos as f32 / click_len as f32;
                let s = 0.8 * decay * (pos as f32 * 0.3).sin();
                for c in 0..channels {
                    out[f * channels + c] = s;
                }
            }
        }
        out
    }

    #[test]
    fn detects_tempo_of_click_track() {
        for &bpm in &[90.0f32, 120.0, 128.0, 174.0] {
            let audio = click_track(bpm, 44_100, 30.0, 2);
            let mut a = Analyzer::new(44_100, 2);
            for chunk in audio.chunks(4096) {
                a.push(chunk);
            }
            let got = a.finish().bpm;
            assert!((got - bpm).abs() < 1.5, "expected {bpm}, got {got}");
        }
    }

    #[test]
    fn silence_has_no_tempo_and_negative_infinite_level() {
        let mut a = Analyzer::new(48_000, 2);
        a.push(&vec![0.0; 48_000 * 2 * 5]);
        let r = a.finish();
        assert_eq!(r.bpm, 0.0);
        assert_eq!(r.peak_db, f32::NEG_INFINITY);
        assert_eq!(r.rms_db, f32::NEG_INFINITY);
    }

    #[test]
    fn overview_tracks_loudness() {
        let mut a = Analyzer::new(44_100, 1);
        a.push(&vec![0.1; 44_100]);
        a.push(&vec![0.4; 44_100]);
        let o = a.overview(4);
        assert_eq!(o.len(), 4);
        assert!((o[3] - 1.0).abs() < 1e-3);
        assert!((o[0] - 0.25).abs() < 1e-2);
        assert_eq!(Analyzer::new(44_100, 1).overview(3), vec![0.0; 3]);
    }

    #[test]
    fn full_scale_square_is_zero_db() {
        let mut a = Analyzer::new(44_100, 1);
        let sq: Vec<f32> = (0..44_100).map(|i| if i % 100 < 50 { 1.0 } else { -1.0 }).collect();
        a.push(&sq);
        let r = a.finish();
        assert!(r.peak_db.abs() < 1e-4);
        assert!(r.rms_db.abs() < 1e-4);
    }
}
