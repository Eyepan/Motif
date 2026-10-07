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

#ifdef __cplusplus
}
#endif

#endif
