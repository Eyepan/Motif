//! JNI entry points for `app.motif.dsp.MotifDsp`. Keep the signatures in sync
//! with the `external fun` declarations there. Analyzer handles are boxed
//! `Analyzer`s passed to Kotlin as `Long`.

use jni::objects::{JByteBuffer, JClass, JDoubleArray, JFloatArray, JLongArray, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jdouble, jdoubleArray, jfloat, jfloatArray, jint, jintArray, jlong, jobjectArray, jstring, JNI_TRUE};
use jni::JNIEnv;
use motif_dsp::analysis::Analyzer;
use motif_dsp::crossfade::{self, Curve};
use motif_dsp::dedupe::{self, Candidate, Verdict};
use motif_dsp::fx::{DeckFx, Knobs};
use motif_dsp::meta;
use motif_dsp::mix::{self, Action, Plan, Timing};
use motif_dsp::{deck_sync, MotifTiming};

fn float_array(env: &mut JNIEnv, values: &[f32]) -> jfloatArray {
    let Ok(array) = env.new_float_array(values.len() as i32) else { return std::ptr::null_mut() };
    if env.set_float_array_region(&array, 0, values).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

fn double_array(env: &mut JNIEnv, values: &[f64]) -> jdoubleArray {
    let Ok(array) = env.new_double_array(values.len() as i32) else { return std::ptr::null_mut() };
    if env.set_double_array_region(&array, 0, values).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_analyzerNew(
    _env: JNIEnv,
    _class: JClass,
    sample_rate: jint,
    channels: jint,
) -> jlong {
    if sample_rate <= 0 || channels <= 0 {
        return 0;
    }
    Box::into_raw(Box::new(Analyzer::new(sample_rate as u32, channels as u32))) as jlong
}

/// Pushes the first `count` interleaved samples of `samples`.
#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_analyzerPush(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    samples: JFloatArray,
    count: jint,
) {
    // SAFETY: handles only come from analyzerNew and are freed once by analyzerFree.
    let Some(analyzer) = (unsafe { (handle as *mut Analyzer).as_mut() }) else { return };
    let len = env.get_array_length(&samples).unwrap_or(0).min(count).max(0) as usize;
    if len == 0 {
        return;
    }
    let mut buf = vec![0f32; len];
    if env.get_float_array_region(&samples, 0, &mut buf).is_ok() {
        analyzer.push(&buf);
    }
}

/// Returns [peak dB, RMS dB, BPM, Camelot number (0 = none), minor (1/0),
/// first downbeat in seconds (-1 = no beat grid)].
#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_analyzerFinish(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jfloatArray {
    // SAFETY: see analyzerPush.
    let Some(analyzer) = (unsafe { (handle as *const Analyzer).as_ref() }) else { return std::ptr::null_mut() };
    let r = analyzer.finish();
    let (number, minor) = r.key.map_or((0.0, 0.0), |k| (k.camelot_number() as f32, k.minor as u8 as f32));
    float_array(&mut env, &[r.peak_db, r.rms_db, r.bpm, number, minor, r.first_downbeat.unwrap_or(-1.0)])
}

/// `count` waveform overview values in 0...1.
#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_analyzerOverview(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    count: jint,
) -> jfloatArray {
    // SAFETY: see analyzerPush.
    let Some(analyzer) = (unsafe { (handle as *const Analyzer).as_ref() }) else { return std::ptr::null_mut() };
    float_array(&mut env, &analyzer.overview(count.max(0) as usize))
}

#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_analyzerFree(_env: JNIEnv, _class: JClass, handle: jlong) {
    if handle != 0 {
        // SAFETY: see analyzerPush; Kotlin zeroes its copy after freeing.
        drop(unsafe { Box::from_raw(handle as *mut Analyzer) });
    }
}

/// [outgoing gain, incoming gain] at fade position `t`, or null for an
/// unknown curve.
#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_crossfadeGains(
    mut env: JNIEnv,
    _class: JClass,
    t: jfloat,
    curve: jint,
) -> jfloatArray {
    let Some(curve) = Curve::from_raw(curve as u32) else { return std::ptr::null_mut() };
    let (a, b) = crossfade::gains(t, curve);
    float_array(&mut env, &[a, b])
}

/// [out start, in start, length, rate, lock] for a blend from the outgoing
/// track into the incoming one; see `mix::plan`.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_mixPlan(
    mut env: JNIEnv,
    _class: JClass,
    out_bpm: jdouble,
    out_downbeat: jdouble,
    out_duration: jdouble,
    in_bpm: jdouble,
    in_downbeat: jdouble,
    in_duration: jdouble,
) -> jdoubleArray {
    let p = mix::plan(
        &Timing { bpm: out_bpm, first_downbeat: out_downbeat, duration: out_duration },
        &Timing { bpm: in_bpm, first_downbeat: in_downbeat, duration: in_duration },
    );
    double_array(&mut env, &[p.out_start, p.in_start, p.length, p.rate, p.lock])
}

/// [progress, outgoing gain, incoming gain, action (0 = set speed, 1 = seek),
/// value] for a plan from `mixPlan`; see `mix::follow`.
#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_mixFollow(
    mut env: JNIEnv,
    _class: JClass,
    plan: JDoubleArray,
    out_pos: jdouble,
    in_pos: jdouble,
) -> jdoubleArray {
    let mut p = [0f64; 5];
    if env.get_double_array_region(&plan, 0, &mut p).is_err() {
        return std::ptr::null_mut();
    }
    let plan = Plan { out_start: p[0], in_start: p[1], length: p[2], rate: p[3], lock: p[4] };
    let f = mix::follow(&plan, out_pos, in_pos);
    let (action, value) = match f.action {
        Action::Speed(s) => (0.0, s),
        Action::Seek(x) => (1.0, x),
    };
    double_array(&mut env, &[f.progress, f.gain_out as f64, f.gain_in as f64, action, value])
}

/// [speed, position] putting the slave deck on the master's tempo and beat,
/// or null when they can't sync; see `mix::sync`.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_deckSync(
    mut env: JNIEnv,
    _class: JClass,
    master_bpm: jdouble,
    master_downbeat: jdouble,
    master_pos: jdouble,
    master_speed: jdouble,
    slave_bpm: jdouble,
    slave_downbeat: jdouble,
    slave_pos: jdouble,
    snap: jboolean,
) -> jdoubleArray {
    let master = MotifTiming { bpm: master_bpm, first_downbeat: master_downbeat, duration: 0.0 };
    let slave = MotifTiming { bpm: slave_bpm, first_downbeat: slave_downbeat, duration: 0.0 };
    match deck_sync(&master, master_pos, master_speed, &slave, slave_pos, snap == JNI_TRUE) {
        Some((speed, pos)) => double_array(&mut env, &[speed, pos]),
        None => std::ptr::null_mut(),
    }
}
/// [start, end] of a beat-aligned loop `beats` long at `pos`, or null
/// without a grid; see `mix::loop_at`.
#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_loopAt(
    mut env: JNIEnv,
    _class: JClass,
    bpm: jdouble,
    downbeat: jdouble,
    pos: jdouble,
    beats: jdouble,
) -> jdoubleArray {
    match (Timing { bpm, first_downbeat: downbeat, duration: 0.0 }).grid() {
        Some(grid) => {
            let (start, end) = mix::loop_at(&grid, pos, beats);
            double_array(&mut env, &[start, end])
        }
        None => std::ptr::null_mut(),
    }
}

/// The first downbeat at or after `pos`, or NaN without a grid; see `mix::next_downbeat`.
#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_nextDownbeat(
    _env: JNIEnv,
    _class: JClass,
    bpm: jdouble,
    downbeat: jdouble,
    pos: jdouble,
) -> jdouble {
    (Timing { bpm, first_downbeat: downbeat, duration: 0.0 }).grid().map_or(f64::NAN, |g| mix::next_downbeat(&g, pos))
}

// MARK: - Deck EQ and filter (motif_dsp::fx)
//
// Handles are boxed `DeckFx`s passed to Kotlin as `Long`, used only from the
// player's audio thread.

#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_fxNew(_env: JNIEnv, _class: JClass, sample_rate: jint, channels: jint) -> jlong {
    if sample_rate <= 0 || channels <= 0 {
        return 0;
    }
    DeckFx::new(sample_rate as u32, channels as u32).map_or(0, |f| Box::into_raw(Box::new(f)) as jlong)
}

/// Sets the knobs and filters the first `count` floats of the direct buffer
/// `samples` in place.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_fxProcess(
    env: JNIEnv,
    _class: JClass,
    handle: jlong,
    samples: JByteBuffer,
    count: jint,
    low: jdouble,
    mid: jdouble,
    high: jdouble,
    filter: jdouble,
) {
    // SAFETY: handles only come from fxNew and are freed once by fxFree.
    let Some(fx) = (unsafe { (handle as *mut DeckFx).as_mut() }) else { return };
    let (Ok(ptr), Ok(capacity)) = (env.get_direct_buffer_address(&samples), env.get_direct_buffer_capacity(&samples)) else { return };
    let len = (count.max(0) as usize).min(capacity / 4);
    if ptr.is_null() || len == 0 || ptr.align_offset(4) != 0 {
        return;
    }
    fx.set(Knobs { low, mid, high, filter });
    // SAFETY: the buffer is direct, holds at least `len` aligned floats, and Kotlin doesn't touch it during the call.
    fx.process(unsafe { std::slice::from_raw_parts_mut(ptr as *mut f32, len) });
}

#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_fxReset(_env: JNIEnv, _class: JClass, handle: jlong) {
    // SAFETY: as in fxProcess.
    if let Some(fx) = unsafe { (handle as *mut DeckFx).as_mut() } {
        fx.reset();
    }
}

#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_fxFree(_env: JNIEnv, _class: JClass, handle: jlong) {
    if handle != 0 {
        // SAFETY: as in fxProcess; Kotlin forgets the handle after this.
        drop(unsafe { Box::from_raw(handle as *mut DeckFx) });
    }
}

// MARK: - Tag cleanup and artist credits (motif_dsp::meta)

fn read_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

/// A Kotlin `Array<String?>`; null elements stay `None`.
fn read_strings(env: &mut JNIEnv, array: &JObjectArray) -> Vec<Option<String>> {
    if array.is_null() {
        return Vec::new();
    }
    let len = env.get_array_length(array).unwrap_or(0);
    (0..len)
        .map(|i| {
            let element = env.get_object_array_element(array, i).ok()?;
            read_string(env, &JString::from(element))
        })
        .collect()
}

fn new_string(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map_or(std::ptr::null_mut(), JString::into_raw)
}

fn new_strings(env: &mut JNIEnv, values: &[&str]) -> jobjectArray {
    let Ok(array) = env.new_object_array(values.len() as i32, "java/lang/String", JObject::null()) else {
        return std::ptr::null_mut();
    };
    for (i, value) in values.iter().enumerate() {
        let Ok(s) = env.new_string(value) else { return std::ptr::null_mut() };
        if env.set_object_array_element(&array, i as i32, s).is_err() {
            return std::ptr::null_mut();
        }
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_metaCleanerVersion(_env: JNIEnv, _class: JClass) -> jint {
    meta::CLEANER_VERSION as jint
}

#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_metaNorm(mut env: JNIEnv, _class: JClass, text: JString) -> jstring {
    let text = read_string(&mut env, &text).unwrap_or_default();
    new_string(&mut env, &meta::norm(&text))
}

/// A site name appended to several of a track's fields, or null.
#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_metaDetectSiteSuffix(
    mut env: JNIEnv,
    _class: JClass,
    fields: JObjectArray,
) -> jstring {
    let fields = read_strings(&mut env, &fields);
    let refs: Vec<Option<&str>> = fields.iter().map(Option::as_deref).collect();
    match meta::detect_site_suffix(&refs) {
        Some(suffix) => new_string(&mut env, &suffix),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_metaCleanField(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
    suffixes: JObjectArray,
) -> jstring {
    let text = read_string(&mut env, &text).unwrap_or_default();
    let suffixes: Vec<String> = read_strings(&mut env, &suffixes).into_iter().flatten().collect();
    new_string(&mut env, &meta::clean_field(&text, &suffixes))
}

/// Credited artists as [name, role, name, role, ...]. `known` holds names
/// already normalized with metaNorm.
#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_metaSplitArtists(
    mut env: JNIEnv,
    _class: JClass,
    credit: JString,
    known: JObjectArray,
) -> jobjectArray {
    let credit = read_string(&mut env, &credit).unwrap_or_default();
    let known: Vec<String> = read_strings(&mut env, &known).into_iter().flatten().collect();
    let credits = meta::split_artists(&credit, &known);
    let flat: Vec<&str> = credits.iter().flat_map(|c| [c.name.as_str(), c.role.as_str()]).collect();
    new_strings(&mut env, &flat)
}

// MARK: - Import dedupe (motif_dsp::dedupe)

fn int_array(env: &mut JNIEnv, values: &[i32]) -> jintArray {
    let Ok(array) = env.new_int_array(values.len() as i32) else { return std::ptr::null_mut() };
    if env.set_int_array_region(&array, 0, values).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

/// Tracks passed as parallel arrays: strings per track, and four numbers per
/// track in `numbers` ([duration ms, sample rate, bit depth, kbps], 0 = unknown).
struct Tracks {
    titles: Vec<Option<String>>,
    artists: Vec<Option<String>>,
    formats: Vec<Option<String>>,
    numbers: Vec<i64>,
}

impl Tracks {
    fn read(env: &mut JNIEnv, titles: &JObjectArray, artists: &JObjectArray, formats: &JObjectArray, numbers: &JLongArray) -> Option<Tracks> {
        let formats = read_strings(env, formats);
        let len = env.get_array_length(numbers).ok()? as usize;
        if len != formats.len() * 4 {
            return None;
        }
        let mut values = vec![0i64; len];
        env.get_long_array_region(numbers, 0, &mut values).ok()?;
        Some(Tracks { titles: read_strings(env, titles), artists: read_strings(env, artists), formats, numbers: values })
    }

    fn candidates(&self) -> Vec<Candidate<'_>> {
        let number = |i: usize, k: usize| self.numbers[i * 4 + k].clamp(0, u32::MAX as i64) as u32;
        (0..self.formats.len())
            .map(|i| Candidate {
                title: self.titles.get(i).and_then(|t| t.as_deref()).unwrap_or(""),
                artist: self.artists.get(i).and_then(|a| a.as_deref()),
                duration_ms: self.numbers[i * 4],
                format: self.formats[i].as_deref().unwrap_or(""),
                sample_rate: number(i, 1),
                bit_depth: number(i, 2),
                bitrate_kbps: number(i, 3),
            })
            .collect()
    }
}

/// Lead artist and title, normalized; equal keys are the same song.
#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_dedupeKey(mut env: JNIEnv, _class: JClass, title: JString, artist: JString) -> jstring {
    let title = read_string(&mut env, &title).unwrap_or_default();
    let artist = read_string(&mut env, &artist);
    new_string(&mut env, &dedupe::match_key(&title, artist.as_deref()))
}

/// [verdict (0 new, 1 duplicate, 2 upgrade), index into the existing tracks]
/// for track 0 against tracks 1..; null on malformed arrays.
#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_dedupeResolve(
    mut env: JNIEnv,
    _class: JClass,
    titles: JObjectArray,
    artists: JObjectArray,
    formats: JObjectArray,
    numbers: JLongArray,
) -> jintArray {
    let Some(tracks) = Tracks::read(&mut env, &titles, &artists, &formats, &numbers) else { return std::ptr::null_mut() };
    let all = tracks.candidates();
    let Some((incoming, existing)) = all.split_first() else { return std::ptr::null_mut() };
    let (verdict, index) = match dedupe::resolve(incoming, existing) {
        Verdict::New => (0, 0),
        Verdict::Duplicate(i) => (1, i),
        Verdict::Upgrade(i) => (2, i),
    };
    int_array(&mut env, &[verdict, index as i32])
}

/// Indexes of the tracks, best copy first.
#[no_mangle]
pub extern "system" fn Java_app_motif_dsp_MotifDsp_dedupeBestFirst(
    mut env: JNIEnv,
    _class: JClass,
    formats: JObjectArray,
    numbers: JLongArray,
) -> jintArray {
    let null = JObjectArray::from(JObject::null());
    let Some(tracks) = Tracks::read(&mut env, &null, &null, &formats, &numbers) else { return std::ptr::null_mut() };
    let order: Vec<i32> = dedupe::best_first(&tracks.candidates()).into_iter().map(|i| i as i32).collect();
    int_array(&mut env, &order)
}
