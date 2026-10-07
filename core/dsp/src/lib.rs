//! Motif's portable DSP core. Rust API in the modules, C ABI below; the C
//! declarations live in `include/motif_dsp.h` and must be kept in sync.

pub mod analysis;
pub mod crossfade;
pub mod key;

use analysis::Analyzer;

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct MotifAnalysis {
    pub peak_db: f32,
    pub rms_db: f32,
    pub bpm: f32,
    /// Camelot wheel number 1...12, or 0 when no key was found.
    pub camelot_number: u8,
    /// 1 for minor (Camelot "A"), 0 for major ("B").
    pub camelot_minor: u8,
}

/// Opaque handle for C callers.
pub struct MotifAnalyzer(Analyzer);

#[no_mangle]
pub extern "C" fn motif_analyzer_new(sample_rate: u32, channels: u32) -> *mut MotifAnalyzer {
    if sample_rate == 0 || channels == 0 {
        return std::ptr::null_mut();
    }
    Box::into_raw(Box::new(MotifAnalyzer(Analyzer::new(sample_rate, channels))))
}

/// # Safety
/// `analyzer` must come from `motif_analyzer_new`; `samples` must point to
/// `sample_count` readable interleaved f32 samples (or be null when 0).
#[no_mangle]
pub unsafe extern "C" fn motif_analyzer_push(
    analyzer: *mut MotifAnalyzer,
    samples: *const f32,
    sample_count: usize,
) {
    let Some(a) = analyzer.as_mut() else { return };
    if samples.is_null() || sample_count == 0 {
        return;
    }
    a.0.push(std::slice::from_raw_parts(samples, sample_count));
}

/// # Safety
/// `analyzer` must come from `motif_analyzer_new`; `out` must be writable.
/// Returns 0 on success, -1 on a null argument.
#[no_mangle]
pub unsafe extern "C" fn motif_analyzer_finish(
    analyzer: *const MotifAnalyzer,
    out: *mut MotifAnalysis,
) -> i32 {
    let (Some(a), false) = (analyzer.as_ref(), out.is_null()) else { return -1 };
    let r = a.0.finish();
    *out = MotifAnalysis {
        peak_db: r.peak_db,
        rms_db: r.rms_db,
        bpm: r.bpm,
        camelot_number: r.key.map_or(0, |k| k.camelot_number()),
        camelot_minor: r.key.is_some_and(|k| k.minor) as u8,
    };
    0
}

/// Fills `out` with `count` waveform overview values in 0...1.
///
/// # Safety
/// `analyzer` must come from `motif_analyzer_new`; `out` must have room for `count` floats.
#[no_mangle]
pub unsafe extern "C" fn motif_analyzer_overview(
    analyzer: *const MotifAnalyzer,
    out: *mut f32,
    count: usize,
) -> i32 {
    let (Some(a), false) = (analyzer.as_ref(), out.is_null()) else { return -1 };
    let values = a.0.overview(count);
    std::ptr::copy_nonoverlapping(values.as_ptr(), out, count);
    0
}

/// # Safety
/// `analyzer` must come from `motif_analyzer_new` and not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn motif_analyzer_free(analyzer: *mut MotifAnalyzer) {
    if !analyzer.is_null() {
        drop(Box::from_raw(analyzer));
    }
}

/// Returns 0 on success, -1 for an unknown curve or null output.
///
/// # Safety
/// `out_a` and `out_b` must be writable.
#[no_mangle]
pub unsafe extern "C" fn motif_crossfade_gains(
    t: f32,
    curve: u32,
    out_a: *mut f32,
    out_b: *mut f32,
) -> i32 {
    let Some(curve) = crossfade::Curve::from_raw(curve) else { return -1 };
    if out_a.is_null() || out_b.is_null() {
        return -1;
    }
    let (a, b) = crossfade::gains(t, curve);
    *out_a = a;
    *out_b = b;
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn c_abi_round_trip() {
        unsafe {
            let h = motif_analyzer_new(44_100, 1);
            assert!(!h.is_null());
            let buf = [0.5f32; 1024];
            motif_analyzer_push(h, buf.as_ptr(), buf.len());
            let mut out = MotifAnalysis { peak_db: 0.0, rms_db: 0.0, bpm: 0.0, camelot_number: 9, camelot_minor: 9 };
            assert_eq!(motif_analyzer_finish(h, &mut out), 0);
            assert!((out.peak_db - 20.0 * 0.5f32.log10()).abs() < 1e-4);
            assert_eq!((out.camelot_number, out.camelot_minor), (0, 0)); // DC has no key
            let mut wave = [9.0f32; 4];
            assert_eq!(motif_analyzer_overview(h, wave.as_mut_ptr(), 4), 0);
            assert!(wave.iter().all(|v| (0.0..=1.0).contains(v)));
            motif_analyzer_free(h);

            assert!(motif_analyzer_new(0, 2).is_null());
            let (mut a, mut b) = (0.0, 0.0);
            assert_eq!(motif_crossfade_gains(0.5, 99, &mut a, &mut b), -1);
        }
    }
}
