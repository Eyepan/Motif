//! Motif's portable DSP core. Rust API in the modules, C ABI below; the C
//! declarations live in `include/motif_dsp.h` and must be kept in sync.

pub mod analysis;
pub mod crossfade;
pub mod key;
pub mod meta;

use analysis::Analyzer;
use std::ffi::{c_char, CStr, CString};

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

// MARK: - Tag cleanup and artist credits (meta)
//
// Strings in and out are NUL-terminated UTF-8. Returned strings are owned by
// the caller and freed with `motif_string_free`. Invalid UTF-8 is replaced
// rather than rejected, since tags come from arbitrary files.

/// # Safety
/// `text` must be null or a NUL-terminated string.
unsafe fn read_c(text: *const c_char) -> Option<String> {
    (!text.is_null()).then(|| CStr::from_ptr(text).to_string_lossy().into_owned())
}

/// # Safety
/// `list` must be null or point to `count` pointers, each null or a NUL-terminated string.
unsafe fn read_c_list(list: *const *const c_char, count: usize) -> Vec<Option<String>> {
    if list.is_null() {
        return Vec::new();
    }
    std::slice::from_raw_parts(list, count).iter().map(|&p| read_c(p)).collect()
}

fn to_c(text: &str) -> *mut c_char {
    // Tag text can't hold NUL after tidy(), but never panic across the ABI.
    CString::new(text.replace('\0', "")).map_or(std::ptr::null_mut(), CString::into_raw)
}

#[no_mangle]
pub extern "C" fn motif_meta_cleaner_version() -> u32 {
    meta::CLEANER_VERSION
}

/// # Safety
/// `text` must come from a `motif_meta_*` function and not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn motif_string_free(text: *mut c_char) {
    if !text.is_null() {
        drop(CString::from_raw(text));
    }
}

/// Case- and whitespace-insensitive comparison key. Null for a null input.
///
/// # Safety
/// `text` must be null or a NUL-terminated string.
#[no_mangle]
pub unsafe extern "C" fn motif_meta_norm(text: *const c_char) -> *mut c_char {
    read_c(text).map_or(std::ptr::null_mut(), |t| to_c(&meta::norm(&t)))
}

/// A site name appended to several of a track's tags, or null. `fields` are
/// title, artist, album and album artist; null entries are missing tags.
///
/// # Safety
/// See `read_c_list`.
#[no_mangle]
pub unsafe extern "C" fn motif_meta_detect_site_suffix(fields: *const *const c_char, count: usize) -> *mut c_char {
    let fields = read_c_list(fields, count);
    let refs: Vec<Option<&str>> = fields.iter().map(Option::as_deref).collect();
    meta::detect_site_suffix(&refs).map_or(std::ptr::null_mut(), |s| to_c(&s))
}

/// The cleaned tag value. Null for a null input.
///
/// # Safety
/// `text` as in `motif_meta_norm`, `suffixes` as in `read_c_list`.
#[no_mangle]
pub unsafe extern "C" fn motif_meta_clean_field(
    text: *const c_char,
    suffixes: *const *const c_char,
    count: usize,
) -> *mut c_char {
    let Some(text) = read_c(text) else { return std::ptr::null_mut() };
    let suffixes: Vec<String> = read_c_list(suffixes, count).into_iter().flatten().collect();
    to_c(&meta::clean_field(&text, &suffixes))
}

/// Credited artists, one per line as "role\tname" with role "primary" or
/// "featured". `known` holds names already passed through `motif_meta_norm`.
///
/// # Safety
/// `credit` as in `motif_meta_norm`, `known` as in `read_c_list`.
#[no_mangle]
pub unsafe extern "C" fn motif_meta_split_artists(
    credit: *const c_char,
    known: *const *const c_char,
    count: usize,
) -> *mut c_char {
    let Some(credit) = read_c(credit) else { return std::ptr::null_mut() };
    let known: Vec<String> = read_c_list(known, count).into_iter().flatten().collect();
    let lines: Vec<String> = meta::split_artists(&credit, &known)
        .iter()
        .map(|c| format!("{}\t{}", c.role.as_str(), c.name))
        .collect();
    to_c(&lines.join("\n"))
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

    #[test]
    fn meta_c_abi() {
        unsafe fn take(p: *mut c_char) -> Option<String> {
            let s = (!p.is_null()).then(|| CStr::from_ptr(p).to_str().unwrap().to_string());
            motif_string_free(p);
            s
        }
        let title = CString::new("Jailer 2 - MassTamilan").unwrap();
        let artist = CString::new("Anirudh Ravichander - MassTamilan").unwrap();
        let credit = CString::new("A, B feat. C").unwrap();
        unsafe {
            let fields = [title.as_ptr(), artist.as_ptr(), std::ptr::null()];
            let suffix = take(motif_meta_detect_site_suffix(fields.as_ptr(), fields.len())).unwrap();
            assert_eq!(suffix, "MassTamilan");
            let suffix = CString::new(suffix).unwrap();
            let suffixes = [suffix.as_ptr()];
            assert_eq!(take(motif_meta_clean_field(title.as_ptr(), suffixes.as_ptr(), 1)).as_deref(), Some("Jailer 2"));
            assert_eq!(take(motif_meta_detect_site_suffix(std::ptr::null(), 0)), None);
            assert_eq!(
                take(motif_meta_split_artists(credit.as_ptr(), std::ptr::null(), 0)).as_deref(),
                Some("primary\tA\nprimary\tB\nfeatured\tC")
            );
            assert_eq!(take(motif_meta_norm(CString::new("  Ab  C ").unwrap().as_ptr())).as_deref(), Some("ab c"));
            assert!(motif_meta_norm(std::ptr::null()).is_null());
            assert_eq!(motif_meta_cleaner_version(), meta::CLEANER_VERSION);
        }
    }
}
