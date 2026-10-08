//! Reads .zip archives of music (an album download) one entry at a time, so
//! an app can import each track without unpacking the whole archive first.
//! iOS has no public unzip API, so the Apple apps use this; Android has
//! `java.util.zip`.
//!
//! Entry names are sanitized: absolute paths, `..` components, directories,
//! macOS resource forks (`__MACOSX/`, `._*`) and hidden files never appear.
//! Extraction writes only to the path the caller names and never more bytes
//! than the caller allows, so a hostile archive can't escape or fill the disk.

use std::fs::File;
use std::io::{self, Read, Write};
use std::path::{Component, Path, PathBuf};

pub struct Entry {
    /// Index in the archive, for [`Archive::extract`].
    index: usize,
    /// Relative path inside the archive, '/'-separated.
    pub name: String,
    /// Uncompressed size as recorded in the archive.
    pub size: u64,
}

pub struct Archive {
    zip: zip::ZipArchive<File>,
    entries: Vec<Entry>,
}

impl Archive {
    pub fn open(path: &Path) -> io::Result<Archive> {
        let mut zip = zip::ZipArchive::new(File::open(path)?).map_err(io::Error::other)?;
        let mut entries = Vec::new();
        for index in 0..zip.len() {
            let Ok(file) = zip.by_index_raw(index) else { continue };
            if !file.is_file() || file.encrypted() {
                continue;
            }
            let Some(name) = file.enclosed_name().as_deref().and_then(visible_name) else { continue };
            entries.push(Entry { index, name, size: file.size() });
        }
        Ok(Archive { zip, entries })
    }

    /// Files in the archive, in archive order.
    pub fn entries(&self) -> &[Entry] {
        &self.entries
    }

    /// Writes entry `i` of [`Archive::entries`] to `dest` (created or
    /// truncated). Fails, removing `dest`, if it holds more than `max_bytes`,
    /// fails its checksum, or is compressed with a method Motif doesn't read.
    pub fn extract(&mut self, i: usize, dest: &Path, max_bytes: u64) -> io::Result<u64> {
        let entry = self.entries.get(i).ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no such entry"))?;
        let result = (|| {
            let file = self.zip.by_index(entry.index).map_err(io::Error::other)?;
            let mut out = File::create(dest)?;
            // One byte over the limit proves the entry is too large.
            let written = io::copy(&mut file.take(max_bytes.saturating_add(1)), &mut out)?;
            if written > max_bytes {
                return Err(io::Error::new(io::ErrorKind::InvalidData, "entry is larger than allowed"));
            }
            out.flush()?;
            Ok(written)
        })();
        if result.is_err() {
            let _ = std::fs::remove_file(dest);
        }
        result
    }
}

/// The entry's path as '/'-separated text, or `None` for hidden files and
/// macOS metadata. `path` is already free of `..` and roots.
fn visible_name(path: &Path) -> Option<String> {
    let parts: Vec<&str> = path
        .components()
        .map(|c| match c {
            Component::Normal(s) => s.to_str(),
            _ => None,
        })
        .collect::<Option<_>>()?;
    let hidden = parts.iter().any(|p| p.starts_with('.') || *p == "__MACOSX");
    (!parts.is_empty() && !hidden).then(|| parts.join("/"))
}

/// Joins a sanitized entry name onto `dir`. Separate from extraction so
/// callers that build their own paths get the same guarantee.
pub fn destination(dir: &Path, name: &str) -> Option<PathBuf> {
    let relative = Path::new(name);
    let safe = relative.components().all(|c| matches!(c, Component::Normal(_)));
    (safe && !name.is_empty()).then(|| dir.join(relative))
}

#[cfg(test)]
mod tests {
    use super::*;
    use zip::write::SimpleFileOptions;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("motif-archive-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn build(path: &Path, files: &[(&str, &[u8])]) {
        let mut zip = zip::ZipWriter::new(File::create(path).unwrap());
        zip.add_directory("Album/", SimpleFileOptions::default()).unwrap();
        for (name, data) in files {
            zip.start_file(*name, SimpleFileOptions::default()).unwrap();
            zip.write_all(data).unwrap();
        }
        zip.finish().unwrap();
    }

    #[test]
    fn lists_and_extracts_safely() {
        let dir = scratch("list");
        let path = dir.join("album.zip");
        let song = vec![7u8; 10_000];
        build(
            &path,
            &[
                ("Album/01 Song.flac", &song),
                ("Album/cover.jpg", b"jpg"),
                ("__MACOSX/Album/._01 Song.flac", b"fork"),
                ("Album/.DS_Store", b"junk"),
                ("../../escape.mp3", b"evil"),
                ("/abs/root.mp3", b"evil"),
            ],
        );
        let mut archive = Archive::open(&path).unwrap();
        let names: Vec<&str> = archive.entries().iter().map(|e| e.name.as_str()).collect();
        // zip drops `..` and roots from enclosed names; the escape attempts are not listed as written.
        assert!(names.contains(&"Album/01 Song.flac") && names.contains(&"Album/cover.jpg"));
        assert!(names.iter().all(|n| !n.contains("..") && !n.starts_with('/') && !n.contains("MACOSX") && !n.contains(".DS")));
        assert!(!dir.join("../escape.mp3").exists());

        let i = names.iter().position(|n| *n == "Album/01 Song.flac").unwrap();
        assert_eq!(archive.entries()[i].size, 10_000);
        let out = dir.join("song.flac");
        assert_eq!(archive.extract(i, &out, 1 << 20).unwrap(), 10_000);
        assert_eq!(std::fs::read(&out).unwrap(), song);

        // Over the limit: fails and leaves nothing behind.
        assert!(archive.extract(i, &out, 9_999).is_err());
        assert!(!out.exists());
        assert!(archive.extract(99, &out, 1 << 20).is_err());
        assert!(Archive::open(&dir.join("missing.zip")).is_err());
        std::fs::write(dir.join("bad.zip"), b"not a zip").unwrap();
        assert!(Archive::open(&dir.join("bad.zip")).is_err());
    }

    #[test]
    fn destinations_stay_inside() {
        let dir = Path::new("/tmp/x");
        assert_eq!(destination(dir, "Album/a.flac"), Some(dir.join("Album/a.flac")));
        assert_eq!(destination(dir, "../a.flac"), None);
        assert_eq!(destination(dir, "/a.flac"), None);
        assert_eq!(destination(dir, ""), None);
    }
}
