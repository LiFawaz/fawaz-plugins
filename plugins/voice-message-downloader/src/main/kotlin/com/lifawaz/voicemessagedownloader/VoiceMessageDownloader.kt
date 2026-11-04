@file:JvmName("VoiceMessageDownloader")

package com.lifawaz.voicemessagedownloader

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import app.valenced.plugins.plugin
import app.valenced.xposed.api.registerNativeAsyncMethod
import app.valenced.xposed.api.registerNativeMethod
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val PLUGIN_ID = "com.lifawaz.voice-message-downloader"
private const val PICK_FOLDER_REQUEST = 0x564D
private const val MAX_AUDIO_BYTES = 50L * 1024L * 1024L
private const val TREE_URI_FILE = "android-save-tree-uri"
private const val TREE_NAME_FILE = "android-save-tree-name"

private val pendingPicker = AtomicReference<CancellableContinuation<String?>?>(null)

@Volatile
private var appContext: Context? = null

@Volatile
private var pluginStorage: File? = null

@Volatile
private var activityResultHook: XC_MethodHook.Unhook? = null

@Suppress("unused")
val voiceMessageDownloader = plugin {
    start {
        val storage = storageDir.apply { mkdirs() }
        pluginStorage = storage

        withAppContext { context ->
            appContext = context.applicationContext
        }

        activityResultHook?.unhook()
        activityResultHook = installFolderPickerResultHook(storage)

        registerNativeAsyncMethod(PLUGIN_ID + ".chooseFolder") {
            suspendCancellableCoroutine { continuation ->
                if (!pendingPicker.compareAndSet(null, continuation)) {
                    continuation.resumeWith(Result.failure(IllegalStateException("The Android folder picker is already open.")))
                    return@suspendCancellableCoroutine
                }

                continuation.invokeOnCancellation {
                    pendingPicker.compareAndSet(continuation, null)
                }

                withAppActivity { activity ->
                    activity.runOnUiThread {
                        if (!continuation.isActive) return@runOnUiThread

                        try {
                            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                addFlags(flags)
                            }

                            activity.startActivityForResult(intent, PICK_FOLDER_REQUEST)
                        } catch (error: Throwable) {
                            pendingPicker.compareAndSet(continuation, null)
                            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                        }
                    }
                }
            }
        }

        registerNativeMethod(PLUGIN_ID + ".getFolder") {
            getSavedFolderName()
        }

        registerNativeAsyncMethod(PLUGIN_ID + ".save") { args ->
            val url = args.getOrNull(0) as? String
                ?: throw IllegalArgumentException("The Discord voice-message URL is missing.")
            val fileName = args.getOrNull(1) as? String
                ?: throw IllegalArgumentException("The voice-message file name is missing.")
            val contentType = args.getOrNull(2) as? String ?: "audio/ogg"

            saveVoiceMessage(url, fileName, contentType)
        }
    }

    stop {
        pendingPicker.getAndSet(null)?.resumeWith(
            Result.failure(IllegalStateException("Voice Message Downloader was stopped."))
        )
        activityResultHook?.unhook()
        activityResultHook = null
        pluginStorage = null
        appContext = null
    }
}

private fun installFolderPickerResultHook(storage: File): XC_MethodHook.Unhook {
    val method = Activity::class.java.getDeclaredMethod(
        "onActivityResult",
        Int::class.javaPrimitiveType!!,
        Int::class.javaPrimitiveType!!,
        Intent::class.java
    ).apply {
        isAccessible = true
    }

    return XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val requestCode = param.args.getOrNull(0) as? Int ?: return
            if (requestCode != PICK_FOLDER_REQUEST) return

            val continuation = pendingPicker.getAndSet(null) ?: return
            val activity = param.thisObject as? Activity
            val resultCode = param.args.getOrNull(1) as? Int ?: Activity.RESULT_CANCELED
            val resultIntent = param.args.getOrNull(2) as? Intent
            val selectedUri = resultIntent?.data

            if (activity == null || resultCode != Activity.RESULT_OK || selectedUri == null) {
                continuation.resumeWith(Result.success(null))
                return
            }

            try {
                val grantedFlags = resultIntent.flags and
                    (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                if ((grantedFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                    throw SecurityException("Android did not grant write access to the selected folder. Choose another folder.")
                }

                activity.contentResolver.takePersistableUriPermission(selectedUri, grantedFlags)

                val folderName = queryTreeDisplayName(activity.contentResolver, selectedUri)
                    ?: selectedUri.lastPathSegment?.substringAfter(':')?.takeIf { it.isNotBlank() }
                    ?: "Selected folder"

                writeStoredValue(File(storage, TREE_URI_FILE), selectedUri.toString())
                writeStoredValue(File(storage, TREE_NAME_FILE), folderName)

                continuation.resumeWith(Result.success(folderName))
            } catch (error: Throwable) {
                continuation.resumeWith(Result.failure(error))
            }
        }
    })
}

private suspend fun saveVoiceMessage(
    url: String,
    requestedName: String,
    requestedContentType: String
): Map<String, String> = withContext(Dispatchers.IO) {
    val context = appContext ?: throw IllegalStateException("Android storage is not ready. Reopen Discord and try again.")
    val storage = pluginStorage ?: throw IllegalStateException("Plugin storage is not available.")
    val resolver = context.contentResolver

    if (!isAllowedDiscordAttachment(url)) {
        throw IllegalArgumentException("The attachment URL is not a supported Discord CDN URL.")
    }

    val treeUri = savedTreeUri(context, storage)
        ?: throw IllegalStateException("No Android save folder is selected. Open Configure and choose a folder.")

    val folderName = readStoredValue(File(storage, TREE_NAME_FILE)) ?: "Selected folder"
    val rootDocumentUri = try {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
    } catch (error: Throwable) {
        throw IllegalStateException("The saved Android folder is no longer available. Choose a new folder in Configure.", error)
    }

    val safeName = safeFileName(requestedName)
    val connection = URL(url).openConnection() as? HttpURLConnection
        ?: throw IllegalStateException("Could not open the Discord attachment connection.")

    connection.connectTimeout = 20_000
    connection.readTimeout = 45_000
    connection.instanceFollowRedirects = true
    var createdFile: Uri? = null

    try {
        val status = connection.responseCode
        if (status !in 200..299) {
            throw IllegalStateException(
                "Discord's attachment server returned HTTP " + status + " " +
                    (connection.responseMessage ?: "") + ". The voice-message link may have expired."
            )
        }

        if (!isAllowedDiscordAttachment(connection.url.toString())) {
            throw SecurityException("Discord redirected the attachment to an unsupported host.")
        }

        val contentLength = connection.contentLengthLong
        if (contentLength > MAX_AUDIO_BYTES) {
            throw IllegalArgumentException("The voice message exceeds the 50 MB download limit.")
        }

        val remoteType = connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (remoteType.isNotEmpty() && !remoteType.startsWith("audio/") && remoteType != "application/octet-stream") {
            throw IllegalArgumentException("Discord returned an unexpected file type: " + remoteType)
        }

        val mimeType = requestedContentType.substringBefore(';').trim().takeIf { it.startsWith("audio/") }
            ?: remoteType.takeIf { it.startsWith("audio/") }
            ?: "application/octet-stream"

        val targetUri = DocumentsContract.createDocument(
            resolver,
            rootDocumentUri,
            mimeType,
            safeName
        ) ?: throw IllegalStateException("Android could not create the audio file in the selected folder.")

        createdFile = targetUri

        try {
            val source = connection.inputStream
            val destination = resolver.openOutputStream(targetUri, "w")
                ?: throw IllegalStateException("Android could not open the new audio file for writing.")

            var totalBytes = 0L
            source.use { input ->
                destination.use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        totalBytes += count
                        if (totalBytes > MAX_AUDIO_BYTES) {
                            throw IllegalArgumentException("The voice message exceeds the 50 MB download limit.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }

            if (totalBytes == 0L) {
                throw IllegalStateException("Discord returned an empty voice-message file.")
            }
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, targetUri) }
            createdFile = null
            throw error
        }

        mapOf("filename" to safeName, "folderName" to folderName)
    } finally {
        connection.disconnect()
        if (createdFile != null) {
            // The completed audio file remains in the user's selected folder.
        }
    }
}

private fun getSavedFolderName(): String? {
    val context = appContext ?: return null
    val storage = pluginStorage ?: return null
    val uri = savedTreeUri(context, storage) ?: return null

    return readStoredValue(File(storage, TREE_NAME_FILE))
        ?: queryTreeDisplayName(context.contentResolver, uri)
        ?: "Selected folder"
}

private fun savedTreeUri(context: Context, storage: File): Uri? {
    val raw = readStoredValue(File(storage, TREE_URI_FILE)) ?: return null
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    val hasWritePermission = context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isWritePermission
    }

    if (!hasWritePermission) {
        File(storage, TREE_URI_FILE).delete()
        File(storage, TREE_NAME_FILE).delete()
        return null
    }

    return uri
}

private fun queryTreeDisplayName(resolver: ContentResolver, treeUri: Uri): String? {
    return runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)

        resolver.query(documentUri, projection, null, null, null)?.use { cursor: Cursor ->
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun readStoredValue(file: File): String? =
    runCatching { file.takeIf { it.isFile }?.readText()?.trim()?.takeIf(String::isNotEmpty) }.getOrNull()

private fun writeStoredValue(file: File, value: String) {
    file.parentFile?.mkdirs()
    val temporary = File(file.parentFile, file.name + ".tmp")
    temporary.writeText(value)
    if (!temporary.renameTo(file)) {
        file.writeText(value)
        temporary.delete()
    }
}

private fun safeFileName(value: String): String {
    val cleaned = value
        .replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_")
        .trim()
        .take(140)

    return cleaned.ifBlank { "voice-message.ogg" }
}

private fun isAllowedDiscordAttachment(value: String): Boolean {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
    val hostAllowed = uri.host == "cdn.discordapp.com" || uri.host == "media.discordapp.net"
    val pathAllowed = uri.path?.startsWith("/attachments/") == true ||
        uri.path?.startsWith("/ephemeral-attachments/") == true

    return uri.scheme == "https" && hostAllowed && pathAllowed
}
