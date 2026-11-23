package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class TapLuaRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            throw IllegalStateException("Pengguna harus masuk dengan Google sebelum mengakses Firestore.")
        }
        return uid
    }

    fun observeUserProfile(): Flow<UserProfile?> = flow {
        val uid = requireUserId()
        val path = "users/$uid"
        emitAll(
            db.collection("users").document(uid)
                .snapshots()
                .map { snap ->
                    if (!snap.exists()) null
                    else snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    suspend fun ensureUserProfile(): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
        return try {
            val user = auth.currentUser
                ?: throw IllegalStateException("Pengguna belum masuk.")
            val uid = user.uid
            val docRef = db.collection("users").document(uid)
            docPath = docRef.path
            val existing = docRef.get().await()
            if (!existing.exists()) {
                val rawName = user.displayName?.trim().orEmpty().ifBlank { "Pengguna TapLua" }.take(80)
                val payload = mapOf<String, Any?>(
                    "userId" to uid,
                    "displayName" to rawName,
                    "email" to user.email?.take(160),
                    "themeMode" to ThemeMode.SYSTEM.name,
                    "defaultTapDurationMs" to 60,
                    "jitterRadiusPx" to 4,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updatePreferences(
        displayName: String,
        themeMode: ThemeMode,
        defaultTapDurationMs: Int,
        jitterRadiusPx: Int
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
        return try {
            val uid = requireUserId()
            val cleanName = displayName.trim().ifBlank { "Pengguna TapLua" }.take(80)
            val cleanTap = defaultTapDurationMs.coerceIn(10, 5000)
            val cleanJitter = jitterRadiusPx.coerceIn(0, 100)
            val docRef = db.collection("users").document(uid)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "displayName" to cleanName,
                    "themeMode" to themeMode.name,
                    "defaultTapDurationMs" to cleanTap,
                    "jitterRadiusPx" to cleanJitter,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    fun observeMacros(): Flow<List<VisualMacro>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/macros"
        emitAll(
            db.collection("users").document(uid).collection("macros")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(VisualMacro::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveMacro(
        macroId: String?,
        name: String,
        description: String,
        loopCount: Int,
        loopDelayMs: Int,
        stopOnError: Boolean,
        steps: List<MacroStep>
    ): Result<String> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/macros"
        return try {
            val uid = requireUserId()
            val cleanName = name.trim().take(80)
            require(cleanName.isNotEmpty()) { "Nama macro wajib diisi." }
            val cleanDesc = description.trim().take(300)
            val cleanLoop = loopCount.coerceIn(0, 100000)
            val cleanDelay = loopDelayMs.coerceIn(0, 600000)
            val boundedSteps = steps.take(50).map { it.toFirestoreMap() }

            val colRef = db.collection("users").document(uid).collection("macros")
            if (macroId.isNullOrBlank()) {
                val docRef = colRef.document()
                docPath = docRef.path
                val payload = mapOf(
                    "userId" to uid,
                    "name" to cleanName,
                    "description" to cleanDesc,
                    "loopCount" to cleanLoop,
                    "loopDelayMs" to cleanDelay,
                    "stopOnError" to stopOnError,
                    "steps" to boundedSteps,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
                Result.success(docRef.id)
            } else {
                val docRef = colRef.document(macroId)
                docPath = docRef.path
                val updates = mapOf(
                    "name" to cleanName,
                    "description" to cleanDesc,
                    "loopCount" to cleanLoop,
                    "loopDelayMs" to cleanDelay,
                    "stopOnError" to stopOnError,
                    "steps" to boundedSteps,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
                Result.success(macroId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, if (macroId.isNullOrBlank()) OperationType.CREATE else OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteMacro(macroId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/macros/$macroId"
        return try {
            val uid = requireUserId()
            require(macroId.isNotBlank()) { "ID macro tidak valid." }
            val docRef = db.collection("users").document(uid).collection("macros").document(macroId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun observeScripts(): Flow<List<LuaScript>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/scripts"
        emitAll(
            db.collection("users").document(uid).collection("scripts")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(LuaScript::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveScript(
        scriptId: String?,
        title: String,
        description: String,
        code: String,
        tags: List<String>
    ): Result<String> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/scripts"
        return try {
            val uid = requireUserId()
            val cleanTitle = title.trim().take(80)
            require(cleanTitle.isNotEmpty()) { "Judul script Lua wajib diisi." }
            val cleanDesc = description.trim().take(300)
            val cleanCode = code.take(50000)
            require(cleanCode.isNotBlank()) { "Kode script Lua tidak boleh kosong." }
            val cleanTags = tags.map { it.trim().take(24) }.filter { it.isNotEmpty() }.distinct().take(10)

            val colRef = db.collection("users").document(uid).collection("scripts")
            if (scriptId.isNullOrBlank()) {
                val docRef = colRef.document()
                docPath = docRef.path
                val payload = mapOf(
                    "userId" to uid,
                    "title" to cleanTitle,
                    "description" to cleanDesc,
                    "code" to cleanCode,
                    "tags" to cleanTags,
                    "lastRunStatus" to ScriptRunStatus.NEVER.name,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
                Result.success(docRef.id)
            } else {
                val docRef = colRef.document(scriptId)
                docPath = docRef.path
                val updates = mapOf(
                    "title" to cleanTitle,
                    "description" to cleanDesc,
                    "code" to cleanCode,
                    "tags" to cleanTags,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
                Result.success(scriptId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, if (scriptId.isNullOrBlank()) OperationType.CREATE else OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateScriptStatus(scriptId: String, status: ScriptRunStatus): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/scripts/$scriptId"
        return try {
            val uid = requireUserId()
            if (scriptId.isBlank()) return Result.success(Unit)
            val docRef = db.collection("users").document(uid).collection("scripts").document(scriptId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "lastRunStatus" to status.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteScript(scriptId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/scripts/$scriptId"
        return try {
            val uid = requireUserId()
            require(scriptId.isNotBlank()) { "ID script tidak valid." }
            val docRef = db.collection("users").document(uid).collection("scripts").document(scriptId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    fun observeRunHistory(): Flow<List<RunHistoryEntry>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/runHistory"
        emitAll(
            db.collection("users").document(uid).collection("runHistory")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(40)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(RunHistoryEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun addRunHistory(
        targetType: RunTargetType,
        targetId: String,
        targetName: String,
        status: ScriptRunStatus,
        durationMs: Long,
        actionsExecuted: Int,
        summaryMessage: String
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/runHistory"
        return try {
            val uid = requireUserId()
            val safeStatus = if (status == ScriptRunStatus.NEVER) ScriptRunStatus.STOPPED else status
            val docRef = db.collection("users").document(uid).collection("runHistory").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "targetType" to targetType.name,
                "targetId" to targetId.ifBlank { "draft" }.take(128),
                "targetName" to targetName.trim().ifBlank { "Sesi Otomasi" }.take(100),
                "status" to safeStatus.name,
                "durationMs" to durationMs.coerceIn(0L, 86400000L),
                "actionsExecuted" to actionsExecuted.coerceIn(0, 1000000),
                "summaryMessage" to summaryMessage.trim().take(400),
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteRunHistory(runId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/runHistory/$runId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("runHistory").document(runId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}
