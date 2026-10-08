package app.motif.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.motif.MotifApp
import app.motif.data.Crate
import app.motif.data.Track
import app.motif.data.tracksByKey
import app.motif.ui.theme.Motif
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the crate name dialog is for. */
private sealed interface CrateNaming {
    /** A new crate, holding these tracks (maybe none). */
    data class Create(val tracks: List<Track>) : CrateNaming
    data class Rename(val crate: Crate) : CrateNaming
}

/**
 * Runs crate edits off the UI and reports failures (a file that can't be read
 * for its content hash, say) as a toast.
 */
@Composable
private fun rememberCrateEdits(app: MotifApp): (suspend () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(app) {
        val run: (suspend () -> Unit) -> Unit = { edit ->
            scope.launch {
                runCatching { edit() }.onFailure {
                    Toast.makeText(context, it.message ?: "Couldn't change the crate", Toast.LENGTH_SHORT).show()
                }
            }
        }
        run
    }
}

/** The Crates tab: every crate, or one crate's songs when [openId] is set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CratesScreen(
    app: MotifApp,
    tracks: List<Track>,
    current: Track?,
    openId: String?,
    onOpen: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val crates by app.crates.crates.collectAsStateWithLifecycle()
    val byKey = remember(tracks) { tracksByKey(tracks) }
    val edit = rememberCrateEdits(app)
    var naming by remember { mutableStateOf<CrateNaming?>(null) }
    val open = crates.firstOrNull { it.id == openId }

    if (open != null) {
        BackHandler { onOpen(null) }
        CrateDetail(
            crate = open,
            songs = open.trackKeys.mapNotNull(byKey::get),
            current = current,
            onBack = { onOpen(null) },
            onPlay = app::play,
            onRemove = { t -> edit { app.crates.remove(listOfNotNull(t.contentHash), open.id) } },
            onRename = { naming = CrateNaming.Rename(open) },
            onDelete = { onOpen(null); edit { app.crates.delete(open.id) } },
            modifier = modifier,
        )
    } else {
        Column(modifier) {
            TopAppBar(
                title = { Text("Crates", fontWeight = FontWeight.Bold) },
                actions = { IconButton(onClick = { naming = CrateNaming.Create(emptyList()) }) { Icon(Icons.Filled.Add, "New crate") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Motif.ground),
            )
            if (crates.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("No crates yet", fontSize = 22.sp, color = Motif.text)
                        Text(
                            "Group tracks into sets for a gig or a mood. Long-press a song in your library to add it to a crate.",
                            color = Motif.secondary,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = { naming = CrateNaming.Create(emptyList()) }) { Text("New crate") }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(crates, key = { it.id }) { crate ->
                        CrateListItem(
                            crate = crate,
                            byKey = byKey,
                            onOpen = { onOpen(crate.id) },
                            onPlay = { app.play(crate.trackKeys.mapNotNull(byKey::get), 0) },
                            onRename = { naming = CrateNaming.Rename(crate) },
                            onDelete = { edit { app.crates.delete(crate.id) } },
                        )
                    }
                }
            }
        }
    }

    naming?.let { n ->
        CrateNameDialog(
            initial = (n as? CrateNaming.Rename)?.crate?.name ?: "",
            isRename = n is CrateNaming.Rename,
            detail = (n as? CrateNaming.Create)?.tracks?.let(::withTracksText),
            onDismiss = { naming = null },
            onSave = { name ->
                naming = null
                edit {
                    when (n) {
                        is CrateNaming.Create -> app.crates.create(name, n.tracks)
                        is CrateNaming.Rename -> app.crates.rename(n.crate.id, name)
                    }
                }
            },
        )
    }
}

/** A crate row: art, name and summary; tap to open, ⋮ for actions. Also used in the Library's Crates segment. */
@Composable
fun CrateListItem(
    crate: Crate,
    byKey: Map<String, Track>,
    onOpen: () -> Unit,
    onPlay: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val songs = crate.trackKeys.mapNotNull(byKey::get)
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(crate.name) },
        supportingContent = { Text(crateSummary(songs, crate.trackKeys.size - songs.size)) },
        leadingContent = {
            ArtTile(crate.name, crate.name.firstOrNull()?.uppercase() ?: "♪", size = 56.dp, radius = 8.dp, artIds = songs.map { it.id })
        },
        trailingContent = {
            if (onPlay != null || onRename != null || onDelete != null) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Crate actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        onPlay?.let { play ->
                            DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; play() }, enabled = songs.isNotEmpty())
                        }
                        onRename?.let { rename -> DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename() }) }
                        onDelete?.let { delete -> DropdownMenuItem(text = { Text("Delete crate") }, onClick = { menu = false; delete() }) }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Motif.ground),
        modifier = Modifier.padding(horizontal = 4.dp).clickable(onClick = onOpen),
    )
}

private enum class CrateOrder(val label: String) { Added("Added"), Bpm("BPM"), Key("Key") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrateDetail(
    crate: Crate,
    songs: List<Track>,
    current: Track?,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onRemove: (Track) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var order by rememberSaveable { mutableStateOf(CrateOrder.Added) }
    var menu by remember { mutableStateOf(false) }
    val sorted = remember(songs, order) {
        when (order) {
            CrateOrder.Added -> songs
            CrateOrder.Bpm -> songs.sortedBy { it.bpm ?: Double.MAX_VALUE }
            CrateOrder.Key -> songs.sortedWith(compareBy<Track>({ camelotNumber(it.musicalKey) }, { it.musicalKey }))
        }
    }
    val missing = crate.trackKeys.size - songs.size
    Column(modifier) {
        TopAppBar(
            title = { Text(crate.name, fontWeight = FontWeight.Bold, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { onPlay(sorted, 0) }, enabled = sorted.isNotEmpty()) { Icon(Icons.Filled.PlayArrow, "Play crate") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Crate actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Delete crate") }, onClick = { menu = false; onDelete() })
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Motif.ground),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    CrateOrder.entries.forEachIndexed { i, o ->
                        SegmentedButton(
                            selected = order == o,
                            onClick = { order = o },
                            shape = SegmentedButtonDefaults.itemShape(i, CrateOrder.entries.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = Motif.raised,
                                activeContentColor = Motif.text,
                                inactiveContainerColor = Motif.ground,
                                inactiveContentColor = Motif.secondary,
                            ),
                            icon = {},
                        ) { Text(o.label, fontSize = 13.sp) }
                    }
                }
            }
            item { SectionHeader("Songs", crateSummary(sorted, missing)) }
            itemsIndexed(sorted, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track, track.id == current?.id,
                    onClick = { onPlay(sorted, index) },
                    mixWith = current,
                    onRemoveFromCrate = { onRemove(track) },
                )
            }
            if (crate.trackKeys.isEmpty()) {
                item {
                    Text(
                        "Empty crate. Long-press a song in your library and choose Add to crate.",
                        color = Motif.secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            if (missing > 0) {
                item {
                    Text(
                        if (missing == 1) "1 song was added on another device and isn't imported here."
                        else "$missing songs were added on another device and aren't imported here.",
                        color = Motif.secondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * "Add to crate" for [tracks]: every crate, then New crate. Picking one adds
 * the tracks and closes; New crate asks for a name first.
 */
@Composable
fun CratePicker(app: MotifApp, tracks: List<Track>, onDismiss: () -> Unit) {
    val crates by app.crates.crates.collectAsStateWithLifecycle()
    val edit = rememberCrateEdits(app)
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        CrateNameDialog(
            initial = "",
            isRename = false,
            detail = withTracksText(tracks),
            onDismiss = onDismiss,
            onSave = { name -> onDismiss(); edit { app.crates.create(name, tracks) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to crate") },
        text = {
            LazyColumn {
                items(crates, key = { it.id }) { crate ->
                    Text(
                        crate.name,
                        color = Motif.text,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDismiss(); edit { app.crates.add(tracks, crate.id) } }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { naming = true }) { Text("New crate…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Motif.surface,
    )
}

@Composable
private fun CrateNameDialog(
    initial: String,
    isRename: Boolean,
    detail: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isRename) "Rename crate" else "New crate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                detail?.let { Text(it, color = Motif.secondary) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Name") },
                    modifier = Modifier.testTag("crate-name"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(if (isRename) "Rename" else "Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Motif.surface,
    )
}

private fun withTracksText(tracks: List<Track>): String? = when (tracks.size) {
    0 -> null
    1 -> "With “${tracks[0].title}”."
    else -> "With ${tracks.size} songs."
}

/** "12 songs · 120–126 BPM", plus how many are on another device. */
fun crateSummary(tracks: List<Track>, missing: Int): String {
    val parts = mutableListOf("${tracks.size} ${if (tracks.size == 1) "song" else "songs"}")
    val bpms = tracks.mapNotNull { it.bpm?.roundToInt() }
    val low = bpms.minOrNull()
    val high = bpms.maxOrNull()
    if (low != null && high != null) parts += if (low == high) "$low BPM" else "$low–$high BPM"
    if (missing > 0) parts += "$missing on another device"
    return parts.joinToString(" · ")
}

/** Camelot wheel position, so keys sort 1A, 1B, 2A … 12B. */
private fun camelotNumber(key: String?): Int = key?.dropLast(1)?.toIntOrNull() ?: Int.MAX_VALUE
