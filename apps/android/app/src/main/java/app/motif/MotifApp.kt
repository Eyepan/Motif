package app.motif

import android.app.Application
import android.content.Context
import app.motif.data.LibraryStore
import app.motif.data.Track
import app.motif.dsp.MotifDsp
import app.motif.importer.Importer
import app.motif.playback.DjEngine
import app.motif.playback.PlaybackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** App-wide objects. Built lazily so a cold start only pays for what the first screen needs. */
class MotifApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences("motif", Context.MODE_PRIVATE) }

    val library: LibraryStore by lazy {
        LibraryStore(this).also {
            scope.launch {
                it.load()
                if (!prefs.getBoolean(KEY_GRIDS_BACKFILLED, false) && MotifDsp.available) {
                    importer.backfillBeatGrids(it.tracks.value) { prefs.edit().putBoolean(KEY_GRIDS_BACKFILLED, true).apply() }
                }
            }
        }
    }

    private val _analyzeOnImport by lazy { MutableStateFlow(prefs.getBoolean(KEY_ANALYZE, true)) }
    val analyzeOnImport: StateFlow<Boolean> get() = _analyzeOnImport.asStateFlow()

    fun setAnalyzeOnImport(on: Boolean) {
        _analyzeOnImport.value = on
        prefs.edit().putBoolean(KEY_ANALYZE, on).apply()
    }

    val importer: Importer by lazy { Importer(this, library, scope) { _analyzeOnImport.value } }

    val playback: PlaybackEngine by lazy {
        PlaybackEngine(this, library, scope, prefs.getBoolean(KEY_MIX, false)) { on ->
            prefs.edit().putBoolean(KEY_MIX, on).apply()
        }
    }

    private var djStarted = false

    /** The DJ Mix decks. Starting a deck pauses regular playback, and the other way round. */
    val dj: DjEngine by lazy {
        djStarted = true
        DjEngine(this, library, scope) { playback.pause() }
    }

    fun play(tracks: List<Track>, startAt: Int) {
        if (djStarted) dj.pauseAll()
        playback.play(tracks, startAt)
    }

    fun delete(track: Track) {
        playback.remove(track)
        if (djStarted) dj.remove(track)
        scope.launch { library.delete(track) }
    }

    private companion object {
        const val KEY_ANALYZE = "analyze_on_import"
        const val KEY_MIX = "mix_into_next"
        const val KEY_GRIDS_BACKFILLED = "beat_grids_backfilled"
    }
}
