//! Prints tempo, beat grid and key for raw mono f32 audio, and how well
//! each minute's own grid agrees with the whole track's.
//! `ffmpeg -i song.mp3 -f f32le -ac 1 -ar 44100 - | cargo run --release --example analyze`
use motif_dsp::analysis::Analyzer;
use std::io::Read;

fn main() {
    let mut bytes = Vec::new();
    std::io::stdin().read_to_end(&mut bytes).unwrap();
    let audio: Vec<f32> = bytes.chunks_exact(4).map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]])).collect();
    let sr = 44_100usize;
    let whole = analyze(&audio);
    let Some(anchor) = whole.first_downbeat else {
        println!("bpm {:.2}, no grid", whole.bpm);
        return;
    };
    let beat = 60.0 / whole.bpm as f64;
    print!("bpm {:.2} downbeat {:.3}s key {:?} | per-minute phase error (ms):", whole.bpm, anchor, whole.key.map(|k| (k.camelot_number(), k.minor)));
    for (i, chunk) in audio.chunks(sr * 60).enumerate() {
        if chunk.len() < sr * 20 {
            break;
        }
        let r = analyze(chunk);
        if let Some(d) = r.first_downbeat {
            let local = (i * 60) as f64 + d as f64;
            let err = ((local - anchor as f64) / beat + 0.5).rem_euclid(1.0) - 0.5;
            print!(" {:+.0}", err * beat * 1000.0);
        } else {
            print!(" -");
        }
    }
    println!();
}

fn analyze(audio: &[f32]) -> motif_dsp::analysis::Analysis {
    let mut a = Analyzer::new(44_100, 1);
    a.push(audio);
    a.finish()
}
