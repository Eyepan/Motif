package app.motif.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.motif.data.LibraryFilter
import app.motif.data.Track
import app.motif.ui.theme.Motif

/** Search with DJ filters: `bpm:124`, `bpm:120-126`, `key:8A`. */
@Composable
fun SearchScreen(tracks: List<Track>, currentId: String?, onPlay: (List<Track>, Int) -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    val filter = remember(query) { LibraryFilter(query) }
    val results = remember(tracks, filter) { if (filter.isEmpty) emptyList() else tracks.filter(filter::matches) }

    Column(modifier.statusBarsPadding()) {
        Text("Search", fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Songs, artists, bpm:124, key:8A") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Motif.raised,
                focusedContainerColor = Motif.raised,
                unfocusedBorderColor = Motif.raised,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        if (filter.isEmpty) {
            Text(
                "Filter by tempo with bpm:124 or bpm:120-126, and by Camelot key with key:8A.",
                color = Motif.secondary,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(results, key = { _, t -> t.id }) { index, track ->
                    TrackRow(track, track.id == currentId, onClick = { onPlay(results, index) })
                }
            }
        }
    }
}
