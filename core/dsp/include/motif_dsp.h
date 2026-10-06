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
void motif_analyzer_free(MotifAnalyzer *analyzer);

// Gains for outgoing (a) and incoming (b) decks at fade position t in [0, 1].
int32_t motif_crossfade_gains(float t, uint32_t curve, float *out_a, float *out_b);

#ifdef __cplusplus
}
#endif

#endif
