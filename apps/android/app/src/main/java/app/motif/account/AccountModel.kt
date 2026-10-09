package app.motif.account

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.motif.data.HistoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the account, history and server screens show. */
data class AccountState(
    val serverUrl: String = MotifApi.DEFAULT_BASE_URL,
    val signedIn: Boolean = false,
    val account: Account? = null,
    val sessions: List<DeviceSession> = emptyList(),
    val summary: HistorySummary? = null,
    val syncing: Boolean = false,
    val syncError: String? = null,
    val lastSyncMs: Long? = null,
    val unsynced: Int = 0,
    val plays: Int = 0,
    val health: ServerHealth? = null,
    val healthError: String? = null,
    val latencyMs: Long? = null,
    val healthCheckedMs: Long? = null,
    /** Bumped when pulled events or a deletion changed local history. */
    val historyRevision: Int = 0,
) {
    val isCustomServer get() = serverUrl != MotifApi.DEFAULT_BASE_URL
}

/**
 * Sign-in, the account and its devices, sync, listening history and server details.
 * All optional: signed out (or offline) the app works the same and history stays on the phone.
 */
class AccountModel(private val context: Context, val history: HistoryStore, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("motif", Context.MODE_PRIVATE)
    private var api = makeApi(prefs.getString(KEY_SERVER, null) ?: MotifApi.DEFAULT_BASE_URL)
    private var syncService = SyncService(api, history)

    private val _state = MutableStateFlow(AccountState(serverUrl = api.baseUrl, signedIn = api.isSignedIn))
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val _showWelcome = MutableStateFlow(!prefs.getBoolean(KEY_WELCOME_DONE, false) && !api.isSignedIn)
    /** Sign in / create account / continue offline, until the user picks one. */
    val showWelcome: StateFlow<Boolean> = _showWelcome.asStateFlow()

    /** Pause history: lasts until the app process ends (docs/analytics.md). */
    val historyPaused = MutableStateFlow(false)

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()
    fun clearError() { _errors.value = null }

    private fun makeApi(server: String) = MotifApi(server, KeystoreTokenStore(context, server))

    /** On launch and whenever the app comes to the foreground. */
    fun start() {
        scope.launch {
            _state.update { it.copy(signedIn = api.isSignedIn, lastSyncMs = syncService.lastSyncMs()) }
            refreshCounts()
            syncNow()
            refreshAccount()
        }
    }

    fun showWelcome() { _showWelcome.value = true }

    fun continueOffline() {
        prefs.edit().putBoolean(KEY_WELCOME_DONE, true).apply()
        _showWelcome.value = false
    }

    // Sign in

    val device: DeviceInfo
        get() = DeviceInfo(
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?: listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).joinToString(" "),
        )

    /** Throws [ApiException] so the form can show the server's message. */
    suspend fun signIn(username: String, password: String) {
        api.login(username, password, device)
        didSignIn()
    }

    suspend fun register(username: String, password: String) {
        api.register(username, password, device)
        didSignIn()
    }

    suspend fun checkUsername(username: String) = api.checkUsername(username)

    private suspend fun didSignIn() {
        prefs.edit().putBoolean(KEY_WELCOME_DONE, true).apply()
        _showWelcome.value = false
        _state.update { it.copy(signedIn = true) }
        refreshAccount()
        syncNow()
    }

    fun signOut() {
        scope.launch {
            api.logout()
            clearAccount()
        }
    }

    private fun clearAccount() {
        _state.update { it.copy(signedIn = false, account = null, sessions = emptyList(), summary = null, syncError = null) }
    }

    // Account

    suspend fun refreshAccount() {
        if (!_state.value.signedIn) return
        try {
            coroutineScope {
                val me = async { api.me() }
                val sessions = async { api.sessions() }
                val summary = async { api.historySummary() }
                _state.update { it.copy(account = me.await(), sessions = sessions.await(), summary = summary.await()) }
            }
        } catch (e: Exception) {
            handle(e)
        }
    }

    suspend fun updateAccount(username: String?, displayName: String?) {
        val account = api.updateAccount(username, displayName)
        _state.update { it.copy(account = account) }
    }

    suspend fun changePassword(current: String, new: String) {
        api.changePassword(current, new)
        refreshAccount()
    }

    fun signOut(session: DeviceSession) {
        scope.launch {
            try {
                api.signOut(session.id)
                if (session.current) clearAccount() else refreshAccount()
            } catch (e: Exception) {
                handle(e)
            }
        }
    }

    fun signOutOtherDevices() {
        scope.launch {
            try {
                api.signOutOtherDevices()
                refreshAccount()
            } catch (e: Exception) {
                handle(e)
            }
        }
    }

    /** Deletes the account and its synced copy on the server. Music and history on this phone stay. */
    suspend fun deleteAccount(password: String) {
        api.deleteAccount(password)
        clearAccount()
    }

    /** A revoked sign-in turns into "signed out"; other failures show as a message. */
    private fun handle(e: Exception) {
        if (e is CancellationException) throw e
        when {
            !api.isSignedIn -> clearAccount()
            e is ApiException && e.isNetwork -> _state.update { it.copy(syncError = e.message) }
            else -> _errors.value = describe(e)
        }
    }

    // Sync

    fun syncNow() {
        scope.launch { sync() }
    }

    private suspend fun sync() {
        val current = _state.value
        if (!current.signedIn || current.syncing) return
        _state.update { it.copy(syncing = true) }
        try {
            val report = syncService.sync()
            _state.update {
                it.copy(
                    syncError = null,
                    lastSyncMs = syncService.lastSyncMs(),
                    historyRevision = it.historyRevision + if (report.pulled > 0 || report.uploaded > 0) 1 else 0,
                )
            }
        } catch (e: Exception) {
            if (e is ApiException && e.isNetwork) {
                _state.update { it.copy(syncError = "Offline. Changes upload when the server is reachable.") }
            } else {
                handle(e)
            }
        } finally {
            _state.update { it.copy(syncing = false, signedIn = api.isSignedIn) }
            refreshCounts()
        }
    }

    private var pendingSync: Job? = null

    /**
     * A play was written: refresh the counts and history list, and upload a
     * minute later so a run of plays goes up in one sync.
     */
    fun playRecorded() {
        scope.launch {
            refreshCounts()
            _state.update { it.copy(historyRevision = it.historyRevision + 1) }
            if (_state.value.signedIn && pendingSync?.isActive != true) {
                pendingSync = launch {
                    delay(60_000)
                    sync()
                }
            }
        }
    }

    suspend fun refreshCounts() {
        val unsynced = history.unsyncedCount()
        val plays = history.count(listOf("play"))
        _state.update { it.copy(unsynced = unsynced, plays = plays) }
    }

    // Listening history

    enum class DeleteRange(val label: String) {
        LastHour("Last hour"), Today("Today"), LastWeek("Last 7 days"), Everything("Everything");

        fun fromMs(now: Long = System.currentTimeMillis()): Long? = when (this) {
            LastHour -> now - 3_600_000
            Today -> java.util.Calendar.getInstance().apply {
                timeInMillis = now
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            LastWeek -> now - 7 * 86_400_000L
            Everything -> null
        }
    }

    /** Signed in, the server deletes first so every device follows; offline, just this phone. */
    fun deleteHistory(range: DeleteRange) {
        scope.launch {
            val from = range.fromMs()
            try {
                if (_state.value.signedIn) api.deleteHistory(from, null)
                history.delete(HistoryStore.LISTENING_TYPES, from, null)
                refreshCounts()
                val summary = if (_state.value.signedIn) runCatching { api.historySummary() }.getOrNull() else null
                _state.update { it.copy(summary = summary ?: it.summary, historyRevision = it.historyRevision + 1) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errors.value = "Couldn't delete history. ${describe(e)}"
            }
        }
    }

    suspend fun exportHistory(): String = history.exportJsonLines()

    // Server

    fun checkHealth() {
        scope.launch {
            val started = System.currentTimeMillis()
            try {
                val health = api.health()
                _state.update {
                    it.copy(health = health, healthError = null, latencyMs = System.currentTimeMillis() - started,
                        healthCheckedMs = System.currentTimeMillis())
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(health = null, latencyMs = null, healthError = describe(e), healthCheckedMs = System.currentTimeMillis()) }
            }
        }
    }

    /** Switching servers signs out; history stays here and uploads to the new server after signing in there. */
    fun useServer(url: String?) {
        val target = url ?: MotifApi.DEFAULT_BASE_URL
        if (target == api.baseUrl) return
        scope.launch {
            api.logout()
            clearAccount()
            if (url == null) prefs.edit().remove(KEY_SERVER).apply() else prefs.edit().putString(KEY_SERVER, target).apply()
            api = makeApi(target)
            syncService = SyncService(api, history)
            _state.update { it.copy(serverUrl = target, health = null) }
            checkHealth()
        }
    }

    companion object {
        /** The server's message, or a plain one for an answer this version can't read. */
        fun describe(e: Exception): String = when (e) {
            is ApiException -> e.message
            is org.json.JSONException -> "The server sent an answer this version of Motif can't read."
            else -> e.message ?: e.toString()
        }

        private const val KEY_SERVER = "server_url"
        private const val KEY_WELCOME_DONE = "welcome_done"

        /** `https://` is added when missing; plain http only for local servers. */
        fun parseServer(text: String): String? {
            var trimmed = text.trim().trimEnd('/')
            if (!trimmed.contains("://")) trimmed = "https://$trimmed"
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
            val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
            val local = host == "localhost" || host.endsWith(".local") || host.startsWith("192.168.") || host.startsWith("10.")
            return trimmed.takeIf { uri.scheme == "https" || (uri.scheme == "http" && local) }
        }
    }
}
