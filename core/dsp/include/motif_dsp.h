// C interface to Motif's DSP core (core/dsp/src/lib.rs). Keep in sync.
#ifndef MOTIF_DSP_H
#define MOTIF_DSP_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MotifAnalyzer MotifAnalyzer;

typedef struct {
    float peak_db;  // sample peak, dBFS (-inf for silence)
    float rms_db;   // RMS level, dBFS (-inf for silence)
    float bpm;      // estimated tempo, 0 if none found
    uint8_t camelot_number;  // Camelot key 1...12, 0 if none found
    uint8_t camelot_minor;   // 1 = minor ("A"), 0 = major ("B")
    float first_downbeat;    // seconds to the beat grid's first downbeat, -1 if none
} MotifAnalysis;

typedef enum {
    MOTIF_CURVE_EQUAL_POWER = 0,
    MOTIF_CURVE_LINEAR = 1,
    MOTIF_CURVE_CUT = 2,
} MotifCrossfadeCurve;

// Streaming analysis: push interleaved float samples in chunks, then finish.
MotifAnalyzer *motif_analyzer_new(uint32_t sample_rate, uint32_t channels);
void motif_analyzer_push(MotifAnalyzer *analyzer, const float *samples, size_t sample_count);
int32_t motif_analyzer_finish(const MotifAnalyzer *analyzer, MotifAnalysis *out);
// Waveform overview: `count` loudness values in 0...1 across the whole track.
int32_t motif_analyzer_overview(const MotifAnalyzer *analyzer, float *out, size_t count);
void motif_analyzer_free(MotifAnalyzer *analyzer);

// Gains for outgoing (a) and incoming (b) decks at fade position t in [0, 1].
int32_t motif_crossfade_gains(float t, uint32_t curve, float *out_a, float *out_b);

// Tag cleanup and artist credits (core/dsp/src/meta.rs). Strings are UTF-8;
// every returned string is the caller's and is freed with motif_string_free.
// Lists are `count` pointers, each NULL for a missing value.
uint32_t motif_meta_cleaner_version(void);
void motif_string_free(char *text);
char *motif_meta_norm(const char *text);
// Title, artist, album, album artist in; the site name appended to them, or NULL.
char *motif_meta_detect_site_suffix(const char *const *fields, size_t count);
char *motif_meta_clean_field(const char *text, const char *const *suffixes, size_t count);
// One credited artist per line, "role\tname", role "primary" or "featured".
// `known` holds names already passed through motif_meta_norm.
char *motif_meta_split_artists(const char *credit, const char *const *known, size_t count);

// Import dedupe (core/dsp/src/dedupe.rs). Strings as above; artist may be NULL,
// unknown numbers are 0. format is the library's format name ("flac", "mp3"...).
typedef struct {
    const char *title;
    const char *artist;
    int64_t duration_ms;
    const char *format;
    uint32_t sample_rate;
    uint32_t bit_depth;
    uint32_t bitrate_kbps;  // ranks lossy copies only
} MotifDedupeTrack;

typedef enum {
    MOTIF_DEDUPE_NEW = 0,        // no copy yet: import it
    MOTIF_DEDUPE_DUPLICATE = 1,  // existing[*out_index] is as good or better: skip
    MOTIF_DEDUPE_UPGRADE = 2,    // existing[*out_index] is worse: replace its file
} MotifDedupeVerdict;

// Lead artist and title, normalized; equal keys are the same song. NULL for a NULL title.
char *motif_dedupe_key(const char *title, const char *artist);
// 1 when a is the better copy, -1 when b is, 0 when equal.
int32_t motif_dedupe_compare_quality(const MotifDedupeTrack *a, const MotifDedupeTrack *b);
// A MotifDedupeVerdict for incoming against count existing tracks, or -1 on a NULL argument.
int32_t motif_dedupe_resolve(const MotifDedupeTrack *incoming, const MotifDedupeTrack *existing,
                             size_t count, size_t *out_index);

// Zip archives (core/dsp/src/archive.rs), read one entry at a time. Listed
// names are relative, '/'-separated and safe to join onto a folder; hidden
// files, __MACOSX and directories are left out.
typedef struct MotifZip MotifZip;
MotifZip *motif_zip_open(const char *path);  // NULL if not a readable zip
size_t motif_zip_count(const MotifZip *zip);
char *motif_zip_name(const MotifZip *zip, size_t index);  // free with motif_string_free
uint64_t motif_zip_size(const MotifZip *zip, size_t index);
// Writes the entry to dest. -1 on failure or when it holds more than max_bytes (dest is removed).
int32_t motif_zip_extract(MotifZip *zip, size_t index, const char *dest, uint64_t max_bytes);
void motif_zip_free(MotifZip *zip);

// Beat-aligned mixing (core/dsp/src/mix.rs). Positions and lengths in seconds.
typedef struct {
    double bpm;             // 0 if unknown
    double first_downbeat;  // negative if the track has no beat grid
    double duration;
} MotifTiming;

typedef struct {
    double out_start;  // outgoing position where the blend starts
    double in_start;   // incoming position at that moment
    double length;     // blend length, outgoing seconds
    double rate;       // incoming playback speed
    double lock;       // beat both lock to, real seconds; 0 = not beat aligned
} MotifMixPlan;

typedef enum {
    MOTIF_MIX_SPEED = 0,  // set the incoming speed to value
    MOTIF_MIX_SEEK = 1,   // move the incoming track to value seconds
} MotifMixAction;

typedef struct {
    double progress;  // 0...1 through the blend
    float gain_out;
    float gain_in;
    uint32_t action;  // MotifMixAction
    double value;
} MotifMixFollow;

// Plans a blend from the end of outgoing into incoming.
int32_t motif_mix_plan(const MotifTiming *outgoing, const MotifTiming *incoming, MotifMixPlan *out);
// Call a few times a second during a blend with both tracks' positions.
int32_t motif_mix_follow(const MotifMixPlan *plan, double out_pos, double in_pos, MotifMixFollow *out);
// Speed and position putting the slave deck on the master's tempo and beat.
// snap != 0 jumps onto the beat (SYNC pressed); 0 keeps the position and nudges
// the speed (staying locked). Returns -1 without grids or if tempos are too far apart.
int32_t motif_deck_sync(const MotifTiming *master, double master_pos, double master_speed,
                        const MotifTiming *slave, double slave_pos, int32_t snap,
                        double *out_speed, double *out_pos);

// Beat-aligned loop `beats` long (1/4...32) at position: starts on the beat at
// or just before it. Returns -1 without a grid.
int32_t motif_loop_at(const MotifTiming *timing, double position, double beats,
                      double *out_start, double *out_end);
// First downbeat at or after position. Returns -1 without a grid.
int32_t motif_next_downbeat(const MotifTiming *timing, double position, double *out);

// Deck EQ and filter (core/dsp/src/fx.rs). Knobs are -1...1 with 0 flat:
// EQ cuts to -26 dB on the left and boosts +6 dB on the right; the filter is a
// low-pass left of centre and a high-pass right of it.
typedef struct {
    double low;
    double mid;
    double high;
    double filter;
} MotifFxKnobs;

typedef enum {
    MOTIF_FX_LOW_SHELF = 0,
    MOTIF_FX_PEAK = 1,
    MOTIF_FX_HIGH_SHELF = 2,
    MOTIF_FX_LOW_PASS = 3,
    MOTIF_FX_HIGH_PASS = 4,
} MotifFxKind;

typedef struct {
    uint32_t kind;  // MotifFxKind
    double freq;    // Hz
    double gain_db; // shelves and peak; 0 for the pass filters
    double q;
    uint8_t bypass; // 1 when the band leaves the sound alone
} MotifFxBand;

// The filters for knobs: low shelf, mid peak, high shelf, filter. For
// platforms with their own EQ unit. Returns how many were written, or -1.
int32_t motif_fx_bands(const MotifFxKnobs *knobs, MotifFxBand *out, size_t count);

// The same filters run on interleaved float samples. Not thread safe: set and
// process from one thread.
typedef struct MotifDeckFx MotifDeckFx;
MotifDeckFx *motif_fx_new(uint32_t sample_rate, uint32_t channels);
void motif_fx_set(MotifDeckFx *fx, const MotifFxKnobs *knobs);
void motif_fx_reset(MotifDeckFx *fx);  // after a seek
void motif_fx_process(MotifDeckFx *fx, float *samples, size_t sample_count);
void motif_fx_free(MotifDeckFx *fx);

#ifdef __cplusplus
}
#endif

#endif
