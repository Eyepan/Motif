//! Tag cleanup and artist credit splitting, shared so every platform turns
//! the same messy tags into the same library. Pure text functions, no I/O.
//! Design: docs/metadata.md.
//!
//! Callers keep the raw tags (`track_tags`) and store the cleaned values, so
//! when these rules change ([`CLEANER_VERSION`]) the library can be re-cleaned
//! from the raw tags.

/// Bump when cleanup or splitting rules change, so apps re-clean their library.
pub const CLEANER_VERSION: u32 = 1;

/// Separators a site or uploader name is appended with, e.g. "Song - Site".
const SUFFIX_SEPARATORS: [&str; 4] = [" - ", " – ", " — ", " | "];
/// A site suffix is a short name, not a phrase.
const MAX_SUFFIX_CHARS: usize = 40;

/// Collapses runs of whitespace and trims.
pub fn tidy(text: &str) -> String {
    text.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Case- and whitespace-insensitive comparison key.
pub fn norm(text: &str) -> String {
    tidy(text).to_lowercase()
}

/// The text after the last suffix separator, if it could be a site name.
fn suffix_of(text: &str) -> Option<&str> {
    let (at, sep) = SUFFIX_SEPARATORS
        .iter()
        .filter_map(|sep| text.rfind(sep).map(|at| (at, *sep)))
        .max_by_key(|(at, _)| *at)?;
    let head = text[..at].trim();
    let tail = text[at + sep.len()..].trim();
    let short = !tail.is_empty() && tail.chars().count() <= MAX_SUFFIX_CHARS;
    (short && !head.is_empty()).then_some(tail)
}

/// True for "www.example.com", "example.in", "Example.Com" and the like.
fn looks_like_domain(text: &str) -> bool {
    let t = text.trim().trim_matches(|c| matches!(c, '(' | ')' | '[' | ']'));
    if t.contains(' ') || !t.contains('.') {
        return false;
    }
    let tld = t.rsplit('.').next().unwrap_or("");
    t.to_lowercase().starts_with("www.")
        || (2..=6).contains(&tld.len()) && tld.chars().all(|c| c.is_ascii_alphabetic())
}

/// A site name appended to several fields of one track, like "Jailer 2 -
/// MassTamilan" and "Anirudh Ravichander - MassTamilan". A suffix counts when
/// at least two of the fields end with it, or one ends with a domain name.
/// `fields` are a track's title, artist, album and album artist (any order,
/// `None` for missing).
pub fn detect_site_suffix(fields: &[Option<&str>]) -> Option<String> {
    let suffixes: Vec<&str> = fields.iter().flatten().filter_map(|f| suffix_of(f.trim())).collect();
    for (i, s) in suffixes.iter().enumerate() {
        let shared = suffixes.iter().skip(i + 1).any(|o| norm(o) == norm(s));
        if shared || looks_like_domain(s) {
            return Some(tidy(s));
        }
    }
    None
}

/// Cleans one tag value: tidies whitespace, drops bracketed domains such as
/// "(www.site.com)", and removes any of `site_suffixes` (from
/// [`detect_site_suffix`], usually collected across the whole library) or a
/// trailing domain. Never returns an empty string for a non-empty input.
pub fn clean_field(text: &str, site_suffixes: &[String]) -> String {
    let mut out = tidy(text);
    // "(www.site.com)" / "[site.in]" anywhere.
    let mut kept = Vec::new();
    for word in out.split(' ') {
        let bracketed = (word.starts_with('(') && word.ends_with(')')) || (word.starts_with('[') && word.ends_with(']'));
        if !(bracketed && looks_like_domain(word)) {
            kept.push(word);
        }
    }
    if !kept.is_empty() {
        out = kept.join(" ");
    }
    // Repeated, in case a file carries "Song - Site - www.site.com".
    while let Some(tail) = suffix_of(&out) {
        let known = site_suffixes.iter().any(|s| norm(s) == norm(tail));
        if !(known || looks_like_domain(tail)) {
            break;
        }
        let cut = out.len() - tail.len();
        let head = out[..cut].trim_end();
        let head = SUFFIX_SEPARATORS
            .iter()
            .find_map(|sep| head.strip_suffix(sep.trim_end()))
            .unwrap_or(head);
        out = head.trim_end().to_string();
    }
    out
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    Primary,
    Featured,
}

impl Role {
    pub fn as_str(self) -> &'static str {
        match self {
            Role::Primary => "primary",
            Role::Featured => "featured",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Credit {
    pub name: String,
    pub role: Role,
}

/// Finds `needle` case-insensitively; returns the byte range in `hay`.
fn find_ci(hay: &str, needle: &str) -> Option<(usize, usize)> {
    let lower = hay.to_lowercase();
    // Lowercasing can change byte lengths outside ASCII; only trust a match
    // when the prefix length is unchanged.
    let at = lower.find(needle)?;
    (hay.is_char_boundary(at) && hay[..at].to_lowercase().len() == at).then_some((at, at + needle.len()))
}

/// Splits "A feat. B" / "A (ft. B)" into the primary and featured parts.
fn split_featured(credit: &str) -> (String, Option<String>) {
    const MARKERS: [&str; 6] = [" featuring ", " feat. ", " feat ", " ft. ", " ft ", " with "];
    for marker in MARKERS {
        for (open, close) in [("(", ")"), ("[", "]")] {
            let bracketed = format!("{open}{}", marker.trim_start());
            if let Some((start, end)) = find_ci(credit, &bracketed) {
                let rest = &credit[end..];
                let inner = rest.split(close).next().unwrap_or(rest);
                return (credit[..start].trim().to_string(), Some(inner.trim().to_string()));
            }
        }
        // "with" is only a separator in brackets: "Above & Beyond" style names use it.
        if marker == " with " {
            continue;
        }
        if let Some((start, end)) = find_ci(credit, marker) {
            return (credit[..start].trim().to_string(), Some(credit[end..].trim().to_string()));
        }
    }
    (credit.trim().to_string(), None)
}

/// Splits a list of names on "," and ";", and on "&" / "and" when that is
/// safe: inside a comma list ("A, B & C"), or when every part is an artist
/// already in `known` (normalized with [`norm`]). That keeps names like
/// "Simon & Garfunkel" whole until both halves show up on their own.
fn split_list(part: &str, known: &[String]) -> Vec<String> {
    let pieces: Vec<String> = part
        .split([',', ';'])
        .map(tidy)
        .filter(|p| !p.is_empty())
        .collect();
    let in_list = pieces.len() > 1;
    let mut out = Vec::new();
    for piece in pieces {
        let halves: Vec<String> = piece
            .split(" & ")
            .flat_map(|p| p.split(" and "))
            .map(tidy)
            .filter(|p| !p.is_empty())
            .collect();
        let all_known = halves.iter().all(|h| known.iter().any(|k| k == &norm(h)));
        if halves.len() > 1 && (in_list || all_known) {
            out.extend(halves);
        } else {
            out.push(piece);
        }
    }
    out
}

/// Splits a tagged artist credit into credited artists, in order, without
/// duplicates. `known` holds normalized names of artists the library already
/// has, used to decide whether "A & B" is a duo or two artists.
pub fn split_artists(credit: &str, known: &[String]) -> Vec<Credit> {
    let (primary, featured) = split_featured(&tidy(credit));
    let mut out: Vec<Credit> = Vec::new();
    let mut push = |name: String, role: Role| {
        if !name.is_empty() && !out.iter().any(|c| norm(&c.name) == norm(&name)) {
            out.push(Credit { name, role });
        }
    };
    for name in split_list(&primary, known) {
        push(name, Role::Primary);
    }
    for name in featured.iter().flat_map(|f| split_list(f, known)) {
        push(name, Role::Featured);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(credits: &[Credit]) -> Vec<(&str, &str)> {
        credits.iter().map(|c| (c.name.as_str(), c.role.as_str())).collect()
    }

    #[test]
    fn detects_suffix_shared_by_fields() {
        let fields = [
            Some("Dileep Theme (Instrumental) - MassTamilan"),
            Some("Anirudh Ravichander - MassTamilan"),
            Some("Jailer 2 - MassTamilan"),
            None,
        ];
        assert_eq!(detect_site_suffix(&fields).as_deref(), Some("MassTamilan"));
        // One field alone is not enough unless it is a domain.
        assert_eq!(detect_site_suffix(&[Some("Intro - Live"), Some("Band"), Some("Tour")]), None);
        assert_eq!(detect_site_suffix(&[Some("Song - www.example.com"), Some("Band")]).as_deref(), Some("www.example.com"));
        assert_eq!(detect_site_suffix(&[Some("Song"), Some("Band"), None]), None);
    }

    #[test]
    fn cleans_fields() {
        let suffixes = vec!["MassTamilan".to_string()];
        assert_eq!(clean_field("Jailer 2 - MassTamilan", &suffixes), "Jailer 2");
        assert_eq!(clean_field("  Jailer 2  -  masstamilan ", &suffixes), "Jailer 2");
        assert_eq!(
            clean_field("Anirudh Ravichander, M.S Krsna, Super Subu - MassTamilan", &suffixes),
            "Anirudh Ravichander, M.S Krsna, Super Subu"
        );
        assert_eq!(clean_field("Song (www.example.com)", &[]), "Song");
        assert_eq!(clean_field("Song - Site - example.in", &["Site".to_string()]), "Song");
        // Legitimate dashes stay.
        assert_eq!(clean_field("Intro - Live", &suffixes), "Intro - Live");
        assert_eq!(clean_field("Jay-Z", &suffixes), "Jay-Z");
        // Never empties a field.
        assert_eq!(clean_field("MassTamilan", &suffixes), "MassTamilan");
    }

    #[test]
    fn splits_comma_lists() {
        let c = split_artists("Anirudh Ravichander, Shankar Mahadevan, Vignesh Shivan", &[]);
        assert_eq!(
            names(&c),
            [("Anirudh Ravichander", "primary"), ("Shankar Mahadevan", "primary"), ("Vignesh Shivan", "primary")]
        );
        assert_eq!(split_artists("A; B", &[]).len(), 2);
        assert_eq!(names(&split_artists("A, B & C", &[])), [("A", "primary"), ("B", "primary"), ("C", "primary")]);
        assert_eq!(split_artists("M.S Krsna", &[]).len(), 1);
    }

    #[test]
    fn splits_featured_artists() {
        assert_eq!(names(&split_artists("A feat. B", &[])), [("A", "primary"), ("B", "featured")]);
        assert_eq!(names(&split_artists("A Ft. B, C", &[])), [("A", "primary"), ("B", "featured"), ("C", "featured")]);
        assert_eq!(names(&split_artists("A (feat. B)", &[])), [("A", "primary"), ("B", "featured")]);
        assert_eq!(names(&split_artists("A featuring B & C", &[])), [("A", "primary"), ("B & C", "featured")]);
        assert_eq!(names(&split_artists("A [with B]", &[])), [("A", "primary"), ("B", "featured")]);
    }

    #[test]
    fn ampersand_needs_known_halves() {
        assert_eq!(split_artists("Simon & Garfunkel", &[]).len(), 1);
        let known = vec![norm("Simon"), norm("Garfunkel")];
        assert_eq!(split_artists("Simon & Garfunkel", &known).len(), 2);
        assert_eq!(split_artists("Above & Beyond", &[norm("Above")]).len(), 1);
    }

    #[test]
    fn dedupes_and_tidies() {
        assert_eq!(names(&split_artists("  A ,a,  B  ", &[])), [("A", "primary"), ("B", "primary")]);
        assert!(split_artists("", &[]).is_empty());
    }

    #[test]
    fn non_ascii_credits() {
        assert_eq!(split_artists("அனிருத், ஷங்கர் மகாதேவன்", &[]).len(), 2);
        assert_eq!(names(&split_artists("Ä feat. Ö", &[])), [("Ä", "primary"), ("Ö", "featured")]);
    }
}
