package com.example.automation

import com.example.data.NotificationConfig
import com.example.data.NotifyLevel
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class RemoteHttpRequest(
    val url: String,
    val contentType: String,
    val body: ByteArray
)

data class RemoteHttpResponse(
    val statusCode: Int,
    val body: String
)

fun interface RemoteHttpTransport {
    fun post(request: RemoteHttpRequest): RemoteHttpResponse
}

data class RemoteDispatchResult(
    val telegramAttempted: Boolean = false,
    val telegramOk: Boolean = false,
    val telegramError: String = "",
    val discordAttempted: Boolean = false,
    val discordOk: Boolean = false,
    val discordError: String = ""
) {
    val anyAttempted: Boolean get() = telegramAttempted || discordAttempted
    val anySucceeded: Boolean get() = telegramOk || discordOk

    fun summaryMessage(): String {
        if (!anyAttempted) return "Belum ada Telegram Bot atau Discord Webhook yang aktif."
        val parts = ArrayList<String>(2)
        if (telegramAttempted) {
            parts += if (telegramOk) "Telegram: ✅ Terkirim" else "Telegram: ❌ ${telegramError.ifBlank { "Gagal" }}"
        }
        if (discordAttempted) {
            parts += if (discordOk) "Discord: ✅ Terkirim" else "Discord: ❌ ${discordError.ifBlank { "Gagal" }}"
        }
        return parts.joinToString(" • ")
    }
}

/**
 * Pure-Kotlin lightweight HTTP dispatcher for Telegram Bot API (`sendMessage` / `sendPhoto`)
 * and Discord Webhooks (JSON embed / multipart embed + JPEG screenshot).
 * Zero third-party HTTP dependencies; uses standard [HttpURLConnection] by default and accepts
 * an injectable [RemoteHttpTransport] for deterministic unit tests.
 */
object RemoteNotifier {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 12_000

    val defaultTransport = RemoteHttpTransport { req ->
        val conn = (URL(req.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", req.contentType)
            setRequestProperty("User-Agent", "WjustMacro/2.20")
        }
        try {
            conn.outputStream.use { os ->
                os.write(req.body)
                os.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            RemoteHttpResponse(code, respText.take(300))
        } finally {
            conn.disconnect()
        }
    }

    fun isValidDiscordWebhook(rawUrl: String): Boolean {
        val u = rawUrl.trim()
        return u.startsWith("https://discord.com/api/webhooks/", ignoreCase = true) ||
            u.startsWith("https://discordapp.com/api/webhooks/", ignoreCase = true) ||
            u.startsWith("https://ptb.discord.com/api/webhooks/", ignoreCase = true) ||
            u.startsWith("https://canary.discord.com/api/webhooks/", ignoreCase = true)
    }

    /** Sends notification to all enabled & configured remote channels (Telegram and/or Discord). Never throws. */
    fun send(
        config: NotificationConfig,
        title: String,
        message: String,
        level: NotifyLevel = NotifyLevel.INFO,
        jpegBytes: ByteArray? = null,
        transport: RemoteHttpTransport = defaultTransport
    ): RemoteDispatchResult {
        var tgAttempted = false
        var tgOk = false
        var tgErr = ""
        var dcAttempted = false
        var dcOk = false
        var dcErr = ""

        if (config.telegramEnabled) {
            tgAttempted = true
            val token = config.telegramBotToken.trim()
            val chatId = config.telegramChatId.trim()
            if (token.isBlank() || chatId.isBlank()) {
                tgErr = "Bot Token atau Chat ID kosong"
            } else {
                runCatching {
                    val req = if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                        buildTelegramPhotoRequest(token, chatId, title, message, level, jpegBytes)
                    } else {
                        buildTelegramMessageRequest(token, chatId, title, message, level)
                    }
                    val resp = transport.post(req)
                    if (resp.statusCode in 200..299) {
                        tgOk = true
                    } else {
                        tgErr = "HTTP ${resp.statusCode}"
                    }
                }.onFailure { err ->
                    tgErr = err.message?.take(80) ?: err::class.java.simpleName
                }
            }
        }

        if (config.discordEnabled) {
            dcAttempted = true
            val url = config.discordWebhookUrl.trim()
            if (!isValidDiscordWebhook(url)) {
                dcErr = "URL Webhook Discord tidak valid"
            } else {
                runCatching {
                    val req = if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                        buildDiscordPhotoRequest(url, title, message, level, jpegBytes)
                    } else {
                        buildDiscordJsonRequest(url, title, message, level)
                    }
                    val resp = transport.post(req)
                    if (resp.statusCode in 200..299) {
                        dcOk = true
                    } else {
                        dcErr = "HTTP ${resp.statusCode}"
                    }
                }.onFailure { err ->
                    dcErr = err.message?.take(80) ?: err::class.java.simpleName
                }
            }
        }

        return RemoteDispatchResult(
            telegramAttempted = tgAttempted,
            telegramOk = tgOk,
            telegramError = tgErr,
            discordAttempted = dcAttempted,
            discordOk = dcOk,
            discordError = dcErr
        )
    }

    fun sendTest(
        config: NotificationConfig,
        appName: String = "Wjust macro",
        transport: RemoteHttpTransport = defaultTransport
    ): RemoteDispatchResult = send(
        config = config,
        title = "Tes Koneksi $appName",
        message = "Notifikasi jarak jauh berhasil terhubung! Siap mengirim laporan farming, alert anti-macet, dan screenshot layar.",
        level = NotifyLevel.SUCCESS,
        jpegBytes = null,
        transport = transport
    )

    internal fun buildTelegramMessageRequest(
        token: String,
        chatId: String,
        title: String,
        message: String,
        level: NotifyLevel
    ): RemoteHttpRequest {
        val fullText = formatPlainBody(title, message, level).take(3900)
        val payload = """{"chat_id":${jsonQuote(chatId)},"text":${jsonQuote(fullText)}}"""
        return RemoteHttpRequest(
            url = "https://api.telegram.org/bot$token/sendMessage",
            contentType = "application/json; charset=UTF-8",
            body = payload.toByteArray(Charsets.UTF_8)
        )
    }

    internal fun buildTelegramPhotoRequest(
        token: String,
        chatId: String,
        title: String,
        message: String,
        level: NotifyLevel,
        jpegBytes: ByteArray
    ): RemoteHttpRequest {
        val boundary = "----WjustMacroBoundary${System.nanoTime()}"
        val caption = formatPlainBody(title, message, level).take(980)
        val baos = ByteArrayOutputStream(jpegBytes.size + 1024)
        val out = DataOutputStream(baos)

        writeFormField(out, boundary, "chat_id", chatId)
        writeFormField(out, boundary, "caption", caption)
        writeFilePart(out, boundary, "photo", "screen.jpg", "image/jpeg", jpegBytes)
        out.writeBytes("--$boundary--\r\n")
        out.flush()

        return RemoteHttpRequest(
            url = "https://api.telegram.org/bot$token/sendPhoto",
            contentType = "multipart/form-data; boundary=$boundary",
            body = baos.toByteArray()
        )
    }

    internal fun buildDiscordJsonRequest(
        webhookUrl: String,
        title: String,
        message: String,
        level: NotifyLevel
    ): RemoteHttpRequest {
        val payload = buildDiscordEmbedJson(title, message, level, withAttachment = false)
        return RemoteHttpRequest(
            url = webhookUrl,
            contentType = "application/json; charset=UTF-8",
            body = payload.toByteArray(Charsets.UTF_8)
        )
    }

    internal fun buildDiscordPhotoRequest(
        webhookUrl: String,
        title: String,
        message: String,
        level: NotifyLevel,
        jpegBytes: ByteArray
    ): RemoteHttpRequest {
        val boundary = "----WjustDiscordBoundary${System.nanoTime()}"
        val payloadJson = buildDiscordEmbedJson(title, message, level, withAttachment = true)
        val baos = ByteArrayOutputStream(jpegBytes.size + 1536)
        val out = DataOutputStream(baos)

        writeFormField(out, boundary, "payload_json", payloadJson)
        writeFilePart(out, boundary, "files[0]", "screen.jpg", "image/jpeg", jpegBytes)
        out.writeBytes("--$boundary--\r\n")
        out.flush()

        return RemoteHttpRequest(
            url = webhookUrl,
            contentType = "multipart/form-data; boundary=$boundary",
            body = baos.toByteArray()
        )
    }

    private fun buildDiscordEmbedJson(
        title: String,
        message: String,
        level: NotifyLevel,
        withAttachment: Boolean
    ): String {
        val colorInt = when (level) {
            NotifyLevel.INFO -> 0x0EA5E9
            NotifyLevel.SUCCESS -> 0x10B981
            NotifyLevel.WARNING -> 0xF59E0B
            NotifyLevel.CRITICAL -> 0xEF4444
        }
        val cleanTitle = "${level.icon} ${title.ifBlank { "Wjust macro" }}".take(240)
        val cleanDesc = message.ifBlank { "-" }.take(3800)
        val imgPart = if (withAttachment) ""","image":{"url":"attachment://screen.jpg"}""" else ""
        return """{"username":"Wjust macro","embeds":[{"title":${jsonQuote(cleanTitle)},"description":${jsonQuote(cleanDesc)},"color":$colorInt$imgPart,"footer":{"text":"Wjust macro • Remote Monitor"}}]}"""
    }

    private fun formatPlainBody(title: String, message: String, level: NotifyLevel): String = buildString {
        append(level.icon).append(' ')
        append(title.ifBlank { "Wjust macro" })
        if (message.isNotBlank()) {
            append("\n").append(message)
        }
    }

    private fun writeFormField(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n")
        out.writeBytes("Content-Type: text/plain; charset=UTF-8\r\n\r\n")
        out.write(value.toByteArray(Charsets.UTF_8))
        out.writeBytes("\r\n")
    }

    private fun writeFilePart(
        out: DataOutputStream,
        boundary: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n")
        out.writeBytes("Content-Type: $mimeType\r\n\r\n")
        out.write(bytes)
        out.writeBytes("\r\n")
    }

    private fun jsonQuote(raw: String): String = buildString(raw.length + 2) {
        append('"')
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append(String.format("\\u%04x", ch.code))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }
}
