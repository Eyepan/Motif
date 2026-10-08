package app.motif.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.motif.MotifApp
import app.motif.data.formatTime
import app.motif.importer.Downloads
import app.motif.sources.Http
import app.motif.sources.MusicSource
import app.motif.sources.SourceResult
import app.motif.sources.isIn
import app.motif.sources.licenseLabel
import app.motif.ui.theme.Motif
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Discover: search open-licensed catalogs, listen before downloading, and
 * download with one tap straight into the library (tags, art and analysis
 * included). Lossless only: Jamendo's FLAC, and Internet Archive items with
 * FLAC or WAV files.
 */
@Composable
fun DiscoverScreen(app: MotifApp, modifier: Modifier = Modifier) {
    val sources = app.sources
    // 0 searches every catalog at once; i + 1 is sources[i].
    var scopeIndex by rememberSaveable { mutableStateOf(0) }
    val only = sources.getOrNull(scopeIndex - 1)
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    /** Catalog name to what went wrong, for the catalogs that failed this search. */
    var failures by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var searchedFor by remember { mutableStateOf<String?>(null) }
    val jamendoId by app.jamendoClientId.collectAsStateWithLifecycle()
    val tracks by app.library.tracks.collectAsStateWithLifecycle()
    val states by app.downloads.states.collectAsStateWithLifecycle()
    val previewing by app.previewer.playing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val uri = LocalUriHandler.current

    DisposableEffect(Unit) { onDispose { app.previewer.stop() } }

    fun search() {
        val q = query.trim()
        val targets = (only?.let(::listOf) ?: sources).filter { it.isConfigured }
        if (q.isEmpty() || targets.isEmpty()) return
        focus.clearFocus()
        scope.launch {
            searching = true
            val outcome = searchAll(targets, q)
            hits = outcome.hits
            failures = outcome.failures
            searchedFor = q
            searching = false
        }
    }

    LaunchedEffect(scopeIndex, jamendoId) {
        hits = emptyList()
        failures = emptyMap()
        searchedFor = null
        app.previewer.stop()
        if (query.isNotBlank()) search()
    }

    Column(modifier.statusBarsPadding()) {
        Text("Discover", fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 12.dp))
        val labels = listOf("All") + sources.map { it.shortName }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = i == scopeIndex,
                    onClick = { scopeIndex = i },
                    shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                    icon = {},
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Motif.raised,
                        activeContentColor = Motif.accent,
                        inactiveContainerColor = Motif.ground,
                        inactiveContentColor = Motif.secondary,
                    ),
                ) { Text(label, maxLines = 1, fontSize = 13.sp) }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Artist, album or genre") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Motif.raised,
                focusedContainerColor = Motif.raised,
                unfocusedBorderColor = Motif.raised,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        )

        val catalogNames = (only?.let(::listOf) ?: sources.filter { it.isConfigured }).joinToString(", ") { it.displayName }
        when {
            only != null && !only.isConfigured -> JamendoSetup(onSave = app::setJamendoClientId)
            searching -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Motif.accent)
            }
            searchedFor == null -> Hint(
                "Search $catalogNames for music you're free to keep. Downloads go straight into your library, " +
                    "with tags, cover art, BPM and key.",
            )
            hits.isEmpty() -> NoResults(
                query = searchedFor!!,
                catalogs = catalogNames,
                failures = failures,
                onStore = { uri.openUri(it) },
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                if (failures.isNotEmpty()) item { FailureNote(failures) }
                items(hits, key = { "${it.source.id}:${it.result.id}" }) { hit ->
                    val (source, result) = hit
                    val key = app.downloads.key(source, result)
                    ResultRow(
                        result = result,
                        sourceName = if (only == null) source.displayName else null,
                        state = states[key],
                        inLibrary = remember(tracks, result) { result.isIn(tracks, source.id) },
                        previewing = previewing == key,
                        onPreview = {
                            if (previewing == key) {
                                app.previewer.pause()
                            } else {
                                scope.launch {
                                    runCatching { source.previewUrl(result) }.getOrNull()?.let { app.previewer.play(key, it) }
                                }
                            }
                        },
                        onDownload = { app.downloads.start(source, result) },
                        onCancel = { app.downloads.cancel(source, result) },
                    )
                }
            }
        }
    }
}

/** One search result and the catalog it came from. */
private data class Hit(val source: MusicSource, val result: SourceResult)

private class Outcome(val hits: List<Hit>, val failures: Map<String, String>)

/** Searches [targets] side by side; one catalog failing doesn't hide the others' results. */
private suspend fun searchAll(targets: List<MusicSource>, query: String): Outcome = coroutineScope {
    val answers = targets.map { source ->
        async {
            try {
                source to Result.success(source.search(query))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                source to Result.failure<List<SourceResult>>(e)
            }
        }
    }.awaitAll()
    val lists = answers.mapNotNull { (source, r) -> r.getOrNull()?.map { Hit(source, it) } }
    Outcome(
        // Interleave so each catalog's best matches come first.
        hits = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } },
        failures = answers.mapNotNull { (source, r) ->
            r.exceptionOrNull()?.let { source.displayName to (it.message ?: "Search failed") }
        }.toMap(),
    )
}

/** Nothing found: says so plainly, why if a catalog failed, and where the music might be sold. */
@Composable
private fun NoResults(query: String, catalogs: String, failures: Map<String, String>, onStore: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("No results for “$query”", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (failures.isNotEmpty()) FailureNote(failures, Modifier)
        Text(
            "Nothing on $catalogs matches. These catalogs only carry music the artists or archives chose to share freely, " +
                "so most label releases aren't here. Check the spelling, or look for it in a store:",
            color = Motif.secondary, fontSize = 14.sp,
        )
        storeSearches(query).forEach { (name, url) ->
            Text(name, color = Motif.accent, fontSize = 15.sp, modifier = Modifier.clickable { onStore(url) }.padding(vertical = 4.dp))
        }
        Text(
            "Bought files can be added from Add Music, and Motif keeps them bit for bit.",
            color = Motif.secondary, fontSize = 13.sp,
        )
    }
}

@Composable
private fun FailureNote(failures: Map<String, String>, modifier: Modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        failures.forEach { (name, message) ->
            Text("$name couldn't be searched: $message", color = Color(0xFFFF6B6B), fontSize = 13.sp)
        }
    }
}

/** Stores that sell lossless downloads, searched for [query]. */
private fun storeSearches(query: String): List<Pair<String, String>> {
    val q = java.net.URLEncoder.encode(query, "UTF-8")
    return listOf(
        "Search Qobuz (FLAC)" to "https://www.qobuz.com/search?q=$q",
        "Search Bandcamp (FLAC)" to "https://bandcamp.com/search?q=$q",
    )
}

@Composable
private fun ResultRow(
    result: SourceResult,
    /** Shown when results from several catalogs are mixed. */
    sourceName: String?,
    state: Downloads.State?,
    inLibrary: Boolean,
    previewing: Boolean,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val uri = LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.clickable(onClick = onPreview)) {
            RemoteArt(result.imageUrl, seed = result.album ?: result.title, letter = result.title.take(1).uppercase())
            Icon(
                if (previewing) Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,
                contentDescription = if (previewing) "Stop preview" else "Preview",
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.align(Alignment.Center).size(24.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(result.title, fontSize = 16.sp, color = if (previewing) Motif.accent else Motif.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                result.quality?.let { FormatBadge(it) }
                sourceName?.let { FormatBadge(it.uppercase()) }
                Text(
                    listOfNotNull(result.subtitle.ifEmpty { null }, result.durationSeconds?.let { formatTime(it.toDouble()) }).joinToString(" · "),
                    fontSize = 13.sp, color = Motif.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val license = licenseLabel(result.licenseUrl)
            val status = when (state) {
                is Downloads.State.Waiting -> "Waiting…"
                is Downloads.State.Downloading ->
                    if (state.files > 1) "Downloading ${state.file + 1} of ${state.files} · ${(state.fraction * 100).toInt()}%"
                    else "Downloading · ${(state.fraction * 100).toInt()}%"
                is Downloads.State.Failed -> state.message
                is Downloads.State.Done -> if (state.files == 1) "Added to your library" else "Added ${state.files} tracks to your library"
                null -> if (!result.downloadable) "Streaming only" else null
            }
            if (status != null || license != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    status?.let {
                        Text(it, fontSize = 12.sp, color = if (state is Downloads.State.Failed) Color(0xFFFF6B6B) else Motif.secondary, maxLines = 1)
                    }
                    if (license != null && result.licenseUrl != null) {
                        Text(license, fontSize = 12.sp, color = Motif.secondary, modifier = Modifier.clickable { uri.openUri(result.licenseUrl) })
                    }
                }
            }
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                state is Downloads.State.Waiting || state is Downloads.State.Downloading -> {
                    val fraction = (state as? Downloads.State.Downloading)?.fraction ?: 0f
                    CircularProgressIndicator(
                        progress = { fraction },
                        color = Motif.accent,
                        trackColor = Motif.hairline,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(28.dp),
                    )
                    IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "Cancel download", tint = Motif.secondary, modifier = Modifier.size(16.dp)) }
                }
                state is Downloads.State.Done || inLibrary -> Icon(Icons.Filled.CheckCircle, "In your library", tint = Motif.done)
                !result.downloadable -> Unit
                else -> IconButton(onClick = onDownload) {
                    Icon(
                        if (state is Downloads.State.Failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Download,
                        if (state is Downloads.State.Failed) "Retry download" else "Download",
                        tint = Motif.accent,
                    )
                }
            }
        }
    }
}

/** Jamendo needs a free client id; until the build carries one, the user can paste theirs. */
@Composable
private fun JamendoSetup(onSave: (String) -> Unit) {
    var id by rememberSaveable { mutableStateOf("") }
    val uri = LocalUriHandler.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect Jamendo", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Jamendo's catalog needs a free client id. Create an app at developer.jamendo.com, then paste its client id here. " +
                "Internet Archive works without one.",
            color = Motif.secondary, fontSize = 14.sp,
        )
        Text(
            "Open developer.jamendo.com",
            color = Motif.accent, fontSize = 14.sp,
            modifier = Modifier.clickable { uri.openUri("https://developer.jamendo.com/v3.0") },
        )
        OutlinedTextField(
            value = id,
            onValueChange = { id = it },
            placeholder = { Text("Client id") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Motif.raised, focusedContainerColor = Motif.raised),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSave(id) },
            enabled = id.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Motif.accent, contentColor = Motif.onAccent),
        ) { Text("Save") }
    }
}

@Composable
private fun Hint(text: String, color: Color = Motif.secondary) {
    Text(text, color = color, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

/** Catalog art from the network, decoded small and kept in a little memory cache. */
@Composable
private fun RemoteArt(url: String?, seed: String, letter: String, size: Dp = 48.dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(url?.let { Thumbnails.cache.get("$it@$px") }, url, px) {
        if (url == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { Thumbnails.load(url, px) }.getOrNull() }
    }
    val image = bitmap
    if (image != null) {
        Image(
            image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Motif.raised),
        )
    } else {
        ArtTile(seed, letter, size = size)
    }
}

private object Thumbnails {
    val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    suspend fun load(url: String, px: Int): Bitmap? {
        val bytes = Http.bytes(url, maxBytes = 4 * 1024 * 1024) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put("$url@$px", bitmap)
        return bitmap
    }
}
