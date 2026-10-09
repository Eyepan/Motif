//! A DJ mixer channel's tone controls for one deck: a three-band EQ (low,
//! mid and high knobs that cut to -26 dB like club mixers, or boost up to
//! +6 dB) and a one-knob filter that sweeps a low-pass to the left of centre
//! and a high-pass to the right.
//!
//! Knobs go from -1 to 1 with 0 flat. [bands] turns knob positions into
//! filter settings, so a platform with its own EQ unit (Apple's
//! AVAudioUnitEQ) sounds the same as [DeckFx], which runs them as biquads
//! (RBJ cookbook) for platforms that process samples themselves (Android).

use std::f64::consts::{FRAC_1_SQRT_2, PI};

/// Gain at a knob turned fully left: the "kill".
pub const KILL_DB: f64 = -26.0;
/// Gain at a knob turned fully right.
pub const BOOST_DB: f64 = 6.0;
/// Low-shelf corner of the low knob, Hz.
pub const LOW_HZ: f64 = 250.0;
/// Centre of the mid knob, Hz.
pub const MID_HZ: f64 = 1_000.0;
/// Q of the mid band: about two octaves wide.
pub const MID_Q: f64 = 0.7;
/// High-shelf corner of the high knob, Hz.
pub const HIGH_HZ: f64 = 4_000.0;
/// Filter knob positions this close to centre leave the filter off.
pub const FILTER_DEAD_ZONE: f64 = 0.02;
/// Low-pass cutoff sweeps down from here with the filter knob just left of centre...
const LOW_PASS_OPEN: f64 = 20_000.0;
/// ...to here fully left.
const LOW_PASS_CLOSED: f64 = 150.0;
/// High-pass cutoff sweeps up from here just right of centre...
const HIGH_PASS_OPEN: f64 = 20.0;
/// ...to here fully right.
const HIGH_PASS_CLOSED: f64 = 6_000.0;
/// A knob change reaches the sound over this long, so turning one doesn't click.
const GLIDE_SECONDS: f64 = 0.03;
/// Frames between coefficient updates while a knob glides.
const BLOCK: usize = 32;

/// Knob positions, each -1...1 with 0 flat (filter: 0 off).
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct Knobs {
    pub low: f64,
    pub mid: f64,
    pub high: f64,
    pub filter: f64,
}

impl Knobs {
    fn clamped(self) -> Self {
        let c = |x: f64| if x.is_nan() { 0.0 } else { x.clamp(-1.0, 1.0) };
        Knobs { low: c(self.low), mid: c(self.mid), high: c(self.high), filter: c(self.filter) }
    }
}

#[repr(u32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    LowShelf = 0,
    Peak = 1,
    HighShelf = 2,
    LowPass = 3,
    HighPass = 4,
}

/// One filter of the chain.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Band {
    pub kind: Kind,
    pub freq: f64,
    /// Shelf and peak gain; 0 for the pass filters.
    pub gain_db: f64,
    pub q: f64,
    /// True when the band leaves the sound alone and can be skipped.
    pub bypass: bool,
}

/// Gain for an EQ knob position: linear in dB, to the kill on the left and
/// the boost on the right.
pub fn eq_gain_db(knob: f64) -> f64 {
    let k = if knob.is_nan() { 0.0 } else { knob.clamp(-1.0, 1.0) };
    if k < 0.0 { -k * KILL_DB } else { k * BOOST_DB }
}

/// The filter knob as a band: low-pass left of centre, high-pass right, the
/// cutoff sweeping exponentially (evenly in octaves).
pub fn filter_band(knob: f64) -> Band {
    let k = if knob.is_nan() { 0.0 } else { knob.clamp(-1.0, 1.0) };
    let sweep = |open: f64, closed: f64, t: f64| open * (closed / open).powf(t);
    if k < 0.0 {
        let t = (-k - FILTER_DEAD_ZONE).max(0.0) / (1.0 - FILTER_DEAD_ZONE);
        Band { kind: Kind::LowPass, freq: sweep(LOW_PASS_OPEN, LOW_PASS_CLOSED, t), gain_db: 0.0, q: FRAC_1_SQRT_2, bypass: -k < FILTER_DEAD_ZONE }
    } else {
        let t = (k - FILTER_DEAD_ZONE).max(0.0) / (1.0 - FILTER_DEAD_ZONE);
        Band { kind: Kind::HighPass, freq: sweep(HIGH_PASS_OPEN, HIGH_PASS_CLOSED, t), gain_db: 0.0, q: FRAC_1_SQRT_2, bypass: k < FILTER_DEAD_ZONE }
    }
}

/// The four filters for a set of knobs: low shelf, mid peak, high shelf, filter.
pub fn bands(knobs: Knobs) -> [Band; 4] {
    let k = knobs.clamped();
    let eq = |kind, freq, knob: f64, q| Band { kind, freq, gain_db: eq_gain_db(knob), q, bypass: knob == 0.0 };
    [
        eq(Kind::LowShelf, LOW_HZ, k.low, FRAC_1_SQRT_2),
        eq(Kind::Peak, MID_HZ, k.mid, MID_Q),
        eq(Kind::HighShelf, HIGH_HZ, k.high, FRAC_1_SQRT_2),
        filter_band(k.filter),
    ]
}

/// Normalised biquad coefficients (a0 = 1).
#[derive(Debug, Clone, Copy, PartialEq)]
struct Coeffs {
    b0: f64,
    b1: f64,
    b2: f64,
    a1: f64,
    a2: f64,
}

impl Coeffs {
    const IDENTITY: Coeffs = Coeffs { b0: 1.0, b1: 0.0, b2: 0.0, a1: 0.0, a2: 0.0 };

    fn new(band: &Band, sample_rate: f64) -> Self {
        if band.bypass {
            return Self::IDENTITY;
        }
        // Keep the corner below Nyquist, or the maths folds over.
        let w0 = 2.0 * PI * band.freq.clamp(10.0, sample_rate * 0.45) / sample_rate;
        let (sin, cos) = w0.sin_cos();
        let alpha = sin / (2.0 * band.q);
        let a = 10f64.powf(band.gain_db / 40.0);
        let shelf = 2.0 * a.sqrt() * alpha;
        let (b0, b1, b2, a0, a1, a2) = match band.kind {
            Kind::Peak => (1.0 + alpha * a, -2.0 * cos, 1.0 - alpha * a, 1.0 + alpha / a, -2.0 * cos, 1.0 - alpha / a),
            Kind::LowShelf => (
                a * ((a + 1.0) - (a - 1.0) * cos + shelf),
                2.0 * a * ((a - 1.0) - (a + 1.0) * cos),
                a * ((a + 1.0) - (a - 1.0) * cos - shelf),
                (a + 1.0) + (a - 1.0) * cos + shelf,
                -2.0 * ((a - 1.0) + (a + 1.0) * cos),
                (a + 1.0) + (a - 1.0) * cos - shelf,
            ),
            Kind::HighShelf => (
                a * ((a + 1.0) + (a - 1.0) * cos + shelf),
                -2.0 * a * ((a - 1.0) + (a + 1.0) * cos),
                a * ((a + 1.0) + (a - 1.0) * cos - shelf),
                (a + 1.0) - (a - 1.0) * cos + shelf,
                2.0 * ((a - 1.0) - (a + 1.0) * cos),
                (a + 1.0) - (a - 1.0) * cos - shelf,
            ),
            Kind::LowPass => ((1.0 - cos) / 2.0, 1.0 - cos, (1.0 - cos) / 2.0, 1.0 + alpha, -2.0 * cos, 1.0 - alpha),
            Kind::HighPass => ((1.0 + cos) / 2.0, -(1.0 + cos), (1.0 + cos) / 2.0, 1.0 + alpha, -2.0 * cos, 1.0 - alpha),
        };
        Coeffs { b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0 }
    }
}

/// Runs the EQ and filter over interleaved float samples, one deck's worth.
/// Not thread safe: set knobs and process from the audio thread, or pass the
/// knobs in with each block.
pub struct DeckFx {
    sample_rate: f64,
    channels: usize,
    target: Knobs,
    /// Where the knobs are now, gliding towards `target`.
    current: Knobs,
    coeffs: [Coeffs; 4],
    /// Transposed direct form II state, two values per band per channel.
    state: Vec<[f64; 2]>,
}

impl DeckFx {
    pub fn new(sample_rate: u32, channels: u32) -> Option<Self> {
        if sample_rate == 0 || channels == 0 {
            return None;
        }
        Some(DeckFx {
            sample_rate: sample_rate as f64,
            channels: channels as usize,
            target: Knobs::default(),
            current: Knobs::default(),
            coeffs: [Coeffs::IDENTITY; 4],
            state: vec![[0.0; 2]; 4 * channels as usize],
        })
    }

    pub fn set(&mut self, knobs: Knobs) {
        self.target = knobs.clamped();
    }

    pub fn knobs(&self) -> Knobs {
        self.target
    }

    /// Forgets the filters' memory, e.g. after a seek. Knobs stay.
    pub fn reset(&mut self) {
        self.state.iter_mut().for_each(|s| *s = [0.0; 2]);
        self.current = self.target;
        self.update_coeffs();
    }

    /// Filters `samples` (interleaved, a whole number of frames) in place.
    pub fn process(&mut self, samples: &mut [f32]) {
        let frame_len = self.channels;
        let step = 2.0 * BLOCK as f64 / (GLIDE_SECONDS * self.sample_rate);
        for block in samples.chunks_mut(BLOCK * frame_len) {
            if self.current != self.target {
                self.current = glide(self.current, self.target, step);
                self.update_coeffs();
            }
            if self.coeffs.iter().all(|c| *c == Coeffs::IDENTITY) {
                // Flat: nothing to do, and no state to keep since the filters were last on.
                continue;
            }
            for frame in block.chunks_exact_mut(frame_len) {
                for (ch, sample) in frame.iter_mut().enumerate() {
                    let mut x = *sample as f64;
                    for (b, c) in self.coeffs.iter().enumerate() {
                        if *c == Coeffs::IDENTITY {
                            continue;
                        }
                        let s = &mut self.state[b * frame_len + ch];
                        let y = c.b0 * x + s[0];
                        s[0] = c.b1 * x - c.a1 * y + s[1];
                        s[1] = c.b2 * x - c.a2 * y;
                        x = y;
                    }
                    *sample = x as f32;
                }
            }
        }
    }

    fn update_coeffs(&mut self) {
        let bands = bands(self.current);
        for (i, band) in bands.iter().enumerate() {
            let c = Coeffs::new(band, self.sample_rate);
            if c == Coeffs::IDENTITY && self.coeffs[i] != Coeffs::IDENTITY {
                // Switched off: start clean when it comes back.
                for ch in 0..self.channels {
                    self.state[i * self.channels + ch] = [0.0; 2];
                }
            }
            self.coeffs[i] = c;
        }
    }
}

/// `from` moved towards `to` by at most `step` per knob.
fn glide(from: Knobs, to: Knobs, step: f64) -> Knobs {
    let g = |a: f64, b: f64| if (b - a).abs() <= step { b } else { a + step * (b - a).signum() };
    Knobs { low: g(from.low, to.low), mid: g(from.mid, to.mid), high: g(from.high, to.high), filter: g(from.filter, to.filter) }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SR: u32 = 48_000;

    fn sine(freq: f64, seconds: f64, channels: usize) -> Vec<f32> {
        let n = (SR as f64 * seconds) as usize;
        (0..n * channels).map(|i| (0.5 * (2.0 * PI * freq * (i / channels) as f64 / SR as f64).sin()) as f32).collect()
    }

    /// Level change in dB of a sine through `knobs`, measured after the glide and filter settle.
    fn gain_db(knobs: Knobs, freq: f64) -> f64 {
        let mut fx = DeckFx::new(SR, 2).unwrap();
        fx.set(knobs);
        let input = sine(freq, 1.0, 2);
        let mut out = input.clone();
        fx.process(&mut out);
        let rms = |s: &[f32]| (s.iter().map(|&x| (x as f64).powi(2)).sum::<f64>() / s.len() as f64).sqrt();
        let tail = input.len() / 2;
        20.0 * (rms(&out[tail..]) / rms(&input[tail..])).log10()
    }

    #[test]
    fn flat_is_untouched() {
        let mut fx = DeckFx::new(SR, 2).unwrap();
        let input = sine(440.0, 0.1, 2);
        let mut out = input.clone();
        fx.process(&mut out);
        assert_eq!(out, input);
        assert!(bands(Knobs::default()).iter().all(|b| b.bypass));
    }

    #[test]
    fn knobs_map_to_gains() {
        assert_eq!(eq_gain_db(-1.0), KILL_DB);
        assert_eq!(eq_gain_db(1.0), BOOST_DB);
        assert_eq!(eq_gain_db(-0.5), KILL_DB / 2.0);
        assert_eq!(eq_gain_db(f64::NAN), 0.0);
        assert_eq!(eq_gain_db(-7.0), KILL_DB);
        let lp = filter_band(-1.0);
        assert_eq!((lp.kind, lp.bypass), (Kind::LowPass, false));
        assert!((lp.freq - LOW_PASS_CLOSED).abs() < 1e-9);
        let hp = filter_band(1.0);
        assert_eq!(hp.kind, Kind::HighPass);
        assert!((hp.freq - HIGH_PASS_CLOSED).abs() < 1e-9);
        assert!(filter_band(0.01).bypass && filter_band(-0.01).bypass);
        // Evenly in octaves: halfway is the geometric mean.
        let mid = filter_band(-(0.5 + FILTER_DEAD_ZONE / 2.0));
        assert!((mid.freq - (LOW_PASS_OPEN * LOW_PASS_CLOSED).sqrt()).abs() < 1.0);
    }

    #[test]
    fn kills_cut_their_band_only() {
        let low_kill = Knobs { low: -1.0, ..Knobs::default() };
        assert!(gain_db(low_kill, 50.0) < -20.0);
        assert!(gain_db(low_kill, 8_000.0).abs() < 0.5);
        let high_kill = Knobs { high: -1.0, ..Knobs::default() };
        assert!(gain_db(high_kill, 14_000.0) < -20.0);
        assert!(gain_db(high_kill, 60.0).abs() < 0.5);
        let mid_kill = Knobs { mid: -1.0, ..Knobs::default() };
        assert!((gain_db(mid_kill, 1_000.0) - KILL_DB).abs() < 0.5);
        let boost = Knobs { mid: 1.0, ..Knobs::default() };
        assert!((gain_db(boost, 1_000.0) - BOOST_DB).abs() < 0.3);
    }

    #[test]
    fn filter_sweeps_both_ways() {
        let closed_lp = Knobs { filter: -1.0, ..Knobs::default() };
        assert!(gain_db(closed_lp, 5_000.0) < -40.0);
        assert!(gain_db(closed_lp, 50.0).abs() < 1.0);
        let closed_hp = Knobs { filter: 1.0, ..Knobs::default() };
        assert!(gain_db(closed_hp, 200.0) < -30.0);
        assert!(gain_db(closed_hp, 16_000.0).abs() < 1.0);
    }

    #[test]
    fn knob_moves_glide_without_clicks() {
        // A slam from flat to a full low kill mid-tone changes the output
        // sample-to-sample no more than the tone itself does.
        let mut fx = DeckFx::new(SR, 1).unwrap();
        let mut out = sine(100.0, 0.5, 1);
        let max_step = |s: &[f32]| s.windows(2).map(|w| (w[1] - w[0]).abs()).fold(0.0f32, f32::max);
        let natural = max_step(&out);
        let (first, rest) = out.split_at_mut(4_000);
        fx.process(first);
        fx.set(Knobs { low: -1.0, filter: 1.0, ..Knobs::default() });
        fx.process(rest);
        assert!(max_step(&out) <= natural * 1.5, "{} vs {}", max_step(&out), natural);
        assert!(out.iter().all(|x| x.is_finite()));
    }

    #[test]
    fn reset_clears_memory_and_jumps_to_knobs() {
        let mut fx = DeckFx::new(SR, 1).unwrap();
        fx.set(Knobs { high: -1.0, ..Knobs::default() });
        fx.reset();
        let mut silence = vec![0.0f32; 64];
        fx.process(&mut silence);
        assert!(silence.iter().all(|&x| x == 0.0));
        assert_eq!(fx.current, fx.target);
        assert!(DeckFx::new(0, 2).is_none());
    }
}
