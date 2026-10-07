//! Beat grid: a constant tempo plus the position of the first downbeat, fitted
//! to a track's onsets so two tracks can be blended beat on beat.
//!
//! The tempo estimate from autocorrelation is good to about a beat per minute,
//! which drifts by a whole beat within a few minutes. The fit folds the onset
//! curve at candidate periods around that estimate and keeps the period whose
//! fold has the sharpest peak; the peak's position is the beat phase. Bars are
//! assumed to be four beats, starting on the beat with the strongest onsets.

/// Beats per bar. Motif assumes 4/4.
pub const BEATS_PER_BAR: usize = 4;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Grid {
    pub bpm: f64,
    /// Seconds from the start of the track to the first downbeat, within the
    /// first bar.
    pub first_downbeat: f64,
}

impl Grid {
    pub fn beat_length(&self) -> f64 {
        60.0 / self.bpm
    }

    pub fn bar_length(&self) -> f64 {
        self.beat_length() * BEATS_PER_BAR as f64
    }
}

/// How far either side of the estimate the tempo search goes, in BPM.
const SEARCH_BPM: f64 = 1.5;
/// A fold whose peak is less than this times its mean has no clear beat.
const MIN_PEAKINESS: f64 = 1.6;

/// Fits a grid to an energy envelope (one value per hop, `hops_per_second`
/// hops a second) given a tempo estimate. `None` when there is no estimate or
/// no clear beat.
pub fn fit(envelope: &[f32], hops_per_second: f64, estimate_bpm: f64) -> Option<Grid> {
    if estimate_bpm.is_nan() || estimate_bpm <= 0.0 || envelope.len() < 8 {
        return None;
    }
    let onsets = onset_strength(envelope);
    let duration = envelope.len() as f64 / hops_per_second;
    if duration < 4.0 * 60.0 / estimate_bpm {
        return None;
    }

    // Coarse then fine search over tempo.
    let mut best = Fold::at(&onsets, hops_per_second, estimate_bpm);
    for (step, span) in [(0.05, SEARCH_BPM), (0.005, 0.05)] {
        let centre = best.bpm;
        let n = (span / step).round() as i32;
        for i in -n..=n {
            let bpm = centre + i as f64 * step;
            if bpm <= 0.0 {
                continue;
            }
            let fold = Fold::at(&onsets, hops_per_second, bpm);
            if fold.peakiness > best.peakiness {
                best = fold;
            }
        }
    }
    if best.peakiness < MIN_PEAKINESS {
        return None;
    }

    let beat = 60.0 / best.bpm;
    let first_beat = best.phase * beat;
    let downbeat = strongest_beat_in_bar(&onsets, hops_per_second, first_beat, beat);
    let bar = beat * BEATS_PER_BAR as f64;
    Some(Grid { bpm: best.bpm, first_downbeat: (first_beat + downbeat as f64 * beat) % bar })
}

/// Log-compressed, half-wave rectified energy rise, normalised to the track's
/// mean energy so loudness doesn't change the result. `onsets[i]` is the rise
/// into hop `i`.
fn onset_strength(envelope: &[f32]) -> Vec<f32> {
    let mean = envelope.iter().map(|&e| e as f64).sum::<f64>() / envelope.len() as f64;
    if mean <= 0.0 {
        return vec![0.0; envelope.len()];
    }
    let compress = |e: f32| (1.0 + 10.0 * e as f64 / mean).ln() as f32;
    let mut out = Vec::with_capacity(envelope.len());
    out.push(0.0);
    out.extend(envelope.windows(2).map(|w| (compress(w[1]) - compress(w[0])).max(0.0)));
    out
}

struct Fold {
    bpm: f64,
    /// Where in the beat the onsets peak, 0...1.
    phase: f64,
    /// Peak of the fold over its mean.
    peakiness: f64,
}

impl Fold {
    /// Folds the onset curve at the beat period for `bpm` into one beat's
    /// worth of bins.
    fn at(onsets: &[f32], hops_per_second: f64, bpm: f64) -> Self {
        let period = hops_per_second * 60.0 / bpm;
        let bins = period.round().max(3.0) as usize;
        let mut hist = vec![0.0f64; bins];
        for (i, &o) in onsets.iter().enumerate() {
            if o > 0.0 {
                // Onsets land on the hop they rise into; use the hop's start.
                let phase = (i as f64 / period).fract();
                hist[((phase * bins as f64) as usize).min(bins - 1)] += o as f64;
            }
        }
        // Light circular smoothing so an onset split across two bins still peaks.
        let smooth: Vec<f64> = (0..bins)
            .map(|b| hist[(b + bins - 1) % bins] + 2.0 * hist[b] + hist[(b + 1) % bins])
            .collect();
        let mean = smooth.iter().sum::<f64>() / bins as f64;
        let (peak_bin, &peak) = smooth
            .iter()
            .enumerate()
            .max_by(|a, b| a.1.total_cmp(b.1))
            .unwrap();
        if mean <= 0.0 {
            return Self { bpm, phase: 0.0, peakiness: 0.0 };
        }
        // Parabolic interpolation for sub-bin phase.
        let (l, r) = (smooth[(peak_bin + bins - 1) % bins], smooth[(peak_bin + 1) % bins]);
        let denom = l - 2.0 * peak + r;
        let offset = if denom.abs() > f64::EPSILON { 0.5 * (l - r) / denom } else { 0.0 };
        let phase = ((peak_bin as f64 + offset) / bins as f64).rem_euclid(1.0);
        Self { bpm, phase, peakiness: peak / mean }
    }
}

/// Which beat of the bar (0...3, counted from `first_beat`) has the strongest
/// onsets across the track.
fn strongest_beat_in_bar(onsets: &[f32], hops_per_second: f64, first_beat: f64, beat: f64) -> usize {
    let mut sums = [0.0f64; BEATS_PER_BAR];
    let mut k = 0usize;
    loop {
        let centre = ((first_beat + k as f64 * beat) * hops_per_second).round() as isize;
        if centre as usize >= onsets.len() {
            break;
        }
        let lo = (centre - 2).max(0) as usize;
        let hi = ((centre + 3) as usize).min(onsets.len());
        sums[k % BEATS_PER_BAR] += onsets[lo..hi].iter().cloned().fold(0.0f32, f32::max) as f64;
        k += 1;
    }
    (0..BEATS_PER_BAR).max_by(|&a, &b| sums[a].total_cmp(&sums[b])).unwrap()
}

#[cfg(test)]
mod tests {
    use crate::analysis::Analyzer;

    /// Clicks every beat from `offset` seconds, with every fourth one louder.
    pub(crate) fn accented_clicks(bpm: f64, offset: f64, seconds: f64, sample_rate: u32) -> Vec<f32> {
        let sr = sample_rate as f64;
        let mut out = vec![0.0f32; (sr * seconds) as usize];
        let click_len = sample_rate as usize / 100;
        let mut k = 0usize;
        loop {
            let start = ((offset + k as f64 * 60.0 / bpm) * sr).round() as usize;
            if start >= out.len() {
                break;
            }
            let amp = if k.is_multiple_of(4) { 0.9 } else { 0.35 };
            let end = (start + click_len).min(out.len());
            for (i, s) in out[start..end].iter_mut().enumerate() {
                *s += amp * (1.0 - i as f32 / click_len as f32) * (i as f32 * 0.3).sin();
            }
            k += 1;
        }
        out
    }

    fn analyze(audio: &[f32], sample_rate: u32) -> crate::analysis::Analysis {
        let mut a = Analyzer::new(sample_rate, 1);
        for chunk in audio.chunks(4096) {
            a.push(chunk);
        }
        a.finish()
    }

    #[test]
    fn fits_tempo_and_downbeat_of_accented_clicks() {
        for &(bpm, offset) in &[(124.0, 0.0), (128.0, 0.73), (90.0, 1.9), (174.0, 0.21), (121.37, 0.5)] {
            let r = analyze(&accented_clicks(bpm, offset, 90.0, 44_100), 44_100);
            assert!((r.bpm as f64 - bpm).abs() < 0.03, "bpm {bpm}: got {}", r.bpm);
            let bar = 240.0 / bpm;
            let want = offset % bar;
            let got = r.first_downbeat.expect("grid") as f64;
            let err = (got - want + bar / 2.0).rem_euclid(bar) - bar / 2.0;
            assert!(err.abs() < 0.006, "bpm {bpm} offset {offset}: downbeat {got}, want {want}");
        }
    }

    #[test]
    fn grid_stays_on_the_beat_at_the_end_of_a_long_track() {
        let (bpm, offset) = (126.4, 0.31);
        let r = analyze(&accented_clicks(bpm, offset, 360.0, 48_000), 48_000);
        let grid = super::Grid { bpm: r.bpm as f64, first_downbeat: r.first_downbeat.unwrap() as f64 };
        // Where the grid puts beat 700, against where the click really is.
        let predicted = grid.first_downbeat + 700.0 * grid.beat_length();
        let actual = offset + 700.0 * 60.0 / bpm;
        assert!((predicted - actual).abs() < 0.015, "drifted {} s", predicted - actual);
    }

    #[test]
    fn no_grid_without_a_beat() {
        assert_eq!(super::fit(&[0.0; 4000], 344.5, 120.0), None);
        assert_eq!(super::fit(&[1.0; 4000], 344.5, 0.0), None);
        // Steady noise-like energy has no beat.
        let noise: Vec<f32> = (0..40_000u32).map(|i| 1.0 + ((i.wrapping_mul(2_654_435_761) >> 16) % 100) as f32 / 1000.0).collect();
        assert_eq!(super::fit(&noise, 344.5, 120.0), None);
    }
}
