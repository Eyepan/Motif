package app.motif.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.motif.MotifApp
import app.motif.account.AccountModel
import app.motif.account.ApiException
import app.motif.account.DeviceSession
import app.motif.data.HistoryEvent
import app.motif.data.Track
import app.motif.data.tracksByKey
import app.motif.ui.theme.Motif
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

// Sign in, settings, account, listening history and server screens.
// Layout follows the approved mockups (https://claude.ai/artifact/M4dkYzjciFdeCqP9L1782W).

private val danger = Color(0xFFFF8A8A)
private val warning = Color(0xFFF2B544)
private val dangerFill = Color(0xFF3A1A1D)

/** Settings sub-screens, opened over the tabs. */
enum class SettingsPage { Settings, Account, History, Server }

private fun relative(ms: Long): String =
    if (System.currentTimeMillis() - ms < 60_000) "just now"
    else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

private fun memberSince(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

private fun errorText(e: Exception): String = AccountModel.describe(e)

// Sign in and create account

/** First launch: sign in, create an account, or skip and use Motif offline. */
@Composable
fun WelcomeScreen(app: MotifApp) {
    var signUp by rememberSaveable { mutableStateOf(false) }
    var offline by rememberSaveable { mutableStateOf(false) }
    if (signUp) {
        BackHandler { signUp = false }
        SignUpScreen(app, onBack = { signUp = false })
        return
    }
    Column(
        Modifier.fillMaxSize().background(Motif.ground).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MotifMark()
            Text("Motif", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Motif.text)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sign in to sync your listening", fontSize = 32.sp, lineHeight = 40.sp, color = Motif.text)
            Text(
                "History, crates and playlists follow you across Android, iPhone and Mac. Music files stay on your devices.",
                fontSize = 16.sp, lineHeight = 24.sp, color = Motif.secondary,
            )
        }
        AuthForm(app, signUp = false)
        OutlinedButton(
            onClick = { signUp = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Create an account", color = Motif.accent, fontWeight = FontWeight.SemiBold) }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(onClick = { offline = true }) {
                Text("Skip, use Motif offline", color = Motif.accent, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Everything works without an account.\nSign in later from Settings.",
                fontSize = 14.sp, color = Motif.secondary, textAlign = TextAlign.Center,
            )
        }
    }
    if (offline) OfflineSheet(onContinue = { offline = false; app.account.continueOffline() }, onDismiss = { offline = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignUpScreen(app: MotifApp, onBack: () -> Unit) {
    Scaffold(
        containerColor = Motif.ground,
        topBar = { BackBar("Create account", onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text("A username and a password. No email, nothing to pay.", fontSize = 16.sp, lineHeight = 24.sp, color = Motif.secondary)
            AuthForm(app, signUp = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfflineSheet(onContinue: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Motif.surface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Use Motif without an account", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Motif.text)
            Text(
                "Nothing is locked. Motif keeps your listening history on this phone and uploads it if you sign in later.",
                fontSize = 15.sp, color = Motif.secondary,
            )
            Text("WORKS OFFLINE", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Motif.secondary)
            listOf(
                "Playback, lossless and in the background", "Import, BPM and key analysis",
                "Crates, DJ Mix and Mix into next", "Listening history and your yearly recap",
            ).forEach { Text("✓  $it", fontSize = 15.sp, color = Motif.text) }
            Text("NEEDS AN ACCOUNT", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Motif.secondary)
            Text("Syncing history, crates and playlists with your other devices", fontSize = 15.sp, color = Motif.badge)
            AccentButton("Continue offline", onClick = onContinue)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Back to sign in", color = Motif.accent) }
        }
    }
}

/** Username and password, plus confirmation and a live availability check when creating an account. */
@Composable
private fun AuthForm(app: MotifApp, signUp: Boolean) {
    val scope = rememberCoroutineScope()
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var availability by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val usernameValid = USERNAME.matches(username)
    val canSubmit = !busy && usernameValid && password.length >= 8 &&
        (!signUp || (password == confirm && available != false))

    if (signUp) {
        LaunchedEffect(username) {
            available = null
            availability = null
            if (!usernameValid) return@LaunchedEffect
            delay(400)
            try {
                val check = app.account.checkUsername(username)
                available = check.available
                availability = if (check.available) "Available" else "That username is taken"
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.status == 400) { available = false; availability = e.message }
            } catch (_: Exception) {
            }
        }
    }

    fun submit() {
        if (!canSubmit) return
        busy = true
        error = null
        scope.launch {
            try {
                if (signUp) app.account.register(username, password) else app.account.signIn(username, password)
                app.crates.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                error = if (e.status == 409) "That username is taken." else e.message
            } catch (e: Exception) {
                error = errorText(e)
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MotifTextField(
                username, { username = it.trim() }, "Username",
                isError = available == false,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            )
            if (signUp) {
                availability?.let { Hint(it, if (available == true) Motif.accent else danger) }
                Hint("3 to 32 letters, numbers, dots, dashes or underscores")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MotifTextField(
                password, { password = it }, "Password",
                visual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (signUp) ImeAction.Next else ImeAction.Done),
                trailing = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                onDone = ::submit,
            )
            if (signUp) Hint("At least 8 characters")
        }
        if (signUp) {
            MotifTextField(
                confirm, { confirm = it }, "Confirm password",
                isError = confirm.isNotEmpty() && confirm != password,
                visual = PasswordVisualTransformation(),
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                onDone = ::submit,
            )
            Row(
                Modifier.fillMaxWidth().background(Motif.surface, RoundedCornerShape(12.dp)).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Info, null, tint = warning)
                Text(
                    "There is no password reset without an email. If you forget it, your music and history are still on this phone and upload again to a new account. Google Password Manager can save it for you.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = Motif.badge,
                )
            }
        }
        error?.let { Text(it, fontSize = 14.sp, color = danger) }
        AccentButton(if (signUp) "Create account" else "Sign in", enabled = canSubmit, busy = busy, onClick = ::submit)
    }
}

private val USERNAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$")

@Composable
private fun MotifTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    isError: Boolean = false,
    visual: VisualTransformation = VisualTransformation.None,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    trailing: (@Composable () -> Unit)? = null,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = visual,
        keyboardOptions = keyboard,
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Motif.accent,
            focusedLabelColor = Motif.accent,
            cursorColor = Motif.accent,
            unfocusedBorderColor = Color(0xFF5C626E),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hint(text: String, color: Color = Motif.secondary) {
    Text(text, fontSize = 12.sp, color = color, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun AccentButton(text: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Motif.accent, contentColor = Motif.onAccent,
            disabledContainerColor = Motif.accent.copy(alpha = 0.35f), disabledContentColor = Motif.onAccent,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Motif.onAccent, strokeWidth = 2.dp)
        else Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MotifMark(size: Int = 40) {
    Box(Modifier.size(size.dp).background(Motif.accent, CircleShape), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(0.2f, 0.45f, 0.7f, 0.4f, 0.15f).forEach { h ->
                Box(Modifier.size(width = 3.dp, height = (size * h).dp).background(Motif.onAccent, RoundedCornerShape(2.dp)))
            }
        }
    }
}

// Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Motif.ground, titleContentColor = Motif.text),
    )
}

/** Settings and its pages, shown over the tabs. [page] is the open page; back closes it. */
@Composable
fun SettingsScreens(app: MotifApp, pages: List<SettingsPage>, onNavigate: (List<SettingsPage>) -> Unit) {
    val page = pages.lastOrNull() ?: return
    val back = { onNavigate(pages.dropLast(1)) }
    val open = { p: SettingsPage -> onNavigate(pages + p) }
    BackHandler(onBack = back)
    Box(Modifier.fillMaxSize().background(Motif.ground)) {
        when (page) {
            SettingsPage.Settings -> SettingsScreen(app, back, open)
            SettingsPage.Account -> AccountScreen(app, back)
            SettingsPage.History -> HistoryScreen(app, back)
            SettingsPage.Server -> ServerScreen(app, back)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(app: MotifApp, onBack: () -> Unit, onOpen: (SettingsPage) -> Unit) {
    val state by app.account.state.collectAsStateWithLifecycle()
    val player by app.playback.state.collectAsStateWithLifecycle()
    val analyze by app.analyzeOnImport.collectAsStateWithLifecycle()
    Scaffold(containerColor = Motif.ground, topBar = { BackBar("Settings", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                if (state.signedIn) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth().background(Motif.surface, RoundedCornerShape(16.dp))
                            .clickable { onOpen(SettingsPage.Account) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Avatar(state.account?.displayName ?: state.account?.username ?: "?", 48)
                        Column(Modifier.weight(1f)) {
                            Text(state.account?.displayName ?: state.account?.username ?: "Account", fontSize = 16.sp, color = Motif.text)
                            Text(syncLine(state.syncError, state.lastSyncMs, state.sessions.size), fontSize = 14.sp, color = Motif.secondary)
                        }
                    }
                } else {
                    Column(
                        Modifier.padding(16.dp).fillMaxWidth().background(Motif.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Not signed in", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Motif.text)
                        Text("Your history is on this phone only. Sign in to sync it with your other devices.", fontSize = 14.sp, color = Motif.secondary)
                        AccentButton("Sign in or create account") { onBack(); app.account.showWelcome() }
                    }
                }
            }
            item { GroupLabel("Playback") }
            item {
                SwitchRow("Mix into next by default", "Beatmatched blend, else gapless", player.mixIntoNext) { app.playback.setMixIntoNext(it) }
            }
            item { GroupLabel("Library") }
            item { SwitchRow("Analyze for mixing on import", null, analyze, app::setAnalyzeOnImport) }
            item { GroupLabel("Sync") }
            item { NavRow("Listening history", "${state.plays} plays · pause, export or delete") { onOpen(SettingsPage.History) } }
            item {
                NavRow("Server", state.serverUrl.substringAfter("://") + if (state.health?.isOk == true) " · connected" else "") {
                    onOpen(SettingsPage.Server)
                }
            }
            item {
                Text(
                    "Only listening history, crates and playlists sync. Music files never leave this phone.",
                    fontSize = 13.sp, color = Motif.secondary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun syncLine(error: String?, lastSyncMs: Long?, devices: Int): String = when {
    error != null -> "Not synced"
    lastSyncMs == null -> "Not synced yet"
    devices > 1 -> "Synced ${relative(lastSyncMs)} · $devices devices"
    else -> "Synced ${relative(lastSyncMs)}"
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Motif.accent, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp))
}

@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = Motif.text)
            detail?.let { Text(it, fontSize = 14.sp, color = Motif.secondary) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Motif.accent, checkedThumbColor = Motif.onAccent),
        )
    }
}

@Composable
private fun NavRow(title: String, detail: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 72.dp).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 16.sp, color = Motif.text)
        detail?.let { Text(it, fontSize = 14.sp, color = Motif.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Avatar(name: String, size: Int) {
    Box(Modifier.size(size.dp).background(Motif.accent, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Motif.onAccent, fontSize = (size * 0.42).sp, fontWeight = FontWeight.SemiBold)
    }
}

// Account

private fun DeviceSession.icon(): ImageVector = when (platform) {
    "ios", "ipados" -> Icons.Outlined.PhoneIphone
    "macos" -> Icons.Outlined.Laptop
    else -> Icons.Outlined.PhoneAndroid
}

private fun DeviceSession.platformName(): String = when (platform) {
    "ios" -> "iOS"
    "ipados" -> "iPadOS"
    "macos" -> "macOS"
    "android" -> "Android"
    else -> "Other"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountScreen(app: MotifApp, onBack: () -> Unit) {
    val state by app.account.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.account.refreshAccount() }
    // Signed out from here (or from another device): back to Settings.
    LaunchedEffect(state.signedIn) { if (!state.signedIn) onBack() }

    Scaffold(containerColor = Motif.ground, topBar = { BackBar("Account", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val account = state.account
                    Avatar(account?.displayName ?: account?.username ?: "?", 56)
                    Column(Modifier.weight(1f)) {
                        Text(account?.displayName ?: account?.username ?: "", fontSize = 22.sp, color = Motif.text)
                        if (account != null) {
                            Text("@${account.username ?: ""} · member since ${memberSince(account.createdAtMs)}", fontSize = 14.sp, color = Motif.secondary)
                        }
                    }
                    IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, "Edit name and username", tint = Motif.text) }
                }
            }
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().background(Motif.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(8.dp).background(if (state.syncError == null) Motif.accent else warning, CircleShape))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                state.syncing -> "Syncing…"
                                state.syncError != null -> "Not synced"
                                state.unsynced > 0 -> "Waiting to upload"
                                else -> "Up to date"
                            },
                            fontSize = 16.sp, color = Motif.text,
                        )
                        Text(
                            state.syncError ?: "${state.summary?.plays ?: state.plays} plays · " +
                                (state.lastSyncMs?.let { "synced ${relative(it)}" } ?: "not synced yet"),
                            fontSize = 14.sp, color = Motif.secondary,
                        )
                    }
                    OutlinedButton(onClick = app.account::syncNow, enabled = !state.syncing) { Text("Sync now", color = Motif.accent) }
                }
            }
            item { GroupLabel("Signed-in devices") }
            items(state.sessions, key = { it.id }) { session ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(session.icon(), null, tint = Motif.secondary)
                    Column(Modifier.weight(1f)) {
                        Text(session.deviceName ?: session.platformName(), fontSize = 16.sp, color = Motif.text)
                        Text(
                            if (session.current) "This phone" else "${session.platformName()} · active ${relative(session.lastUsedAtMs)}",
                            fontSize = 14.sp, color = if (session.current) Motif.accent else Motif.secondary,
                        )
                    }
                    if (!session.current) {
                        IconButton(onClick = { app.account.signOut(session) }) {
                            Icon(Icons.AutoMirrored.Outlined.Logout, "Sign out ${session.deviceName ?: session.platformName()}", tint = Motif.secondary)
                        }
                    }
                }
            }
            if (state.sessions.size > 1) {
                item { NavRow("Sign out of other devices", null, app.account::signOutOtherDevices) }
            }
            item { GroupLabel("Security") }
            item { NavRow("Change password", "Signs out your other devices") { changingPassword = true } }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Sign out", color = Motif.text)
                    }
                    Button(
                        onClick = { deleting = true },
                        colors = ButtonDefaults.buttonColors(containerColor = dangerFill, contentColor = danger),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("Delete account") }
                }
            }
            item {
                Text(
                    "Signing out keeps your music and history on this phone. Deleting the account removes the synced copy from the server only.",
                    fontSize = 13.sp, color = Motif.secondary, modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }

    if (editing) EditProfileDialog(app, state.account?.username ?: "", state.account?.displayName ?: "") { editing = false }
    if (changingPassword) ChangePasswordDialog(app) { changingPassword = false }
    if (deleting) DeleteAccountDialog(app, state.account?.username) { deleting = false }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out on this phone?") },
            text = { Text("Your music and history stay here and upload again when you sign back in.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; app.account.signOut() }) { Text("Sign out", color = danger) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
            containerColor = Motif.surface,
        )
    }
}

/** A dialog with fields that runs [action] and shows its error inline. */
@Composable
private fun FormDialog(
    title: String,
    confirm: String,
    canConfirm: Boolean,
    destructive: Boolean = false,
    note: String,
    onDismiss: () -> Unit,
    action: suspend () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
                Text(error ?: note, fontSize = 13.sp, color = if (error == null) Motif.secondary else danger)
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            action()
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ApiException) {
                            error = when (e.status) {
                                409 -> "That username is taken."
                                403 -> "That password is wrong."
                                else -> e.message
                            }
                        } catch (e: Exception) {
                            error = errorText(e)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(confirm, color = if (destructive) danger else Motif.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Motif.surface,
    )
}

@Composable
private fun EditProfileDialog(app: MotifApp, currentUsername: String, currentName: String, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    var username by remember { mutableStateOf(currentUsername) }
    FormDialog(
        "Edit profile", "Save", canConfirm = USERNAME.matches(username),
        note = "Your name is shown only to you. Changing your username changes how you sign in.",
        onDismiss = onDismiss,
        action = {
            app.account.updateAccount(
                username.takeIf { !it.equals(currentUsername, ignoreCase = true) },
                name.takeIf { it != currentName },
            )
        },
    ) {
        MotifTextField(name, { name = it }, "Name")
        MotifTextField(username, { username = it.trim() }, "Username", keyboard = KeyboardOptions(autoCorrectEnabled = false))
    }
}

@Composable
private fun ChangePasswordDialog(app: MotifApp, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val hidden = PasswordVisualTransformation()
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password)
    FormDialog(
        "Change password", "Change", canConfirm = current.isNotEmpty() && new.length >= 8 && new == confirm,
        note = "At least 8 characters. Your other devices are signed out.",
        onDismiss = onDismiss,
        action = { app.account.changePassword(current, new) },
    ) {
        MotifTextField(current, { current = it }, "Current password", visual = hidden, keyboard = keyboard)
        MotifTextField(new, { new = it }, "New password", visual = hidden, keyboard = keyboard)
        MotifTextField(confirm, { confirm = it }, "Confirm new password", isError = confirm.isNotEmpty() && confirm != new, visual = hidden, keyboard = keyboard)
    }
}

@Composable
private fun DeleteAccountDialog(app: MotifApp, username: String?, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    FormDialog(
        "Delete ${username ?: "account"}?", "Delete", canConfirm = password.isNotEmpty(), destructive = true,
        note = "This removes your account and the synced copy of your history from the server, for every device. Music and history on this phone stay.",
        onDismiss = onDismiss,
        action = { app.account.deleteAccount(password) },
    ) {
        MotifTextField(password, { password = it }, "Password", visual = PasswordVisualTransformation(),
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
    }
}

// Listening history

private data class PlayRow(val event: HistoryEvent, val track: Track?, val thisDevice: Boolean, val detail: String, val mix: Boolean)

private fun playRow(event: HistoryEvent, byKey: Map<String, Track>, deviceId: String): PlayRow {
    val payload = runCatching { JSONObject(event.payload) }.getOrElse { JSONObject() }
    val listened = if (payload.has("listened_ms")) formatClock(payload.optLong("listened_ms")) else null
    val mix = payload.optString("context") == "mix"
    val detail = when {
        payload.optString("end_reason") == "skipped" -> listened?.let { "Skipped at $it" } ?: "Skipped"
        mix -> "DJ Mix" + (listened?.let { " · $it" } ?: "")
        else -> listened?.let { "Played $it" } ?: "Played"
    }
    return PlayRow(event, event.trackKey?.let(byKey::get), event.deviceId == deviceId, detail, mix)
}

private fun formatClock(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

private fun dayLabel(ms: Long): String = when {
    DateUtils.isToday(ms) -> "Today"
    DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(app: MotifApp, onBack: () -> Unit) {
    val state by app.account.state.collectAsStateWithLifecycle()
    val paused by app.account.historyPaused.collectAsStateWithLifecycle()
    val tracks by app.library.tracks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var onlyThisPhone by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<PlayRow>>(emptyList()) }
    val history = app.account.history

    LaunchedEffect(onlyThisPhone, state.historyRevision, tracks) {
        val byKey = tracksByKey(tracks)
        val device = history.deviceId
        rows = history.recent(listOf("play"), 500, if (onlyThisPhone) device else null).map { playRow(it, byKey, device) }
        app.account.refreshCounts()
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/jsonl")) { uri ->
        if (uri != null) scope.launch {
            val text = app.account.exportHistory()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
        }
    }

    Scaffold(
        containerColor = Motif.ground,
        topBar = {
            BackBar("Listening history", onBack) {
                IconButton(onClick = { exporter.launch("motif-history.jsonl") }) { Icon(Icons.Outlined.IosShare, "Export history") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Delete history") }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                SwitchRow("Pause history", "Until you next open Motif", paused) { app.account.historyPaused.value = it }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "All devices", true to "This phone").forEach { (value, label) ->
                        FilterChip(
                            selected = onlyThisPhone == value,
                            onClick = { onlyThisPhone = value },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF3A4A14), selectedLabelColor = Color(0xFFE4F7A8)),
                        )
                    }
                }
            }
            item {
                val parts = listOfNotNull(
                    "${state.plays} plays",
                    if (state.unsynced > 0) "${state.unsynced} waiting to upload" else null,
                    if (!state.signedIn) "kept on this phone only" else null,
                )
                Text(parts.joinToString(" · "), fontSize = 13.sp, color = Motif.secondary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            if (rows.isEmpty()) {
                item {
                    Text("No plays yet. Everything you listen to shows up here.", fontSize = 15.sp, color = Motif.secondary,
                        modifier = Modifier.padding(24.dp))
                }
            }
            var lastDay: String? = null
            rows.forEach { row ->
                val day = dayLabel(row.event.atMs)
                if (day != lastDay) {
                    lastDay = day
                    item(key = "day-$day") { GroupLabel(day) }
                }
                item(key = row.event.id) { PlayRowView(row) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete listening history") },
            text = {
                Column {
                    Text(
                        if (state.signedIn) "Plays are removed from every signed-in device and the server. Your library and crates stay."
                        else "Plays are removed from this phone. Your library and crates stay.",
                        color = Motif.secondary,
                    )
                    AccountModel.DeleteRange.entries.forEach { range ->
                        TextButton(onClick = { confirmDelete = false; app.account.deleteHistory(range) }) {
                            Text(range.label, color = danger)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            containerColor = Motif.surface,
        )
    }
}

@Composable
private fun PlayRowView(row: PlayRow) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 16.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val track = row.track
        if (track != null) ArtTile(track.artSeed, track.monogram, size = 48.dp, radius = 8.dp, artIds = listOf(track.id))
        else Box(Modifier.size(48.dp).background(Motif.raised, RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(track?.title ?: "Track not on this phone", fontSize = 16.sp, color = if (track == null) Motif.secondary else Motif.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(track?.artist, if (row.thisDevice) "This phone" else "Another device").joinToString(" · "),
                fontSize = 14.sp, color = Motif.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(row.detail, fontSize = 12.sp, color = if (row.mix) Motif.accent else Motif.secondary)
        }
        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(row.event.atMs)), style = Motif.mono(12.sp), color = Motif.secondary)
    }
}

// Server

private val regions = mapOf(
    "sin1" to "Singapore", "bom1" to "Mumbai", "hnd1" to "Tokyo", "fra1" to "Frankfurt",
    "iad1" to "Washington, D.C.", "sfo1" to "San Francisco", "lhr1" to "London", "syd1" to "Sydney",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerScreen(app: MotifApp, onBack: () -> Unit) {
    val state by app.account.state.collectAsStateWithLifecycle()
    var custom by rememberSaveable { mutableStateOf(state.isCustomServer) }
    var address by rememberSaveable { mutableStateOf(if (state.isCustomServer) state.serverUrl else "") }
    var addressError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { app.account.checkHealth() }

    Scaffold(containerColor = Motif.ground, topBar = { BackBar("Server", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            val health = state.health
            val statusColor = when {
                health == null -> if (state.healthError == null) Motif.secondary else danger
                health.isOk -> Motif.accent
                else -> warning
            }
            Row(
                Modifier.padding(16.dp).fillMaxWidth().background(Motif.surface, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            health == null -> if (state.healthError == null) "Checking…" else "Unreachable"
                            health.isOk -> "Connected"
                            else -> "Degraded"
                        },
                        fontSize = 16.sp, color = Motif.text,
                    )
                    Text(
                        state.healthError ?: listOfNotNull(
                            state.healthCheckedMs?.let { "Checked ${relative(it)}" },
                            state.latencyMs?.let { "$it ms" },
                        ).joinToString(" · "),
                        fontSize = 14.sp, color = Motif.secondary,
                    )
                }
                OutlinedButton(onClick = app.account::checkHealth) { Text("Test", color = Motif.accent) }
            }

            GroupLabel("Details")
            DetailRow("Address", state.serverUrl.substringAfter("://"))
            health?.region?.let { DetailRow("Region", regions[it]?.let { name -> "$name ($it)" } ?: it) }
            health?.version?.let { DetailRow("Server version", it) }
            state.summary?.lastUploadAtMs?.let { DetailRow("Last upload", relative(it)) }
            DetailRow("Waiting to upload", state.unsynced.toString(), if (state.unsynced > 0) warning else Motif.secondary)

            GroupLabel("Use a different server")
            SwitchRow("Use a self-hosted server", null, custom) { on ->
                custom = on
                if (!on && state.isCustomServer) app.account.useServer(null)
            }
            if (custom) {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MotifTextField(
                        address, { address = it }, "Server address", isError = addressError != null,
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    )
                    AccentButton("Use this server", enabled = address.isNotBlank()) {
                        val url = AccountModel.parseServer(address)
                        addressError = if (url == null) "Enter an https:// address." else null
                        if (url != null) app.account.useServer(url)
                    }
                }
            }
            Text(
                addressError ?: "Switching servers signs you out. Your history stays on this phone and uploads to the new server once you sign in there.",
                fontSize = 13.sp, color = if (addressError == null) Motif.secondary else danger,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color = Motif.secondary) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, color = Motif.text, modifier = Modifier.weight(1f))
        Text(value, style = Motif.mono(14.sp), color = valueColor)
    }
}
