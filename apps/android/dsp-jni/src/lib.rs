//! JNI entry points for `app.motif.dsp.MotifDsp`. Keep the signatures in sync
//! with the `external fun` declarations there. Analyzer handles are boxed
//! `Analyzer`s passed to Kotlin as `Long`.

use jni::objects::{JClass, JFloatArray};
use jni::sys::{jfloat, jfloatArray, jint, jlong};
use jni::JNIEnv;
use motif_dsp::analysis::Analyzer;
use motif_dsp::crossfade::{self, Curve};

fn float_array(env: &mut JNIEnv, values: &[f32]) -> jfloatArray {
    let Ok(array) = env.new_float_array(values.len() as i32) else { return std::ptr::null_mut() };
    if env.set_float_array_region(&array, 0, values).is_err() {
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

/// Returns [peak dB, RMS dB, BPM, Camelot number (0 = none), minor (1/0)].
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
    float_array(&mut env, &[r.peak_db, r.rms_db, r.bpm, number, minor])
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
