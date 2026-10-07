//! Musical key estimation: chroma from a decimated mono stream, matched
//! against Krumhansl-Kessler key profiles. Reported in Camelot notation,
//! the DJ convention (8A = A minor, 8B = C major; neighbours mix cleanly).

const FFT_SIZE: usize = 4096;
const TARGET_RATE: f32 = 11_025.0;
const MIN_HZ: f32 = 65.0; // C2
const MAX_HZ: f32 = 2_100.0; // ~C7

const MAJOR: [f32; 12] = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
const MINOR: [f32; 12] = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Key {
    /// Pitch class of the tonic, 0 = C.
    pub tonic: u8,
    pub minor: bool,
}

impl Key {
    /// Camelot wheel number, 1...12.
    pub fn camelot_number(self) -> u8 {
        // Majors step by fifths from C = 8B; a minor shares its relative major's number.
        let major_pc = if self.minor { (self.tonic + 3) % 12 } else { self.tonic } as u32;
        (((7 * major_pc) % 12 + 7) % 12 + 1) as u8
    }

    pub fn camelot(self) -> String {
        format!("{}{}", self.camelot_number(), if self.minor { 'A' } else { 'B' })
    }
}

pub struct KeyDetector {
    decimation: usize,
    rate: f32,
    acc: f32,
    acc_n: usize,
    frame: Vec<f32>,
    chroma: [f64; 12],
    window: Vec<f32>,
    /// Pitch class for each FFT bin, or 255 when the bin is out of range.
    bin_class: Vec<u8>,
}

impl KeyDetector {
    pub fn new(sample_rate: u32) -> Self {
        let decimation = ((sample_rate as f32 / TARGET_RATE).round() as usize).max(1);
        let rate = sample_rate as f32 / decimation as f32;
        let window = (0..FFT_SIZE)
            .map(|i| 0.5 - 0.5 * (2.0 * std::f32::consts::PI * i as f32 / FFT_SIZE as f32).cos())
            .collect();
        let bin_class = (0..FFT_SIZE / 2)
            .map(|b| {
                let hz = b as f32 * rate / FFT_SIZE as f32;
                if !(MIN_HZ..=MAX_HZ).contains(&hz) {
                    return 255;
                }
                let midi = 69.0 + 12.0 * (hz / 440.0).log2();
                (midi.round() as i32).rem_euclid(12) as u8
            })
            .collect();
        Self {
            decimation,
            rate,
            acc: 0.0,
            acc_n: 0,
            frame: Vec::with_capacity(FFT_SIZE),
            chroma: [0.0; 12],
            window,
            bin_class,
        }
    }

    pub fn sample_rate(&self) -> f32 {
        self.rate
    }

    /// Push one mono sample at the source rate.
    #[inline]
    pub fn push(&mut self, mono: f32) {
        // Box-filter decimation: crude, but chroma only needs < 2.1 kHz.
        self.acc += mono;
        self.acc_n += 1;
        if self.acc_n < self.decimation {
            return;
        }
        self.frame.push(self.acc / self.acc_n as f32);
        self.acc = 0.0;
        self.acc_n = 0;
        if self.frame.len() == FFT_SIZE {
            self.analyze_frame();
            self.frame.clear();
        }
    }

    fn analyze_frame(&mut self) {
        let mut re: Vec<f32> = self.frame.iter().zip(&self.window).map(|(s, w)| s * w).collect();
        let mut im = vec![0.0f32; FFT_SIZE];
        fft(&mut re, &mut im);
        for (b, &pc) in self.bin_class.iter().enumerate() {
            if pc != 255 {
                self.chroma[pc as usize] += ((re[b] * re[b] + im[b] * im[b]) as f64).sqrt();
            }
        }
    }

    pub fn finish(&self) -> Option<Key> {
        let total: f64 = self.chroma.iter().sum();
        if total < 1e-3 {
            return None;
        }
        let chroma: Vec<f32> = self.chroma.iter().map(|&c| (c / total) as f32).collect();
        let mut best: Option<(f32, Key)> = None;
        for tonic in 0..12u8 {
            for (profile, minor) in [(&MAJOR, false), (&MINOR, true)] {
                let rotated: Vec<f32> = (0..12).map(|i| profile[(i + 12 - tonic as usize) % 12]).collect();
                let r = pearson(&chroma, &rotated);
                if best.is_none_or(|(b, _)| r > b) {
                    best = Some((r, Key { tonic, minor }));
                }
            }
        }
        best.filter(|(r, _)| *r > 0.2).map(|(_, k)| k)
    }
}

fn pearson(a: &[f32], b: &[f32]) -> f32 {
    let n = a.len() as f32;
    let (ma, mb) = (a.iter().sum::<f32>() / n, b.iter().sum::<f32>() / n);
    let (mut num, mut da, mut db) = (0.0, 0.0, 0.0);
    for (x, y) in a.iter().zip(b) {
        num += (x - ma) * (y - mb);
        da += (x - ma) * (x - ma);
        db += (y - mb) * (y - mb);
    }
    if da == 0.0 || db == 0.0 { 0.0 } else { num / (da * db).sqrt() }
}

/// In-place iterative radix-2 FFT. `re.len()` must be a power of two.
fn fft(re: &mut [f32], im: &mut [f32]) {
    let n = re.len();
    let mut j = 0;
    for i in 1..n {
        let mut bit = n >> 1;
        while j & bit != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j |= bit;
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
    }
    let mut len = 2;
    while len <= n {
        let angle = -2.0 * std::f32::consts::PI / len as f32;
        let (w_re, w_im) = (angle.cos(), angle.sin());
        for start in (0..n).step_by(len) {
            let (mut cr, mut ci) = (1.0f32, 0.0f32);
            for k in 0..len / 2 {
                let (a, b) = (start + k, start + k + len / 2);
                let tr = re[b] * cr - im[b] * ci;
                let ti = re[b] * ci + im[b] * cr;
                re[b] = re[a] - tr;
                im[b] = im[a] - ti;
                re[a] += tr;
                im[a] += ti;
                let next = cr * w_re - ci * w_im;
                ci = cr * w_im + ci * w_re;
                cr = next;
            }
        }
        len <<= 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hz(midi: i32) -> f32 {
        440.0 * 2f32.powf((midi - 69) as f32 / 12.0)
    }

    /// Two-second chords, each note with a few decaying harmonics.
    fn progression(chords: &[[i32; 3]], sample_rate: u32) -> Vec<f32> {
        let per = sample_rate as usize * 2;
        let mut out = Vec::with_capacity(per * chords.len());
        for chord in chords {
            for i in 0..per {
                let t = i as f32 / sample_rate as f32;
                let mut s = 0.0;
                for &m in chord {
                    for h in 1..=3 {
                        s += (2.0 * std::f32::consts::PI * hz(m) * h as f32 * t).sin() / h as f32;
                    }
                }
                out.push(0.1 * s);
            }
        }
        out
    }

    fn detect(audio: &[f32], sample_rate: u32) -> Option<Key> {
        let mut d = KeyDetector::new(sample_rate);
        audio.iter().for_each(|&s| d.push(s));
        d.finish()
    }

    #[test]
    fn camelot_mapping() {
        assert_eq!(Key { tonic: 0, minor: false }.camelot(), "8B");
        assert_eq!(Key { tonic: 9, minor: true }.camelot(), "8A");
        assert_eq!(Key { tonic: 7, minor: false }.camelot(), "9B");
        assert_eq!(Key { tonic: 5, minor: false }.camelot(), "7B");
        assert_eq!(Key { tonic: 0, minor: true }.camelot(), "5A");
        assert_eq!(Key { tonic: 6, minor: false }.camelot(), "2B");
    }

    #[test]
    fn detects_c_major_progression() {
        // I IV V I in C: C-E-G, F-A-C, G-B-D, C-E-G
        let audio = progression(&[[60, 64, 67], [53, 57, 60], [55, 59, 62], [48, 52, 55]], 44_100);
        assert_eq!(detect(&audio, 44_100), Some(Key { tonic: 0, minor: false }));
    }

    #[test]
    fn detects_a_minor_progression() {
        // i iv V i in A minor: A-C-E, D-F-A, E-G#-B, A-C-E
        let audio = progression(&[[57, 60, 64], [50, 53, 57], [52, 56, 59], [45, 48, 52]], 48_000);
        assert_eq!(detect(&audio, 48_000), Some(Key { tonic: 9, minor: true }));
    }

    #[test]
    fn silence_has_no_key() {
        assert_eq!(detect(&vec![0.0; 44_100 * 3], 44_100), None);
    }

    #[test]
    fn fft_finds_a_pure_tone() {
        let mut re: Vec<f32> = (0..1024).map(|i| (2.0 * std::f32::consts::PI * 64.0 * i as f32 / 1024.0).sin()).collect();
        let mut im = vec![0.0; 1024];
        fft(&mut re, &mut im);
        let peak = (0..512).max_by(|&a, &b| (re[a].hypot(im[a])).total_cmp(&re[b].hypot(im[b]))).unwrap();
        assert_eq!(peak, 64);
    }
}
