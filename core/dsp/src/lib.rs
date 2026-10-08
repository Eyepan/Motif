//! Motif's portable DSP core. Rust API in the modules, C ABI below; the C
//! declarations live in `include/motif_dsp.h` and must be kept in sync.

pub mod analysis;
#[cfg(feature = "archive")]
pub mod archive;
pub mod beatgrid;
pub mod crossfade;
pub mod dedupe;
pub mod key;
pub mod mix;
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
    /// Seconds to the first downbeat of the beat grid, or -1 without one.
    pub first_downbeat: f32,
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
        first_downbeat: r.first_downbeat.unwrap_or(-1.0),
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

/// A track's tempo and beat grid for mixing; see `mix::Timing`.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct MotifTiming {
    /// 0 when unknown.
    pub bpm: f64,
    /// Seconds to the first downbeat, negative without a grid.
    pub first_downbeat: f64,
    pub duration: f64,
}

impl From<MotifTiming> for mix::Timing {
    fn from(t: MotifTiming) -> Self {
        mix::Timing { bpm: t.bpm, first_downbeat: t.first_downbeat, duration: t.duration }
    }
}

/// See `mix::Plan`.
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct MotifMixPlan {
    pub out_start: f64,
    pub in_start: f64,
    pub length: f64,
    pub rate: f64,
    pub lock: f64,
}

pub const MOTIF_MIX_SPEED: u32 = 0;
pub const MOTIF_MIX_SEEK: u32 = 1;

/// See `mix::Follow`. `action` is `MOTIF_MIX_SPEED` (set the incoming speed to
/// `value`) or `MOTIF_MIX_SEEK` (move the incoming track to `value` seconds).
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct MotifMixFollow {
    pub progress: f64,
    pub gain_out: f32,
    pub gain_in: f32,
    pub action: u32,
    pub value: f64,
}

/// Plans a blend from the end of `outgoing` into `incoming`.
///
/// # Safety
/// Pointers must be valid (or null, which returns -1).
#[no_mangle]
pub unsafe extern "C" fn motif_mix_plan(
    outgoing: *const MotifTiming,
    incoming: *const MotifTiming,
    out: *mut MotifMixPlan,
) -> i32 {
    let (Some(o), Some(i), false) = (outgoing.as_ref(), incoming.as_ref(), out.is_null()) else { return -1 };
    let p = mix::plan(&(*o).into(), &(*i).into());
    *out = MotifMixPlan { out_start: p.out_start, in_start: p.in_start, length: p.length, rate: p.rate, lock: p.lock };
    0
}

/// Gains and incoming-track correction with the outgoing track at `out_pos`
/// and the incoming one at `in_pos` seconds.
///
/// # Safety
/// Pointers must be valid (or null, which returns -1).
#[no_mangle]
pub unsafe extern "C" fn motif_mix_follow(
    plan: *const MotifMixPlan,
    out_pos: f64,
    in_pos: f64,
    out: *mut MotifMixFollow,
) -> i32 {
    let (Some(p), false) = (plan.as_ref(), out.is_null()) else { return -1 };
    let plan = mix::Plan { out_start: p.out_start, in_start: p.in_start, length: p.length, rate: p.rate, lock: p.lock };
    let f = mix::follow(&plan, out_pos, in_pos);
    let (action, value) = match f.action {
        mix::Action::Speed(s) => (MOTIF_MIX_SPEED, s),
        mix::Action::Seek(x) => (MOTIF_MIX_SEEK, x),
    };
    *out = MotifMixFollow { progress: f.progress, gain_out: f.gain_out, gain_in: f.gain_in, action, value };
    0
}

/// Speed and position putting the slave deck on the master's tempo and beat;
/// see `mix::sync`. Returns 0, or -1 when the decks lack grids or their
/// tempos are too far apart (outputs untouched).
///
/// # Safety
/// Pointers must be valid (or null, which returns -1).
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn motif_deck_sync(
    master: *const MotifTiming,
    master_pos: f64,
    master_speed: f64,
    slave: *const MotifTiming,
    slave_pos: f64,
    snap: i32,
    out_speed: *mut f64,
    out_pos: *mut f64,
) -> i32 {
    let (Some(m), Some(s), false, false) = (master.as_ref(), slave.as_ref(), out_speed.is_null(), out_pos.is_null()) else {
        return -1;
    };
    match deck_sync(m, master_pos, master_speed, s, slave_pos, snap != 0) {
        Some((speed, pos)) => {
            *out_speed = speed;
            *out_pos = pos;
            0
        }
        None => -1,
    }
}

/// `mix::sync` over raw timings; shared with the JNI bindings.
pub fn deck_sync(
    master: &MotifTiming,
    master_pos: f64,
    master_speed: f64,
    slave: &MotifTiming,
    slave_pos: f64,
    snap: bool,
) -> Option<(f64, f64)> {
    let grid = |t: &MotifTiming| mix::Timing::from(*t).grid();
    if master_speed.is_nan() || master_speed <= 0.0 {
        return None;
    }
    let m = mix::Deck { grid: grid(master)?, position: master_pos, speed: master_speed };
    let s = mix::Deck { grid: grid(slave)?, position: slave_pos, speed: 1.0 };
    mix::sync(&m, &s, snap)
}

// MARK: - Import dedupe (dedupe)

/// One file or library track for `dedupe`. Strings are NUL-terminated UTF-8;
/// `artist` may be null. Unknown numbers are 0.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct MotifDedupeTrack {
    pub title: *const c_char,
    pub artist: *const c_char,
    pub duration_ms: i64,
    pub format: *const c_char,
    pub sample_rate: u32,
    pub bit_depth: u32,
    pub bitrate_kbps: u32,
}

pub const MOTIF_DEDUPE_NEW: i32 = 0;
pub const MOTIF_DEDUPE_DUPLICATE: i32 = 1;
pub const MOTIF_DEDUPE_UPGRADE: i32 = 2;

/// Owned strings behind a `MotifDedupeTrack`, borrowed as a `dedupe::Candidate`.
struct OwnedCandidate {
    title: String,
    artist: Option<String>,
    format: String,
    duration_ms: i64,
    sample_rate: u32,
    bit_depth: u32,
    bitrate_kbps: u32,
}

impl OwnedCandidate {
    /// # Safety
    /// The track's strings must be null or NUL-terminated.
    unsafe fn read(t: &MotifDedupeTrack) -> Self {
        OwnedCandidate {
            title: read_c(t.title).unwrap_or_default(),
            artist: read_c(t.artist),
            format: read_c(t.format).unwrap_or_default(),
            duration_ms: t.duration_ms,
            sample_rate: t.sample_rate,
            bit_depth: t.bit_depth,
            bitrate_kbps: t.bitrate_kbps,
        }
    }

    fn candidate(&self) -> dedupe::Candidate<'_> {
        dedupe::Candidate {
            title: &self.title,
            artist: self.artist.as_deref(),
            duration_ms: self.duration_ms,
            format: &self.format,
            sample_rate: self.sample_rate,
            bit_depth: self.bit_depth,
            bitrate_kbps: self.bitrate_kbps,
        }
    }
}

/// `dedupe::match_key`: equal keys are the same song. Null for a null title.
///
/// # Safety
/// `title` and `artist` must be null or NUL-terminated.
#[no_mangle]
pub unsafe extern "C" fn motif_dedupe_key(title: *const c_char, artist: *const c_char) -> *mut c_char {
    let Some(title) = read_c(title) else { return std::ptr::null_mut() };
    to_c(&dedupe::match_key(&title, read_c(artist).as_deref()))
}

/// 1 when `a` is the better copy, -1 when `b` is, 0 when equal or on a null argument.
///
/// # Safety
/// Null, or valid tracks as described on `MotifDedupeTrack`.
#[no_mangle]
pub unsafe extern "C" fn motif_dedupe_compare_quality(a: *const MotifDedupeTrack, b: *const MotifDedupeTrack) -> i32 {
    let (Some(a), Some(b)) = (a.as_ref(), b.as_ref()) else { return 0 };
    let (a, b) = (OwnedCandidate::read(a), OwnedCandidate::read(b));
    dedupe::compare_quality(&a.candidate(), &b.candidate()) as i32
}

/// What to do with `incoming` given `count` existing tracks: `MOTIF_DEDUPE_NEW`,
/// or `MOTIF_DEDUPE_DUPLICATE` / `MOTIF_DEDUPE_UPGRADE` with the matching
/// track's index in `out_index`. -1 on a null argument.
///
/// # Safety
/// `incoming` and `out_index` valid; `existing` null (with `count` 0) or `count` valid tracks.
#[no_mangle]
pub unsafe extern "C" fn motif_dedupe_resolve(
    incoming: *const MotifDedupeTrack,
    existing: *const MotifDedupeTrack,
    count: usize,
    out_index: *mut usize,
) -> i32 {
    let (Some(incoming), false) = (incoming.as_ref(), out_index.is_null()) else { return -1 };
    if existing.is_null() && count > 0 {
        return -1;
    }
    let incoming = OwnedCandidate::read(incoming);
    let owned: Vec<OwnedCandidate> = if count == 0 {
        Vec::new()
    } else {
        std::slice::from_raw_parts(existing, count).iter().map(|t| OwnedCandidate::read(t)).collect()
    };
    let (code, index) = resolve_owned(&incoming, &owned);
    *out_index = index;
    code
}

fn resolve_owned(incoming: &OwnedCandidate, existing: &[OwnedCandidate]) -> (i32, usize) {
    let candidates: Vec<dedupe::Candidate> = existing.iter().map(OwnedCandidate::candidate).collect();
    match dedupe::resolve(&incoming.candidate(), &candidates) {
        dedupe::Verdict::New => (MOTIF_DEDUPE_NEW, 0),
        dedupe::Verdict::Duplicate(i) => (MOTIF_DEDUPE_DUPLICATE, i),
        dedupe::Verdict::Upgrade(i) => (MOTIF_DEDUPE_UPGRADE, i),
    }
}

// MARK: - Zip archives (archive)

/// Opaque handle for C callers.
#[cfg(feature = "archive")]
pub struct MotifZip(archive::Archive);

/// Opens a .zip at `path`, or null if it can't be read as one.
///
/// # Safety
/// `path` must be null or NUL-terminated.
#[cfg(feature = "archive")]
#[no_mangle]
pub unsafe extern "C" fn motif_zip_open(path: *const c_char) -> *mut MotifZip {
    let Some(path) = read_c(path) else { return std::ptr::null_mut() };
    match archive::Archive::open(std::path::Path::new(&path)) {
        Ok(a) => Box::into_raw(Box::new(MotifZip(a))),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Number of files listed (directories, hidden files and unsafe paths left out).
///
/// # Safety
/// `zip` must be null or come from `motif_zip_open`.
#[cfg(feature = "archive")]
#[no_mangle]
pub unsafe extern "C" fn motif_zip_count(zip: *const MotifZip) -> usize {
    zip.as_ref().map_or(0, |z| z.0.entries().len())
}

/// Entry `index`'s relative '/'-separated path, safe to join onto a folder.
/// Null when out of range. Free with `motif_string_free`.
///
/// # Safety
/// See `motif_zip_count`.
#[cfg(feature = "archive")]
#[no_mangle]
pub unsafe extern "C" fn motif_zip_name(zip: *const MotifZip, index: usize) -> *mut c_char {
    zip.as_ref().and_then(|z| z.0.entries().get(index)).map_or(std::ptr::null_mut(), |e| to_c(&e.name))
}

/// Entry `index`'s uncompressed size as recorded, 0 when out of range.
///
/// # Safety
/// See `motif_zip_count`.
#[cfg(feature = "archive")]
#[no_mangle]
pub unsafe extern "C" fn motif_zip_size(zip: *const MotifZip, index: usize) -> u64 {
    zip.as_ref().and_then(|z| z.0.entries().get(index)).map_or(0, |e| e.size)
}

/// Writes entry `index` to `dest`. Returns 0, or -1 when it fails, exceeds
/// `max_bytes` (nothing is left at `dest` then) or an argument is null.
///
/// # Safety
/// `zip` must be null or come from `motif_zip_open`; `dest` null or NUL-terminated.
#[cfg(feature = "archive")]
#[no_mangle]
pub unsafe extern "C" fn motif_zip_extract(zip: *mut MotifZip, index: usize, dest: *const c_char, max_bytes: u64) -> i32 {
    let (Some(z), Some(dest)) = (zip.as_mut(), read_c(dest)) else { return -1 };
    match z.0.extract(index, std::path::Path::new(&dest), max_bytes) {
        Ok(_) => 0,
        Err(_) => -1,
    }
}

/// # Safety
/// `zip` must come from `motif_zip_open` and not be used afterwards.
#[cfg(feature = "archive")]
#[no_mangle]
pub unsafe extern "C" fn motif_zip_free(zip: *mut MotifZip) {
    if !zip.is_null() {
        drop(Box::from_raw(zip));
    }
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
            let mut out = MotifAnalysis { peak_db: 0.0, rms_db: 0.0, bpm: 0.0, camelot_number: 9, camelot_minor: 9, first_downbeat: 0.0 };
            assert_eq!(motif_analyzer_finish(h, &mut out), 0);
            assert!((out.peak_db - 20.0 * 0.5f32.log10()).abs() < 1e-4);
            assert_eq!((out.camelot_number, out.camelot_minor), (0, 0)); // DC has no key
            assert_eq!(out.first_downbeat, -1.0); // nor a beat
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

    #[test]
    fn mix_c_abi() {
        unsafe {
            let out = MotifTiming { bpm: 128.0, first_downbeat: 0.4, duration: 300.0 };
            let inc = MotifTiming { bpm: 125.0, first_downbeat: 1.1, duration: 280.0 };
            let mut plan = MotifMixPlan::default();
            assert_eq!(motif_mix_plan(&out, &inc, &mut plan), 0);
            assert!(plan.lock > 0.0 && (plan.rate - 128.0 / 125.0).abs() < 1e-12);
            let mut f = MotifMixFollow::default();
            assert_eq!(motif_mix_follow(&plan, plan.out_start + 0.5, plan.in_start, &mut f), 0);
            assert_eq!(f.action, MOTIF_MIX_SEEK);
            assert!((f.value - (plan.in_start + 0.5 * plan.rate)).abs() < 1e-9);
            assert_eq!(motif_mix_plan(&out, std::ptr::null(), &mut plan), -1);

            let (mut speed, mut pos) = (0.0, 0.0);
            assert_eq!(motif_deck_sync(&out, 10.0, 1.0, &inc, 20.0, 1, &mut speed, &mut pos), 0);
            assert!((speed - 128.0 / 125.0).abs() < 1e-12);
            let no_grid = MotifTiming { first_downbeat: -1.0, ..inc };
            assert_eq!(motif_deck_sync(&out, 10.0, 1.0, &no_grid, 20.0, 1, &mut speed, &mut pos), -1);
        }
    }

    #[test]
    fn dedupe_c_abi() {
        let title = CString::new("Veramaari (320kbps)").unwrap();
        let artist = CString::new("Anirudh Ravichander").unwrap();
        let mp3 = CString::new("mp3").unwrap();
        let flac = CString::new("flac").unwrap();
        let lossy = MotifDedupeTrack {
            title: title.as_ptr(),
            artist: artist.as_ptr(),
            duration_ms: 214_000,
            format: mp3.as_ptr(),
            sample_rate: 44_100,
            bit_depth: 0,
            bitrate_kbps: 320,
        };
        let lossless = MotifDedupeTrack { format: flac.as_ptr(), bit_depth: 16, bitrate_kbps: 0, duration_ms: 214_300, ..lossy };
        unsafe {
            let key = motif_dedupe_key(title.as_ptr(), artist.as_ptr());
            assert_eq!(CStr::from_ptr(key).to_str().unwrap(), "anirudh ravichander\u{1f}veramaari");
            motif_string_free(key);
            assert!(motif_dedupe_key(std::ptr::null(), artist.as_ptr()).is_null());

            assert_eq!(motif_dedupe_compare_quality(&lossless, &lossy), 1);
            assert_eq!(motif_dedupe_compare_quality(&lossy, &lossless), -1);

            let mut index = 99;
            assert_eq!(motif_dedupe_resolve(&lossy, std::ptr::null(), 0, &mut index), MOTIF_DEDUPE_NEW);
            let library = [lossy];
            assert_eq!(motif_dedupe_resolve(&lossless, library.as_ptr(), 1, &mut index), MOTIF_DEDUPE_UPGRADE);
            assert_eq!(index, 0);
            let library = [lossless];
            assert_eq!(motif_dedupe_resolve(&lossy, library.as_ptr(), 1, &mut index), MOTIF_DEDUPE_DUPLICATE);
            assert_eq!(motif_dedupe_resolve(&lossy, std::ptr::null(), 1, &mut index), -1);
        }
    }

    #[test]
    #[cfg(feature = "archive")]
    fn zip_c_abi() {
        unsafe {
            assert!(motif_zip_open(std::ptr::null()).is_null());
            let missing = CString::new("/nonexistent/motif.zip").unwrap();
            assert!(motif_zip_open(missing.as_ptr()).is_null());
            assert_eq!(motif_zip_count(std::ptr::null()), 0);
            assert!(motif_zip_name(std::ptr::null(), 0).is_null());
            assert_eq!(motif_zip_extract(std::ptr::null_mut(), 0, missing.as_ptr(), 1), -1);
            motif_zip_free(std::ptr::null_mut());
        }
    }
}
