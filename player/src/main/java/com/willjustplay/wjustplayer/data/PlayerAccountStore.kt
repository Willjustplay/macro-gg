package com.willjustplay.wjustplayer.data

import android.content.Context
import com.example.data.AccountProfile
import com.example.data.Macro
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local per-macro account roster configured by the Wjust Player end-user.
 * Stored separately from encrypted `.wjm` macros so creator bundles never ship credentials
 * and updating/re-importing an overwritten `.wjm` keeps the user's own accounts intact.
 */
@Serializable
data class PlayerMacroAccounts(
    val accounts: List<AccountProfile> = emptyList(),
    val lastAccountId: String = ""
)

class PlayerAccountStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(File(appContext.filesDir, "wjustplayer").apply { mkdirs() }, "player_accounts_v1.json")
    private val lock = Any()

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _accountsByKey = MutableStateFlow<Map<String, PlayerMacroAccounts>>(emptyMap())
    val accountsByKey: StateFlow<Map<String, PlayerMacroAccounts>> = _accountsByKey.asStateFlow()

    init {
        reload()
    }

    /** Stable lookup key that survives `.wjm` macro re-imports/overwrites via [Macro.originId]. */
    fun keyFor(macro: Macro): String = macro.originId.trim().ifBlank { macro.id }

    fun getFor(macro: Macro): PlayerMacroAccounts {
        val map = _accountsByKey.value
        val key = keyFor(macro)
        return map[key] ?: map[macro.id] ?: PlayerMacroAccounts()
    }

    fun saveAccounts(macro: Macro, accounts: List<AccountProfile>) {
        synchronized(lock) {
            val key = keyFor(macro)
            val prev = _accountsByKey.value[key] ?: _accountsByKey.value[macro.id] ?: PlayerMacroAccounts()
            val validLastId = if (accounts.any { it.id == prev.lastAccountId }) prev.lastAccountId else ""
            val updated = _accountsByKey.value.toMutableMap()
            updated[key] = PlayerMacroAccounts(accounts = accounts, lastAccountId = validLastId)
            persistLocked(updated)
        }
    }

    fun recordAccountTransition(
        macro: Macro,
        lastAccountId: String,
        completedAccountId: String,
        lastUsedAtMs: Long
    ) {
        synchronized(lock) {
            val key = keyFor(macro)
            val cur = _accountsByKey.value[key] ?: _accountsByKey.value[macro.id] ?: return
            val updatedList = if (completedAccountId.isNotBlank() && lastUsedAtMs > 0L) {
                cur.accounts.map { acc ->
                    if (acc.id == completedAccountId) acc.copy(lastUsedAt = lastUsedAtMs) else acc
                }
            } else cur.accounts
            val updated = _accountsByKey.value.toMutableMap()
            updated[key] = PlayerMacroAccounts(
                accounts = updatedList,
                lastAccountId = lastAccountId.ifBlank { cur.lastAccountId }
            )
            persistLocked(updated)
        }
    }

    private fun reload() {
        synchronized(lock) {
            if (!file.exists()) {
                _accountsByKey.value = emptyMap()
                return
            }
            val parsed = runCatching {
                json.decodeFromString<Map<String, PlayerMacroAccounts>>(file.readText(Charsets.UTF_8))
            }.getOrDefault(emptyMap())
            _accountsByKey.value = parsed
        }
    }

    private fun persistLocked(map: Map<String, PlayerMacroAccounts>) {
        _accountsByKey.value = map
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(map), Charsets.UTF_8)
        }
    }

    companion object {
        @Volatile
        private var instance: PlayerAccountStore? = null

        fun get(context: Context): PlayerAccountStore =
            instance ?: synchronized(this) {
                instance ?: PlayerAccountStore(context.applicationContext).also { instance = it }
            }
    }
}
