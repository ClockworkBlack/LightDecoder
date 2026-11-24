package com.lightdecoder.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * manifest.json, the first entry of a backup's ZIP: what made it, when, and what it holds.
 * [schemaVersion] is the highest archive schema version in it (a restore refuses a newer one
 * than this app's); each archive also has its own.
 */
data class BackupManifest(
    val format: Int = FORMAT,
    val appVersionCode: Int,
    val appVersionName: String,
    val createdAtMs: Long,
    val schemaVersion: Int,
    val archives: List<Archive>,
    /** Media files left out because they were larger than [ChatBackup.MAX_MEDIA_FILE_BYTES]. */
    val skippedTooLarge: Int = 0,
    /** Media files the archive names that were not on the phone (never downloaded, or deleted). */
    val missingFiles: Int = 0,
) {
    data class Archive(
        /** [com.lightdecoder.chat.ChatService.key]. */
        val service: String,
        /** Its database file name, and its ZIP entry under "db/". */
        val file: String,
        val schemaVersion: Int,
        val conversations: Int,
        val messages: Int,
        val files: Int,
    )

    val conversations: Int get() = archives.sumOf { it.conversations }
    val messages: Int get() = archives.sumOf { it.messages }
    val files: Int get() = archives.sumOf { it.files }

    fun toJson(): String = JSONObject().apply {
        put("format", format)
        put("appVersionCode", appVersionCode)
        put("appVersionName", appVersionName)
        put("createdAt", createdAtMs)
        put("schemaVersion", schemaVersion)
        put("conversations", conversations)
        put("messages", messages)
        put("files", files)
        put("skippedTooLarge", skippedTooLarge)
        put("missingFiles", missingFiles)
        put("archives", JSONArray().apply {
            archives.forEach {
                put(JSONObject().apply {
                    put("service", it.service)
                    put("file", it.file)
                    put("schemaVersion", it.schemaVersion)
                    put("conversations", it.conversations)
                    put("messages", it.messages)
                    put("files", it.files)
                })
            }
        })
    }.toString(2)

    companion object {
        const val FORMAT = 1

        /** Reads [json]; anything malformed is [BackupException.Reason.INVALID_CONTENT]. */
        fun parse(json: String): BackupManifest = try {
            val o = JSONObject(json)
            val list = o.getJSONArray("archives")
            BackupManifest(
                format = o.getInt("format"),
                appVersionCode = o.optInt("appVersionCode", 0),
                appVersionName = o.optString("appVersionName", ""),
                createdAtMs = o.optLong("createdAt", 0),
                schemaVersion = o.getInt("schemaVersion"),
                skippedTooLarge = o.optInt("skippedTooLarge", 0),
                missingFiles = o.optInt("missingFiles", 0),
                archives = (0 until list.length()).map { i ->
                    val a = list.getJSONObject(i)
                    Archive(
                        service = a.getString("service"),
                        file = a.getString("file"),
                        schemaVersion = a.getInt("schemaVersion"),
                        conversations = a.optInt("conversations", 0),
                        messages = a.optInt("messages", 0),
                        files = a.optInt("files", 0),
                    )
                },
            )
        } catch (e: JSONException) {
            throw BackupException(BackupException.Reason.INVALID_CONTENT, "manifest", e)
        }
    }
}
