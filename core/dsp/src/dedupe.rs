//! Duplicate detection and quality ranking for imports, shared so every
//! platform keeps the same copy of a song. Pure functions over what the apps
//! already read from a file: cleaned tags, duration and format.
//!
//! Two files are the same recording when their match keys (lead artist and
//! title, normalized) are equal and their durations agree within a couple of
//! seconds. Between copies, lossless beats lossy; lossless copies then rank by
//! sample rate and bit depth, lossy ones by bitrate and then sample rate.

use crate::meta::{norm, split_artists, Role};
use std::cmp::Ordering;

/// What the apps know about one file, or one library track.
#[derive(Debug, Clone, Copy, Default)]
pub struct Candidate<'a> {
    /// Cleaned title (see `meta::clean_field`).
    pub title: &'a str,
    /// Cleaned artist credit, if tagged.
    pub artist: Option<&'a str>,
    /// 0 when unknown.
    pub duration_ms: i64,
    /// Library format name: "flac", "wav", "aiff", "alac", "mp3", "aac", "ogg"...
    pub format: &'a str,
    /// 0 when unknown.
    pub sample_rate: u32,
    /// 0 when unknown or not meaningful (lossy).
    pub bit_depth: u32,
    /// Average bitrate, 0 when unknown. Only ranks lossy copies.
    pub bitrate_kbps: u32,
}

/// What to do with an incoming file, given the library's tracks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    /// No copy yet: import it.
    New,
    /// `existing[i]` is the same recording at equal or better quality: skip it.
    Duplicate(usize),
    /// `existing[i]` is the same recording at lower quality: replace its file.
    Upgrade(usize),
}

/// Durations within this many milliseconds (or 1% of the longer one, if more)
/// are the same recording: encoders pad or trim a few frames.
const DURATION_SLACK_MS: i64 = 2_000;

const LOSSLESS: [&str; 7] = ["flac", "wav", "aiff", "alac", "ape", "wv", "dsf"];

/// Bracketed words that describe the file rather than the song, as in
/// "Song (320kbps)" or "Song [FLAC 24bit]".
const QUALITY_WORDS: [&str; 14] =
    ["kbps", "flac", "wav", "mp3", "aac", "m4a", "alac", "lossless", "hq", "hd", "hi-res", "hires", "bit", "khz"];

pub fn is_lossless(format: &str) -> bool {
    LOSSLESS.contains(&format.trim().to_lowercase().as_str())
}

/// The comparison key for a title and artist credit: the lead artist and the
/// title, lowercased, without punctuation, track numbers, "feat." credits or
/// quality markers. Equal keys are the same song (durations decide whether
/// it's the same recording).
pub fn match_key(title: &str, artist: Option<&str>) -> String {
    let lead = artist
        .map(|a| split_artists(a, &[]))
        .and_then(|credits| credits.into_iter().find(|c| c.role == Role::Primary))
        .map(|c| words(&c.name))
        .unwrap_or_default();
    format!("{lead}\u{1f}{}", title_words(title))
}

fn title_words(title: &str) -> String {
    let mut text = norm(title);
    text = strip_brackets(&text);
    text = strip_track_number(&text).to_string();
    let cleaned = words(&text);
    // A title that was nothing but a marker or a number stays as it was.
    if cleaned.is_empty() {
        words(&norm(title))
    } else {
        cleaned
    }
}

/// Lowercased words without punctuation. Letters and combining marks of
/// every script are kept: in Tamil and Hindi the marks are vowels.
fn words(text: &str) -> String {
    let spaced: String = text
        .chars()
        .map(|c| if c.is_ascii_punctuation() || is_unicode_punctuation(c) { ' ' } else { c })
        .collect();
    norm(&spaced)
}

fn is_unicode_punctuation(c: char) -> bool {
    matches!(c, '\u{2010}'..='\u{2027}' | '\u{2030}'..='\u{205e}' | '\u{3000}'..='\u{3003}' | '\u{00a1}' | '\u{00bf}' | '\u{00b7}')
}

/// Drops "(feat. X)", "(320kbps)", "[FLAC 24bit]" and the like; keeps
/// brackets that name a different version, such as "(Remix)" or "(Live)".
fn strip_brackets(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(open) = rest.find(['(', '[']) {
        let close_char = if rest[open..].starts_with('(') { ')' } else { ']' };
        let Some(len) = rest[open + 1..].find(close_char) else { break };
        let inner = &rest[open + 1..open + 1 + len];
        out.push_str(&rest[..open]);
        if !is_noise(inner) {
            out.push_str(&rest[open..open + len + 2]);
        }
        rest = &rest[open + len + 2..];
    }
    out.push_str(rest);
    out
}

fn is_noise(inner: &str) -> bool {
    let inner = inner.trim();
    let featured = ["feat. ", "feat ", "ft. ", "ft ", "featuring "].iter().any(|m| inner.starts_with(m));
    let quality = !inner.is_empty()
        && inner.split(|c: char| c.is_whitespace() || c == '/' || c == ',').filter(|w| !w.is_empty()).all(|w| {
            let w = w.trim_matches(|c: char| c.is_ascii_punctuation() && c != '-');
            let unit = w.trim_start_matches(|c: char| c.is_ascii_digit() || c == '.').trim_start_matches('-');
            QUALITY_WORDS.contains(&w) || QUALITY_WORDS.contains(&unit) || w.chars().all(|c| c.is_ascii_digit())
        });
    featured || quality
}

/// "01 - Song", "01. Song", "1) Song" and "01_Song" lose the number;
/// "99 Problems" keeps it.
fn strip_track_number(text: &str) -> &str {
    let digits = text.chars().take_while(char::is_ascii_digit).count();
    if digits == 0 || digits > 3 {
        return text;
    }
    let after = text[digits..].trim_start();
    match after.chars().next() {
        Some('.' | '-' | ')' | '_') => {
            let rest = after[1..].trim_start();
            if rest.is_empty() { text } else { rest }
        }
        _ => text,
    }
}

/// True when both are the same recording: equal keys and close durations.
/// An unknown (0) duration on either side matches on the key alone.
pub fn same_recording(a: &Candidate, b: &Candidate) -> bool {
    match_key(a.title, a.artist) == match_key(b.title, b.artist) && durations_match(a.duration_ms, b.duration_ms)
}

fn durations_match(a: i64, b: i64) -> bool {
    if a <= 0 || b <= 0 {
        return true;
    }
    let slack = DURATION_SLACK_MS.max(a.max(b) / 100);
    (a - b).abs() <= slack
}

/// Orders two copies by quality; `Greater` means `a` is the better copy.
pub fn compare_quality(a: &Candidate, b: &Candidate) -> Ordering {
    let (la, lb) = (is_lossless(a.format), is_lossless(b.format));
    la.cmp(&lb).then_with(|| {
        if la {
            a.sample_rate.cmp(&b.sample_rate).then(a.bit_depth.cmp(&b.bit_depth))
        } else {
            a.bitrate_kbps.cmp(&b.bitrate_kbps).then(a.sample_rate.cmp(&b.sample_rate))
        }
    })
}

/// Decides what to do with `incoming` given `existing` tracks (the whole
/// library, or just those sharing its [`match_key`]). Among several matching
/// copies, the best one is the one compared against and upgraded.
pub fn resolve(incoming: &Candidate, existing: &[Candidate]) -> Verdict {
    let key = match_key(incoming.title, incoming.artist);
    let best = existing
        .iter()
        .enumerate()
        .filter(|(_, e)| match_key(e.title, e.artist) == key && durations_match(incoming.duration_ms, e.duration_ms))
        .max_by(|(_, a), (_, b)| compare_quality(a, b));
    match best {
        None => Verdict::New,
        Some((i, e)) if compare_quality(incoming, e) == Ordering::Greater => Verdict::Upgrade(i),
        Some((i, _)) => Verdict::Duplicate(i),
    }
}

/// Sorts a batch best copy first, so when one download holds several copies
/// of a song the best is imported and the rest are skipped. Returns indexes.
pub fn best_first(batch: &[Candidate]) -> Vec<usize> {
    let mut order: Vec<usize> = (0..batch.len()).collect();
    order.sort_by(|&a, &b| compare_quality(&batch[b], &batch[a]));
    order
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track<'a>(title: &'a str, artist: &'a str, ms: i64, format: &'a str, rate: u32, depth: u32, kbps: u32) -> Candidate<'a> {
        Candidate {
            title,
            artist: Some(artist),
            duration_ms: ms,
            format,
            sample_rate: rate,
            bit_depth: depth,
            bitrate_kbps: kbps,
        }
    }

    #[test]
    fn keys_ignore_noise() {
        let k = match_key("Hukum Reloaded", Some("Anirudh Ravichander"));
        assert_eq!(match_key("01 - Hukum Reloaded (320kbps)", Some("anirudh  ravichander")), k);
        assert_eq!(match_key("Hukum Reloaded [FLAC 24-bit / 96kHz]", Some("Anirudh Ravichander, Arivu")), k);
        assert_eq!(match_key("Hukum Reloaded (feat. Arivu)", Some("Anirudh Ravichander feat. Arivu")), k);
        assert_eq!(match_key("Hukum: Reloaded!", Some("Anirudh Ravichander")), k);
        assert_ne!(match_key("Hukum Reloaded (Remix)", Some("Anirudh Ravichander")), k);
        assert_ne!(match_key("Hukum Reloaded", Some("Someone Else")), k);
        assert_eq!(match_key("99 Problems", None), "\u{1f}99 problems");
        assert_eq!(match_key("1. Intro", None), "\u{1f}intro");
        assert_eq!(match_key("(320kbps)", None), "\u{1f}320kbps");
    }

    #[test]
    fn keys_keep_non_latin_scripts() {
        // Tamil vowel signs are combining marks; dropping them would merge different words.
        assert_ne!(match_key("காதல்", None), match_key("காதல", None));
        assert_eq!(match_key("காதல் — Love", None), match_key("காதல் Love", None));
    }

    #[test]
    fn durations_must_agree() {
        let a = track("Veramaari", "Anirudh", 214_000, "mp3", 44_100, 0, 320);
        assert!(same_recording(&a, &Candidate { duration_ms: 215_500, ..a }));
        assert!(!same_recording(&a, &Candidate { duration_ms: 120_000, ..a }));
        assert!(same_recording(&a, &Candidate { duration_ms: 0, ..a }));
        // 1% of a long mix is more than two seconds.
        let long = Candidate { duration_ms: 3_600_000, ..a };
        assert!(same_recording(&long, &Candidate { duration_ms: 3_630_000, ..a }));
    }

    #[test]
    fn quality_order() {
        let mp3_128 = track("t", "a", 1, "mp3", 44_100, 0, 128);
        let mp3_320 = track("t", "a", 1, "mp3", 44_100, 0, 320);
        let cd = track("t", "a", 1, "flac", 44_100, 16, 0);
        let wav = track("t", "a", 1, "WAV", 44_100, 16, 0);
        let hires = track("t", "a", 1, "wav", 96_000, 24, 0);
        let deep = track("t", "a", 1, "flac", 44_100, 24, 0);
        assert_eq!(compare_quality(&mp3_320, &mp3_128), Ordering::Greater);
        assert_eq!(compare_quality(&cd, &mp3_320), Ordering::Greater);
        assert_eq!(compare_quality(&cd, &wav), Ordering::Equal);
        assert_eq!(compare_quality(&hires, &deep), Ordering::Greater);
        assert_eq!(compare_quality(&deep, &cd), Ordering::Greater);
        assert_eq!(best_first(&[mp3_128, hires, mp3_320, cd]), vec![1, 3, 2, 0]);
    }

    #[test]
    fn resolve_keeps_the_best_copy() {
        let mp3 = track("Ala Bolelo", "Anirudh", 200_000, "mp3", 44_100, 0, 320);
        let flac = track("Ala Bolelo", "Anirudh", 200_400, "flac", 44_100, 16, 0);
        let other = track("Bindaas", "Anirudh", 200_000, "mp3", 44_100, 0, 320);
        assert_eq!(resolve(&mp3, &[]), Verdict::New);
        assert_eq!(resolve(&mp3, &[other]), Verdict::New);
        assert_eq!(resolve(&flac, &[other, mp3]), Verdict::Upgrade(1));
        assert_eq!(resolve(&mp3, &[other, flac]), Verdict::Duplicate(1));
        assert_eq!(resolve(&mp3, &[mp3]), Verdict::Duplicate(0));
        // Against several copies, the best one decides.
        let low = Candidate { bitrate_kbps: 128, ..mp3 };
        assert_eq!(resolve(&mp3, &[low, flac]), Verdict::Duplicate(1));
        assert_eq!(resolve(&flac, &[low, mp3]), Verdict::Upgrade(1));
    }
}
