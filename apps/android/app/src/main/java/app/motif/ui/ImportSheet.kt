package app.motif.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.motif.MotifApp
import app.motif.dsp.MotifDsp
import app.motif.importer.ImportJob
import app.motif.ui.theme.Motif

/** Add Music: pick files or a whole folder (phone storage, SD card, USB drive), or go to Discover; per-file progress. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(app: MotifApp, onDismiss: () -> Unit, onDiscover: () -> Unit) {
    val jobs by app.importer.jobs.collectAsStateWithLifecycle()
    val analyze by app.analyzeOnImport.collectAsStateWithLifecycle()
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { app.importer.importFiles(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(app.importer::importFolder) }
    val watched by app.folderImporter.folder.collectAsStateWithLifecycle()
    val pickWatched = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(app.folderImporter::watch) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Motif.surface) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Add Music", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    TextButton(onClick = onDismiss) { Text("Done", fontWeight = FontWeight.SemiBold) }
                }
            }
            item { GroupLabel("From") }
            item {
                Card {
                    SourceRow(Icons.Outlined.AudioFile, "Files", "FLAC, WAV, ALAC and more from this phone or the cloud") {
                        pickFiles.launch(arrayOf("audio/*"))
                    }
                    SourceRow(Icons.Outlined.Folder, "Folder", "A whole folder, including SD cards and USB drives") {
                        pickFolder.launch(null)
                    }
                    val folder = watched
                    if (folder == null) {
                        SourceRow(Icons.Outlined.Download, "Read from Downloads", "Pick a folder. Albums, folders and .zip files; best copy kept") {
                            pickWatched.launch(null)
                        }
                    } else {
                        SourceRow(Icons.Outlined.Download, "Reading ${folderName(folder)}", "Checked every time Motif opens. Tap to stop") {
                            app.folderImporter.stop()
                        }
                    }
                    SourceRow(Icons.Outlined.Explore, "Discover", "Search and download free music from Jamendo, the Internet Archive and Audius", onDiscover)
                }
            }

            if (jobs.isNotEmpty()) {
                val active = jobs.count { it.stage is ImportJob.Stage.Copying || it.stage is ImportJob.Stage.Analyzing }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GroupLabel("Importing · ${jobs.size - active} of ${jobs.size}", Modifier.weight(1f))
                        if (active == 0) TextButton(onClick = app.importer::clearFinished) { Text("Clear") }
                    }
                }
                items(jobs, key = { it.id }) { job -> Card { ImportJobRow(job) } }
            }

            item { GroupLabel("On import") }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row {
                            Text("Keep original files", Modifier.weight(1f))
                            Text("Always", color = Motif.secondary)
                        }
                        Text("No transcoding. Bit-perfect copies.", fontSize = 12.sp, color = Motif.secondary)
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = MotifDsp.available) { app.setAnalyzeOnImport(!analyze) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Analyze for mixing")
                            Text(
                                if (MotifDsp.available) "BPM, key and loudness. Runs on device." else "Not available in this build.",
                                fontSize = 12.sp, color = Motif.secondary,
                            )
                        }
                        Switch(
                            checked = analyze && MotifDsp.available,
                            onCheckedChange = app::setAnalyzeOnImport,
                            enabled = MotifDsp.available,
                            colors = SwitchDefaults.colors(checkedTrackColor = Motif.accent, checkedThumbColor = Motif.onAccent),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** "Download/Music" for a tree like primary:Download/Music. */
private fun folderName(tree: Uri): String =
    DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "your folder" }

@Composable
private fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), fontSize = 12.sp, color = Motif.secondary, letterSpacing = 0.6.sp, modifier = modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Motif.raised, RoundedCornerShape(12.dp))) { content() }
}

@Composable
private fun SourceRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).background(Motif.hairline, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Motif.accent, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(title, color = Motif.text)
            Text(detail, fontSize = 12.sp, color = Motif.secondary)
        }
    }
}

@Composable
private fun ImportJobRow(job: ImportJob) {
    val stage = job.stage
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(job.fileName, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (stage is ImportJob.Stage.Done) Text(stage.track.shortQualityLabel, style = Motif.mono(11.sp), color = Motif.badge)
        }
        val progress = when (stage) {
            ImportJob.Stage.Copying -> 0.15f
            is ImportJob.Stage.Analyzing -> 0.25f + 0.75f * stage.progress
            else -> 1f
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = when (stage) {
                is ImportJob.Stage.Done -> Motif.done
                is ImportJob.Stage.Skipped -> Motif.secondary
                else -> Motif.accent
            },
            trackColor = Motif.hairline,
            drawStopIndicator = {},
        )
        val (status, color) = when (stage) {
            ImportJob.Stage.Copying -> "Copying to library…" to Motif.secondary
            is ImportJob.Stage.Analyzing -> "Analyzing tempo and key… ${(stage.progress * 100).toInt()}%" to Motif.secondary
            is ImportJob.Stage.Failed -> stage.message to Color(0xFFFF6B6B)
            is ImportJob.Stage.Skipped -> stage.reason to Motif.secondary
            is ImportJob.Stage.Done -> listOfNotNull(
                "Added",
                stage.track.bpm?.let { "${Math.round(it)} BPM" },
                stage.track.musicalKey,
            ).joinToString(" · ") to Motif.secondary
        }
        Text(status, fontSize = 12.sp, color = color)
    }
}
