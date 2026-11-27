package com.openbot.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class ThreadSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int
)

data class AuditEntry(
    val id: Long,
    val threadId: String,
    val command: String,
    val decision: String,
    val risk: String?,
    val exitCode: Int?,
    val sandboxMode: String?,
    val summary: String?,
    val createdAt: Long
)

/**
 * Local SQLite persistence for chat threads, LLM history, and command/approval audit logs.
 */
class ThreadStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE threads (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                thread_id TEXT NOT NULL,
                sender TEXT NOT NULL,
                text TEXT NOT NULL,
                is_user INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                seq INTEGER NOT NULL,
                FOREIGN KEY(thread_id) REFERENCES threads(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_messages_thread_seq ON messages(thread_id, seq)")

        db.execSQL(
            """
            CREATE TABLE agent_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                thread_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(thread_id) REFERENCES threads(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_agent_history_thread ON agent_history(thread_id, id)")

        db.execSQL(
            """
            CREATE TABLE audit_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                thread_id TEXT NOT NULL,
                command TEXT NOT NULL,
                decision TEXT NOT NULL,
                risk TEXT,
                exit_code INTEGER,
                sandbox_mode TEXT,
                summary TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(thread_id) REFERENCES threads(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_audit_log_created ON audit_log(created_at DESC)")
        db.execSQL("CREATE INDEX idx_audit_log_thread ON audit_log(thread_id, created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Initial schema version 1; future migrations go here.
    }

    @Synchronized
    fun listThreads(): List<ThreadSummary> {
        val result = mutableListOf<ThreadSummary>()
        val sql = """
            SELECT t.id, t.title, t.created_at, t.updated_at,
                   (SELECT COUNT(*) FROM messages m WHERE m.thread_id = t.id) AS msg_count
            FROM threads t
            ORDER BY t.updated_at DESC
        """.trimIndent()
        readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                result += ThreadSummary(
                    id = c.getString(0),
                    title = c.getString(1),
                    createdAt = c.getLong(2),
                    updatedAt = c.getLong(3),
                    messageCount = c.getInt(4)
                )
            }
        }
        return result
    }

    @Synchronized
    fun createThread(title: String = DEFAULT_TITLE): ThreadSummary {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val cleanTitle = title.trim().ifBlank { DEFAULT_TITLE }
        val values = ContentValues().apply {
            put("id", id)
            put("title", cleanTitle)
            put("created_at", now)
            put("updated_at", now)
        }
        writableDatabase.insertOrThrow("threads", null, values)
        return ThreadSummary(id = id, title = cleanTitle, createdAt = now, updatedAt = now, messageCount = 0)
    }

    @Synchronized
    fun ensureInitialThread(): ThreadSummary {
        return listThreads().firstOrNull() ?: createThread()
    }

    @Synchronized
    fun renameThread(threadId: String, newTitle: String) {
        val clean = newTitle.trim().ifBlank { return }
        val values = ContentValues().apply {
            put("title", clean.take(MAX_TITLE_CHARS))
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("threads", values, "id = ?", arrayOf(threadId))
    }

    @Synchronized
    fun deleteThread(threadId: String) {
        writableDatabase.delete("threads", "id = ?", arrayOf(threadId))
    }

    @Synchronized
    fun loadMessages(threadId: String): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        readableDatabase.query(
            "messages",
            arrayOf("id", "sender", "text", "is_user"),
            "thread_id = ?",
            arrayOf(threadId),
            null,
            null,
            "seq ASC, created_at ASC"
        ).use { c ->
            while (c.moveToNext()) {
                result += ChatMessage(
                    id = c.getString(0),
                    sender = c.getString(1),
                    text = c.getString(2),
                    isUser = c.getInt(3) != 0
                )
            }
        }
        return result
    }

    @Synchronized
    fun appendMessage(threadId: String, message: ChatMessage) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val nextSeq = db.rawQuery(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM messages WHERE thread_id = ?",
                arrayOf(threadId)
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 1L }

            val values = ContentValues().apply {
                put("id", message.id)
                put("thread_id", threadId)
                put("sender", message.sender)
                put("text", message.text)
                put("is_user", if (message.isUser) 1 else 0)
                put("created_at", now)
                put("seq", nextSeq)
            }
            db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)

            val threadUpdate = ContentValues().apply {
                put("updated_at", now)
            }
            if (message.isUser) {
                val currentTitle = db.rawQuery(
                    "SELECT title FROM threads WHERE id = ?",
                    arrayOf(threadId)
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (currentTitle == DEFAULT_TITLE) {
                    val derived = deriveTitle(message.text)
                    if (derived.isNotBlank()) {
                        threadUpdate.put("title", derived)
                    }
                }
            }
            db.update("threads", threadUpdate, "id = ?", arrayOf(threadId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun updateMessageText(threadId: String, messageId: String, newText: String) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("text", newText) }
            db.update("messages", values, "id = ? AND thread_id = ?", arrayOf(messageId, threadId))
            val threadUpdate = ContentValues().apply { put("updated_at", now) }
            db.update("threads", threadUpdate, "id = ?", arrayOf(threadId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun loadAgentHistory(threadId: String): List<LlmChatMessage> {
        val result = mutableListOf<LlmChatMessage>()
        readableDatabase.query(
            "agent_history",
            arrayOf("role", "content"),
            "thread_id = ?",
            arrayOf(threadId),
            null,
            null,
            "id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                result += LlmChatMessage(
                    role = c.getString(0),
                    content = c.getString(1)
                )
            }
        }
        return result
    }

    @Synchronized
    fun appendAgentHistory(threadId: String, message: LlmChatMessage) {
        if (message.role == "system") return
        val values = ContentValues().apply {
            put("thread_id", threadId)
            put("role", message.role)
            put("content", message.content)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("agent_history", null, values)
    }

    @Synchronized
    fun recordAudit(
        threadId: String,
        command: String,
        decision: String,
        risk: String? = null,
        exitCode: Int? = null,
        sandboxMode: String? = null,
        summary: String? = null
    ) {
        val values = ContentValues().apply {
            put("thread_id", threadId)
            put("command", command)
            put("decision", decision)
            if (risk != null) put("risk", risk) else putNull("risk")
            if (exitCode != null) put("exit_code", exitCode) else putNull("exit_code")
            if (sandboxMode != null) put("sandbox_mode", sandboxMode) else putNull("sandbox_mode")
            if (summary != null) put("summary", summary.take(MAX_AUDIT_SUMMARY_CHARS)) else putNull("summary")
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("audit_log", null, values)
    }

    @Synchronized
    fun listAuditLogs(threadId: String? = null, limit: Int = 100): List<AuditEntry> {
        val result = mutableListOf<AuditEntry>()
        val selection = if (threadId != null) "thread_id = ?" else null
        val selectionArgs = if (threadId != null) arrayOf(threadId) else null
        readableDatabase.query(
            "audit_log",
            arrayOf("id", "thread_id", "command", "decision", "risk", "exit_code", "sandbox_mode", "summary", "created_at"),
            selection,
            selectionArgs,
            null,
            null,
            "created_at DESC, id DESC",
            limit.coerceIn(1, 500).toString()
        ).use { c ->
            while (c.moveToNext()) {
                result += AuditEntry(
                    id = c.getLong(0),
                    threadId = c.getString(1),
                    command = c.getString(2),
                    decision = c.getString(3),
                    risk = if (c.isNull(4)) null else c.getString(4),
                    exitCode = if (c.isNull(5)) null else c.getInt(5),
                    sandboxMode = if (c.isNull(6)) null else c.getString(6),
                    summary = if (c.isNull(7)) null else c.getString(7),
                    createdAt = c.getLong(8)
                )
            }
        }
        return result
    }

    @Synchronized
    fun exportAuditJson(threadId: String? = null): String {
        val entries = listAuditLogs(threadId = threadId, limit = 500)
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("thread_id", e.threadId)
                    put("timestamp", formatIsoUtc(e.createdAt))
                    put("command", e.command)
                    put("decision", e.decision)
                    if (e.risk != null) put("risk", e.risk)
                    if (e.exitCode != null) put("exit_code", e.exitCode)
                    if (e.sandboxMode != null) put("sandbox_mode", e.sandboxMode)
                    if (e.summary != null) put("summary", e.summary)
                }
            )
        }
        return JSONObject().apply {
            put("schema_version", 1)
            put("exported_at", formatIsoUtc(System.currentTimeMillis()))
            if (threadId != null) put("thread_id", threadId)
            put("count", entries.size)
            put("entries", arr)
        }.toString(2)
    }

    private fun deriveTitle(raw: String): String {
        val singleLine = raw.trim().replace(Regex("\\s+"), " ")
        return if (singleLine.length <= MAX_TITLE_CHARS) {
            singleLine
        } else {
            singleLine.take(MAX_TITLE_CHARS - 1).trimEnd() + "…"
        }
    }

    private fun formatIsoUtc(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochMs))
    }

    companion object {
        const val DB_NAME = "openbot_threads.db"
        const val DB_VERSION = 1
        const val DEFAULT_TITLE = "New Thread"
        private const val MAX_TITLE_CHARS = 42
        private const val MAX_AUDIT_SUMMARY_CHARS = 1000
    }
}
