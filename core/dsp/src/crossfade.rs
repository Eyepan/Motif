//! Crossfade gain curves for DJ-style transitions.

use std::f32::consts::FRAC_PI_2;

#[repr(u32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Curve {
    /// Constant perceived loudness through the fade. Default for music.
    EqualPower = 0,
    /// Constant amplitude sum. Dips in the middle on uncorrelated material.
    Linear = 1,
    /// Hard switch at the midpoint, for scratch-style cuts.
    Cut = 2,
}

impl Curve {
    pub fn from_raw(raw: u32) -> Option<Self> {
        match raw {
            0 => Some(Self::EqualPower),
            1 => Some(Self::Linear),
            2 => Some(Self::Cut),
            _ => None,
        }
    }
}

/// Gains for the outgoing (`a`) and incoming (`b`) decks at fade position
/// `t` in `[0, 1]`. Values outside the range are clamped.
pub fn gains(t: f32, curve: Curve) -> (f32, f32) {
    let t = if t.is_nan() { 0.0 } else { t.clamp(0.0, 1.0) };
    match curve {
        Curve::EqualPower => ((t * FRAC_PI_2).cos(), (t * FRAC_PI_2).sin()),
        Curve::Linear => (1.0 - t, t),
        Curve::Cut => if t < 0.5 { (1.0, 0.0) } else { (0.0, 1.0) },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endpoints() {
        for c in [Curve::EqualPower, Curve::Linear, Curve::Cut] {
            let (a, b) = gains(0.0, c);
            assert!((a - 1.0).abs() < 1e-6 && b.abs() < 1e-6);
            let (a, b) = gains(1.0, c);
            assert!(a.abs() < 1e-6 && (b - 1.0).abs() < 1e-6);
        }
    }

    #[test]
    fn equal_power_keeps_power_constant() {
        for i in 0..=100 {
            let (a, b) = gains(i as f32 / 100.0, Curve::EqualPower);
            assert!((a * a + b * b - 1.0).abs() < 1e-5);
        }
    }
}
