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
import app.valenced.xposed.api.HostScope
import app.valenced.xposed.api.registerNativeAsyncMethod
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
private const val PICK_FOLDER = 0x564D
private const val MAX_AUDIO_BYTES = 50L * 1024L * 1024L
private const val FOLDER_URI = "android-default-folder-uri"
private const val FOLDER_NAME = "android-default-folder-name"

private data class SavedFile(val name: String)

private val pendingFolder = AtomicReference<CancellableContinuation<String?>?>(null)

@Volatile private var hostScope: HostScope? = null
@Volatile private var appContext: Context? = null
@Volatile private var pluginStorage: File? = null
@Volatile private var activityResultHook: XC_MethodHook.Unhook? = null

@Suppress("unused")
val voiceMessageDownloader = plugin {
    start {
        hostScope = this
        pluginStorage = storageDir.apply { mkdirs() }
        withAppActivity { activity -> appContext = activity.applicationContext }

        activityResultHook?.unhook()
        activityResultHook = installResultHook()

        registerNativeAsyncMethod(PLUGIN_ID + ".chooseFolder") { chooseDefaultFolder() }
        registerNativeAsyncMethod(PLUGIN_ID + ".getFolder") { getSavedFolderName() }

        registerNativeAsyncMethod(PLUGIN_ID + ".saveToFolder") { args ->
            val url = args.getOrNull(0) as? String
                ?: throw IllegalArgumentException("The Discord voice-message URL is missing.")
            val filename = args.getOrNull(1) as? String
                ?: throw IllegalArgumentException("The suggested filename is missing.")
            val contentType = args.getOrNull(2) as? String ?: "audio/ogg"
            val saved = downloadToFolder(url, filename, contentType)
            mapOf("filename" to saved.name, "saved" to true)
        }
    }

    stop {
        pendingFolder.getAndSet(null)?.resumeWith(Result.success(null))
        activityResultHook?.unhook()
        activityResultHook = null
        pluginStorage = null
        appContext = null
        hostScope = null
    }
}

private fun withCurrentActivity(block: (Activity) -> Unit) {
    val scope = hostScope ?: throw IllegalStateException("Valenced activity is not available.")
    scope.withAppActivity(block)
}

private suspend fun chooseDefaultFolder(): String? =
    suspendCancellableCoroutine { continuation ->
        if (!pendingFolder.compareAndSet(null, continuation)) {
            continuation.resumeWith(Result.failure(IllegalStateException("The Android folder picker is already open.")))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { pendingFolder.compareAndSet(continuation, null) }

        try {
            withCurrentActivity { activity ->
                activity.runOnUiThread {
                    if (!continuation.isActive) return@runOnUiThread
                    try {
                        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(flags)
                            savedTreeUri(activity.applicationContext)?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
                        }
                        activity.startActivityForResult(intent, PICK_FOLDER)
                    } catch (error: Throwable) {
                        finishFolder(continuation, error = error)
                    }
                }
            }
        } catch (error: Throwable) {
            finishFolder(continuation, error = error)
        }
    }

private fun installResultHook(): XC_MethodHook.Unhook {
    val method = Activity::class.java.getDeclaredMethod(
        "onActivityResult",
        Int::class.javaPrimitiveType!!,
        Int::class.javaPrimitiveType!!,
        Intent::class.java
    ).apply { isAccessible = true }

    return XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val code = param.args.getOrNull(0) as? Int ?: return
            val activity = param.thisObject as? Activity ?: return
            val result = param.args.getOrNull(1) as? Int ?: Activity.RESULT_CANCELED
            val intent = param.args.getOrNull(2) as? Intent
            val uri = intent?.data

            if (code == PICK_FOLDER) onFolderResult(activity, result, intent, uri)
        }
    })
}

private fun onFolderResult(activity: Activity, result: Int, intent: Intent?, uri: Uri?) {
    appContext = activity.applicationContext
    val continuation = pendingFolder.get() ?: return
    if (result != Activity.RESULT_OK || intent == null || uri == null) {
        finishFolder(continuation, null)
        return
    }
    try {
        val flags = intent.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if ((flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
            throw SecurityException("Android did not grant write access to that folder. Choose another folder.")
        }

        val resolver = activity.contentResolver
        resolver.takePersistableUriPermission(uri, flags)
        val name = queryTreeName(resolver, uri)
            ?: uri.lastPathSegment?.substringAfter(':')?.takeIf { it.isNotBlank() }
            ?: "Selected folder"

        val storage = pluginStorage ?: throw IllegalStateException("Plugin storage is not available.")
        val oldUri = savedTreeUri(activity.applicationContext)
        if (oldUri != null && oldUri != uri) {
            runCatching {
                resolver.releasePersistableUriPermission(
                    oldUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        writeStoredValue(File(storage, FOLDER_URI), uri.toString())
        writeStoredValue(File(storage, FOLDER_NAME), name)
        finishFolder(continuation, name)
    } catch (error: Throwable) {
        finishFolder(continuation, error = error)
    }
}

private fun finishFolder(
    continuation: CancellableContinuation<String?>,
    value: String? = null,
    error: Throwable? = null
) {
    if (!pendingFolder.compareAndSet(continuation, null) || !continuation.isActive) return
    if (error != null) continuation.resumeWith(Result.failure(error))
    else continuation.resumeWith(Result.success(value))
}

private suspend fun requireAppContext(): Context {
    appContext?.let { return it }
    return suspendCancellableCoroutine { continuation ->
        try {
            withCurrentActivity { activity ->
                val context = activity.applicationContext
                appContext = context
                if (continuation.isActive) continuation.resumeWith(Result.success(context))
            }
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
    }
}

private suspend fun downloadToFolder(url: String, filename: String, contentType: String): SavedFile {
    val context = requireAppContext()
    val tree = savedTreeUri(context)
        ?: throw IllegalStateException("No save folder is configured. Open Voice Message Downloader → Configure → Choose or change folder first.")

    return withContext(Dispatchers.IO) {
        if (!isAllowedDiscordAttachment(url)) {
            throw IllegalArgumentException("The attachment URL is not a supported Discord CDN URL.")
        }

        val temp = File.createTempFile("voice-message-", ".audio", context.cacheDir)
        var createdDocument: Uri? = null
        try {
            val connection = URL(url).openConnection() as? HttpURLConnection
                ?: throw IllegalStateException("Could not open the Discord attachment connection.")
            connection.connectTimeout = 20_000
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = true
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IllegalStateException("Discord's attachment server returned HTTP $status. The link may have expired.")
                }
                if (!isAllowedDiscordAttachment(connection.url.toString())) {
                    throw SecurityException("Discord redirected the attachment to an unsupported host.")
                }
                if (connection.contentLengthLong > MAX_AUDIO_BYTES) {
                    throw IllegalArgumentException("The voice message exceeds the 50 MB download limit.")
                }
                val remoteType = connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                if (remoteType.isNotEmpty() && !remoteType.startsWith("audio/") && remoteType != "application/octet-stream") {
                    throw IllegalArgumentException("Discord returned an unexpected file type: $remoteType")
                }
                var total = 0L
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_AUDIO_BYTES) throw IllegalArgumentException("The voice message exceeds the 50 MB download limit.")
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }
                if (total == 0L) throw IllegalStateException("Discord returned an empty voice-message file.")
            } finally {
                connection.disconnect()
            }

            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val mime = contentType.substringBefore(';').trim().lowercase().takeIf { it.startsWith("audio/") } ?: "audio/ogg"
            val document = DocumentsContract.createDocument(resolver, parent, mime, filename)
                ?: throw IllegalStateException("Android could not create a file in the saved folder. Re-select the folder in Configure.")
            createdDocument = document
            val output = resolver.openOutputStream(document, "wt")
                ?: throw IllegalStateException("Android could not open the new voice-message file for writing.")
            temp.inputStream().use { input ->
                output.use { stream ->
                    input.copyTo(stream, 16 * 1024)
                    stream.flush()
                }
            }
            val savedName = queryDocumentString(resolver, document, DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: filename
            SavedFile(savedName)
        } catch (error: Throwable) {
            createdDocument?.let { uri -> runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
            throw error
        } finally {
            temp.delete()
        }
    }
}

private fun getSavedFolderName(): String? {
    val storage = pluginStorage ?: return null
    return readStoredValue(File(storage, FOLDER_NAME))
}

private fun savedTreeUri(context: Context): Uri? {
    val storage = pluginStorage ?: return null
    val raw = readStoredValue(File(storage, FOLDER_URI)) ?: return null
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    val granted = context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isWritePermission
    }
    if (!granted) {
        File(storage, FOLDER_URI).delete()
        File(storage, FOLDER_NAME).delete()
        return null
    }
    return uri
}

private fun queryTreeName(resolver: ContentResolver, tree: Uri): String? =
    runCatching {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        queryDocumentString(resolver, document, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    }.getOrNull()

private fun queryDocumentFlags(resolver: ContentResolver, uri: Uri): Int? =
    runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor: Cursor ->
            val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            if (cursor.moveToFirst() && column >= 0) cursor.getInt(column) else null
        }
    }.getOrNull()

private fun queryDocumentString(resolver: ContentResolver, uri: Uri, column: String): String? =
    runCatching {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor: Cursor ->
            val index = cursor.getColumnIndex(column)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

private fun readStoredValue(file: File): String? =
    runCatching { file.takeIf { it.isFile }?.readText()?.trim()?.takeIf(String::isNotEmpty) }.getOrNull()

private fun writeStoredValue(file: File, value: String) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, file.name + ".tmp")
    temp.writeText(value)
    if (!temp.renameTo(file)) {
        file.writeText(value)
        temp.delete()
    }
}

private fun isAllowedDiscordAttachment(value: String): Boolean {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
    val host = uri.host == "cdn.discordapp.com" || uri.host == "media.discordapp.net"
    val path = uri.path?.startsWith("/attachments/") == true ||
        uri.path?.startsWith("/ephemeral-attachments/") == true
    return uri.scheme == "https" && host && path
}
