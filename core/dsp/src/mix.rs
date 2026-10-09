//! Beat-aligned blends and deck sync. Pure maths over track positions, so
//! every platform drives its own players and gets the same mixes.
//!
//! A blend is planned once ([plan]) and then followed ([follow]) a few times a
//! second with both players' positions: the result gives the crossfader gains
//! and how to keep the incoming track on the outgoing one's beat. Early in the
//! blend, while the incoming track is still quiet, it may jump to fix start-up
//! lag; after that it only speeds up or slows down slightly, which is
//! inaudible with a pitch-preserving time stretch.

use crate::beatgrid::Grid;
use crate::crossfade::{self, Curve};

/// Largest tempo change applied to a track to match another, as a fraction.
pub const MAX_TEMPO_ADJUST: f64 = 0.08;

/// Longest blend, seconds.
const MAX_BLEND: f64 = 32.0;
/// Shortest blend when the tracks aren't beat aligned, seconds.
const MIN_FREE_BLEND: f64 = 8.0;
/// Blend lengths tried for aligned blends, in bars, longest first.
const BLEND_BARS: [u32; 3] = [16, 8, 4];
/// Aligned blends start on a phrase boundary of this many bars.
const PHRASE_BARS: f64 = 4.0;
/// Gap left between the end of a blend and the end of the outgoing track.
const END_MARGIN: f64 = 0.3;

/// Jumps are allowed until this far into the blend (incoming gain ~0.3).
const SEEK_UNTIL: f64 = 0.2;
/// Errors above this, seconds, are fixed by a jump while jumps are allowed.
const SEEK_ABOVE: f64 = 0.025;
/// Phase errors at or below this, seconds, are left alone.
const PHASE_TOLERANCE: f64 = 0.006;
/// Phase errors are corrected over roughly this many seconds.
const CATCH_UP: f64 = 1.0;
/// Largest speed nudge, as a fraction.
const MAX_NUDGE: f64 = 0.03;
/// Nudges are rounded to this step, so a player's speed changes rarely.
const NUDGE_STEP: f64 = 0.0025;

/// What a platform knows about a track for mixing.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Timing {
    /// 0 or less when unknown.
    pub bpm: f64,
    /// Seconds to the first downbeat; negative or NaN when there is no grid.
    pub first_downbeat: f64,
    /// Seconds.
    pub duration: f64,
}

impl Timing {
    pub fn grid(&self) -> Option<Grid> {
        (self.bpm > 0.0 && self.first_downbeat >= 0.0).then_some(Grid { bpm: self.bpm, first_downbeat: self.first_downbeat })
    }
}

/// Speed for a track at `bpm` to play at `target_bpm` (or half or double
/// it), if that's within `max_adjust`.
pub fn tempo_match(target_bpm: f64, bpm: f64, max_adjust: f64) -> Option<f64> {
    if !(target_bpm > 0.0 && bpm > 0.0) {
        return None;
    }
    [target_bpm / bpm, target_bpm * 2.0 / bpm, target_bpm / (2.0 * bpm)]
        .into_iter()
        .min_by(|a, b| (a - 1.0).abs().total_cmp(&(b - 1.0).abs()))
        .filter(|r| (r - 1.0).abs() <= max_adjust)
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Plan {
    /// Outgoing track position where the blend starts.
    pub out_start: f64,
    /// Incoming track position at that moment.
    pub in_start: f64,
    /// Blend length, seconds of outgoing track (played at normal speed).
    pub length: f64,
    /// Incoming track speed during the blend.
    pub rate: f64,
    /// Beat length both tracks lock to, in seconds of real time; 0 when the
    /// blend isn't beat aligned.
    pub lock: f64,
}

/// Plans a blend from the end of `outgoing` into `incoming`. With grids on
/// both and tempos within reach it's a phrase-aligned 16, 8 or 4 bar blend
/// from downbeat to downbeat; otherwise a tempo-matched (when possible)
/// crossfade over the last 8 to 32 seconds.
pub fn plan(outgoing: &Timing, incoming: &Timing) -> Plan {
    let rate = tempo_match(outgoing.bpm, incoming.bpm, MAX_TEMPO_ADJUST);
    let longest = MAX_BLEND.min(outgoing.duration / 3.0);
    if let (Some(out), Some(inc), Some(rate)) = (outgoing.grid(), incoming.grid(), rate) {
        let bar = out.bar_length();
        if let Some(bars) = BLEND_BARS.iter().find(|&&b| b as f64 * bar <= longest) {
            let length = *bars as f64 * bar;
            let latest = outgoing.duration - length - END_MARGIN;
            if latest >= out.first_downbeat {
                let phrase = PHRASE_BARS * bar;
                let out_start = out.first_downbeat + ((latest - out.first_downbeat) / phrase).floor() * phrase;
                return Plan {
                    out_start,
                    in_start: inc.first_downbeat,
                    length,
                    rate,
                    lock: out.beat_length().max(inc.beat_length() / rate),
                };
            }
        }
    }
    let length = if outgoing.bpm > 0.0 { 16.0 * 4.0 * 60.0 / outgoing.bpm } else { 16.0 }
        .clamp(MIN_FREE_BLEND, MAX_BLEND)
        .min(outgoing.duration / 3.0);
    Plan {
        out_start: (outgoing.duration - length - END_MARGIN).max(0.0),
        in_start: 0.0,
        length,
        rate: rate.unwrap_or(1.0),
        lock: 0.0,
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Action {
    /// Play the incoming track at this speed.
    Speed(f64),
    /// Move the incoming track to this position (keep the speed).
    Seek(f64),
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Follow {
    /// 0 at the start of the blend, 1 at the end.
    pub progress: f64,
    pub gain_out: f32,
    pub gain_in: f32,
    pub action: Action,
}

/// Where a blend stands with the outgoing track at `out_pos` and the incoming
/// one at `in_pos` (seconds into each track).
pub fn follow(plan: &Plan, out_pos: f64, in_pos: f64) -> Follow {
    let progress = if plan.length > 0.0 { ((out_pos - plan.out_start) / plan.length).clamp(0.0, 1.0) } else { 1.0 };
    let (gain_out, gain_in) = crossfade::gains(progress as f32, Curve::EqualPower);
    let action = if plan.lock <= 0.0 {
        Action::Speed(plan.rate)
    } else {
        let expected = plan.in_start + (out_pos - plan.out_start).max(0.0) * plan.rate;
        let error = (in_pos - expected) / plan.rate;
        if progress < SEEK_UNTIL && error.abs() > SEEK_ABOVE {
            Action::Seek(expected)
        } else {
            Action::Speed(nudged(plan.rate, wrap(error, plan.lock)))
        }
    };
    Follow { progress, gain_out, gain_in, action }
}

/// A deck in a two-deck mix: its track and where it is.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Deck {
    pub grid: Grid,
    /// Seconds into the track.
    pub position: f64,
    pub speed: f64,
}

/// Speed and position that put `slave` on `master`'s tempo and beat. With
/// `snap` the position jumps straight onto the beat (pressing SYNC);
/// otherwise the position is left alone and the speed is nudged to drift
/// back into phase (staying locked). `None` when the tempos are too far apart.
pub fn sync(master: &Deck, slave: &Deck, snap: bool) -> Option<(f64, f64)> {
    let ratio = tempo_match(master.grid.bpm * master.speed, slave.grid.bpm, MAX_TEMPO_ADJUST)?;
    let master_beat = master.grid.beat_length() / master.speed;
    let slave_beat = slave.grid.beat_length() / ratio;
    let lock = master_beat.max(slave_beat);
    // Real-time distance of each deck from its own grid, compared on the lock beat.
    let m = (master.position - master.grid.first_downbeat) / master.speed;
    let s = (slave.position - slave.grid.first_downbeat) / ratio;
    let error = wrap(s - m, lock);
    Some(if snap { (ratio, slave.position - error * ratio) } else { (nudged(ratio, error), slave.position) })
}

/// A loop `beats` long (1/4 to 32) on the grid: it starts on the beat (or
/// the quarter or half beat, for loops shorter than a beat) at or just before
/// `position`, so pressing LOOP mid-beat keeps the music going and repeats
/// from the beat it was on. Returns (start, end) in seconds.
pub fn loop_at(grid: &Grid, position: f64, beats: f64) -> (f64, f64) {
    let beats = beats.clamp(0.25, 32.0);
    let length = beats * grid.beat_length();
    let unit = grid.beat_length() * beats.min(1.0);
    // A press a hair early still lands on the beat it meant.
    let n = ((position - grid.first_downbeat) / unit + 0.02).floor();
    let mut start = grid.first_downbeat + n * unit;
    while start < 0.0 {
        start += unit;
    }
    (start, start + length)
}

/// The first downbeat at or after `position`.
pub fn next_downbeat(grid: &Grid, position: f64) -> f64 {
    let bar = grid.bar_length();
    let n = ((position - grid.first_downbeat) / bar - 1e-9).ceil();
    grid.first_downbeat + n * bar
}

/// `x` wrapped into `[-period / 2, period / 2)`.
fn wrap(x: f64, period: f64) -> f64 {
    (x + period / 2.0).rem_euclid(period) - period / 2.0
}

/// `rate` adjusted to close a phase `error` (seconds, positive = ahead).
fn nudged(rate: f64, error: f64) -> f64 {
    if error.abs() <= PHASE_TOLERANCE {
        return rate;
    }
    let nudge = (error / CATCH_UP).clamp(-MAX_NUDGE, MAX_NUDGE);
    // Errors above the tolerance always round to at least one step.
    rate * (1.0 - (nudge / NUDGE_STEP).round() * NUDGE_STEP)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn timing(bpm: f64, first_downbeat: f64, duration: f64) -> Timing {
        Timing { bpm, first_downbeat, duration }
    }

    #[test]
    fn tempo_match_takes_half_and_double_time() {
        assert_eq!(tempo_match(124.0, 120.0, MAX_TEMPO_ADJUST), Some(124.0 / 120.0));
        assert_eq!(tempo_match(124.0, 100.0, MAX_TEMPO_ADJUST), None);
        assert_eq!(tempo_match(140.0, 70.0, MAX_TEMPO_ADJUST), Some(1.0));
        assert_eq!(tempo_match(87.0, 172.0, MAX_TEMPO_ADJUST), Some(174.0 / 172.0));
        assert_eq!(tempo_match(0.0, 120.0, MAX_TEMPO_ADJUST), None);
    }

    #[test]
    fn aligned_plan_runs_downbeat_to_downbeat() {
        let out = timing(128.0, 0.4, 300.0);
        let inc = timing(125.0, 1.1, 280.0);
        let p = plan(&out, &inc);
        let bar = 240.0 / 128.0;
        assert!((p.length - 16.0 * bar).abs() < 1e-9);
        assert!((p.rate - 128.0 / 125.0).abs() < 1e-12);
        assert_eq!(p.in_start, 1.1);
        // On a 4-bar phrase boundary, ending before the track does, as late as possible.
        let phrases = (p.out_start - 0.4) / (4.0 * bar);
        assert!((phrases - phrases.round()).abs() < 1e-9);
        assert!(p.out_start + p.length <= 300.0 - END_MARGIN);
        assert!(p.out_start + p.length + 4.0 * bar > 300.0 - END_MARGIN);
        assert!((p.lock - 60.0 / 128.0).abs() < 1e-9);
    }

    #[test]
    fn slow_tracks_get_shorter_blends() {
        // 16 bars at 90 BPM is 42.7 s, past the 32 s cap, so 8 bars.
        let p = plan(&timing(90.0, 0.0, 300.0), &timing(92.0, 0.0, 300.0));
        assert!((p.length - 8.0 * 240.0 / 90.0).abs() < 1e-9);
        // A 40 s track allows a third: 13.3 s, so 4 bars at 128.
        let p = plan(&timing(128.0, 0.0, 40.0), &timing(128.0, 0.0, 300.0));
        assert!((p.length - 4.0 * 240.0 / 128.0).abs() < 1e-9);
    }

    #[test]
    fn unaligned_plan_matches_the_old_blend() {
        // No grid on the incoming track: tempo still matched, no phase lock.
        let p = plan(&timing(124.0, 0.2, 300.0), &timing(120.0, -1.0, 300.0));
        assert_eq!(p.lock, 0.0);
        assert!((p.length - 30.97).abs() < 0.01);
        assert!((p.rate - 124.0 / 120.0).abs() < 1e-12);
        assert_eq!(p.in_start, 0.0);
        // Tempos too far apart: plain crossfade.
        let p = plan(&timing(124.0, 0.2, 300.0), &timing(100.0, 0.2, 300.0));
        assert_eq!((p.lock, p.rate), (0.0, 1.0));
        let p = plan(&timing(0.0, -1.0, 30.0), &timing(0.0, -1.0, 30.0));
        assert!((p.length - 10.0).abs() < 1e-9);
        assert!(matches!(follow(&p, 25.0, 3.0).action, Action::Speed(r) if r == 1.0));
    }

    #[test]
    fn follow_fades_and_holds_phase() {
        let p = plan(&timing(128.0, 0.4, 300.0), &timing(125.0, 1.1, 280.0));
        let at = |t: f64| p.out_start + t * p.length;
        let on_beat = |t: f64| p.in_start + t * p.length * p.rate;

        let f = follow(&p, p.out_start - 5.0, 0.0);
        assert_eq!((f.progress, f.gain_out, f.gain_in), (0.0, 1.0, 0.0));

        // Started 80 ms late: jump while it's still quiet.
        let f = follow(&p, at(0.05), on_beat(0.05) - 0.08);
        assert!(matches!(f.action, Action::Seek(x) if (x - on_beat(0.05)).abs() < 1e-9));

        // On the beat: keep the matched speed.
        let f = follow(&p, at(0.5), on_beat(0.5) + 0.002);
        assert_eq!(f.action, Action::Speed(p.rate));
        assert!((f.gain_out - f.gain_in).abs() < 1e-6);

        // 20 ms ahead later on: slow down slightly rather than jump.
        let Action::Speed(r) = follow(&p, at(0.5), on_beat(0.5) + 0.02 * p.rate).action else { panic!() };
        assert!(r < p.rate && r > p.rate * 0.97);
        // A whole beat off is still on the beat.
        let f = follow(&p, at(0.5), on_beat(0.5) + p.lock * p.rate);
        assert_eq!(f.action, Action::Speed(p.rate));

        let f = follow(&p, at(1.2), 0.0);
        assert_eq!(f.progress, 1.0);
        assert!(f.gain_out.abs() < 1e-6);
    }

    #[test]
    fn sync_snaps_then_locks() {
        let master = Deck { grid: Grid { bpm: 126.0, first_downbeat: 0.3 }, position: 61.0, speed: 1.02 };
        let slave = Deck { grid: Grid { bpm: 124.0, first_downbeat: 0.9 }, position: 40.0, speed: 1.0 };
        let (speed, position) = sync(&master, &slave, true).unwrap();
        assert!((speed * 124.0 - 126.0 * 1.02).abs() < 1e-9);
        // After snapping, both are the same real time past a beat.
        let synced = Deck { position, speed, ..slave };
        let beat = 60.0 / (126.0 * 1.02);
        let phase = |d: &Deck| ((d.position - d.grid.first_downbeat) / d.speed / beat).rem_euclid(1.0);
        assert!((phase(&master) - phase(&synced)).abs() < 1e-9);
        assert!((position - 40.0).abs() <= 60.0 / 124.0 * speed / 2.0 + 1e-9);
        // Locked: no change in phase, and a lagging deck is sped up.
        assert_eq!(sync(&master, &synced, false), Some((speed, position)));
        let behind = Deck { position: position - 0.02, ..synced };
        assert!(sync(&master, &behind, false).unwrap().0 > speed);
        // Too far apart to sync.
        let far = Deck { grid: Grid { bpm: 100.0, first_downbeat: 0.0 }, ..slave };
        assert_eq!(sync(&master, &far, true), None);
    }

    #[test]
    fn loops_start_on_the_beat() {
        let g = Grid { bpm: 120.0, first_downbeat: 0.3 };
        // Beats at 0.3, 0.8, 1.3...: a 4-beat loop pressed at 1.0 starts at 0.8.
        let (s, e) = loop_at(&g, 1.0, 4.0);
        assert!((s - 0.8).abs() < 1e-9 && (e - 2.8).abs() < 1e-9);
        // Pressed 5 ms early: still that beat.
        assert!((loop_at(&g, 1.295, 1.0).0 - 1.3).abs() < 1e-9);
        // Half-beat loops snap to half beats.
        let (s, e) = loop_at(&g, 1.1, 0.5);
        assert!((s - 1.05).abs() < 1e-9 && (e - 1.3).abs() < 1e-9);
        // Before the first downbeat, never before the track starts.
        assert!(loop_at(&g, 0.1, 1.0).0 >= 0.0);
        assert!((loop_at(&g, 0.1, 64.0).1 - loop_at(&g, 0.1, 64.0).0 - 16.0).abs() < 1e-9);
    }

    #[test]
    fn next_downbeat_is_on_a_bar_line() {
        let g = Grid { bpm: 120.0, first_downbeat: 0.3 };
        assert!((next_downbeat(&g, 0.0) - 0.3).abs() < 1e-9);
        assert!((next_downbeat(&g, 0.3) - 0.3).abs() < 1e-9);
        assert!((next_downbeat(&g, 0.31) - 2.3).abs() < 1e-9);
        assert!((next_downbeat(&g, 9.0) - 10.3).abs() < 1e-9);
    }

    #[test]
    fn nudges_are_stepped_and_capped() {
        assert_eq!(nudged(1.0, 0.004), 1.0);
        assert!((nudged(1.0, 0.007) - (1.0 - 3.0 * NUDGE_STEP)).abs() < 1e-12);
        assert!((nudged(1.0, -0.5) - (1.0 + MAX_NUDGE)).abs() < 1e-12);
        assert!((wrap(0.9, 1.0) + 0.1).abs() < 1e-12);
    }
}
