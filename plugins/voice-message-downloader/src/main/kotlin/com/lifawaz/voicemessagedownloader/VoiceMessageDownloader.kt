@file:JvmName("VoiceMessageDownloader")
package com.lifawaz.voicemessagedownloader

import android.app.Activity
import android.app.AlertDialog
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
private const val CREATE_FILE = 0x564E
private const val OPEN_FILE = 0x564F
private const val MAX_AUDIO_BYTES = 50L * 1024L * 1024L
private const val FOLDER_URI = "android-default-folder-uri"
private const val FOLDER_NAME = "android-default-folder-name"

private data class Destination(val uri: Uri, val name: String, val newFile: Boolean)
private data class SavedFile(val name: String, val replaced: Boolean)

private val pendingFolder = AtomicReference<CancellableContinuation<String?>?>(null)
private val pendingFile = AtomicReference<CancellableContinuation<Destination?>?>(null)

@Volatile private var hostScope: HostScope? = null
@Volatile private var appContext: Context? = null
@Volatile private var pluginStorage: File? = null
@Volatile private var activityResultHook: XC_MethodHook.Unhook? = null

@Suppress("unused")
val voiceMessageDownloader = plugin {
    start {
        hostScope = this
        pluginStorage = storageDir.apply { mkdirs() }
        withAppContext { appContext = it.applicationContext }

        activityResultHook?.unhook()
        activityResultHook = installResultHook()

        registerNativeAsyncMethod(PLUGIN_ID + ".chooseFolder") { chooseDefaultFolder() }
        registerNativeAsyncMethod(PLUGIN_ID + ".getFolder") { getSavedFolderName() }

        registerNativeAsyncMethod(PLUGIN_ID + ".saveWithPicker") { args ->
            val url = args.getOrNull(0) as? String
                ?: throw IllegalArgumentException("The Discord voice-message URL is missing.")
            val filename = args.getOrNull(1) as? String
                ?: throw IllegalArgumentException("The suggested filename is missing.")
            val contentType = args.getOrNull(2) as? String ?: "audio/ogg"

            val target = chooseDestination(filename, contentType)
                ?: return@registerNativeAsyncMethod mapOf("cancelled" to true)

            val saved = downloadToDestination(url, filename, contentType, target)
            mapOf(
                "cancelled" to false,
                "filename" to saved.name,
                "replaced" to saved.replaced
            )
        }
    }

    stop {
        pendingFolder.getAndSet(null)?.resumeWith(Result.success(null))
        pendingFile.getAndSet(null)?.resumeWith(Result.success(null))
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
                            savedTreeUri()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
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

private suspend fun chooseDestination(filename: String, contentType: String): Destination? =
    suspendCancellableCoroutine { continuation ->
        if (!pendingFile.compareAndSet(null, continuation)) {
            continuation.resumeWith(Result.failure(IllegalStateException("The Android file picker is already open.")))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { pendingFile.compareAndSet(continuation, null) }

        try {
            withCurrentActivity { activity ->
                activity.runOnUiThread {
                    if (!continuation.isActive) return@runOnUiThread
                    try {
                        AlertDialog.Builder(activity)
                            .setTitle("Save voice message")
                            .setMessage("Create a new audio file, or choose an existing audio file to replace.")
                            .setPositiveButton("Create new file") { _, _ ->
                                launchFilePicker(activity, CREATE_FILE, filename, contentType, continuation)
                            }
                            .setNegativeButton("Choose existing file") { _, _ ->
                                launchFilePicker(activity, OPEN_FILE, filename, contentType, continuation)
                            }
                            .setNeutralButton("Cancel") { _, _ -> finishFile(continuation, null) }
                            .setOnCancelListener { finishFile(continuation, null) }
                            .show()
                    } catch (error: Throwable) {
                        finishFile(continuation, error = error)
                    }
                }
            }
        } catch (error: Throwable) {
            finishFile(continuation, error = error)
        }
    }

private fun launchFilePicker(
    activity: Activity,
    requestCode: Int,
    filename: String,
    contentType: String,
    continuation: CancellableContinuation<Destination?>
) {
    if (!continuation.isActive) return
    try {
        val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val initialFolder = savedTreeUri()
        val intent = if (requestCode == CREATE_FILE) {
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = contentType.substringBefore(';').trim().takeIf { it.startsWith("audio/") }
                    ?: "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, filename)
                if (initialFolder != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialFolder)
                addFlags(grantFlags)
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                if (initialFolder != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialFolder)
                addFlags(grantFlags)
            }
        }
        activity.startActivityForResult(intent, requestCode)
    } catch (error: Throwable) {
        finishFile(continuation, error = error)
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

            when (code) {
                PICK_FOLDER -> onFolderResult(activity, result, intent, uri)
                CREATE_FILE -> onFileResult(activity, result, intent, uri, true)
                OPEN_FILE -> onFileResult(activity, result, intent, uri, false)
            }
        }
    })
}

private fun onFolderResult(activity: Activity, result: Int, intent: Intent?, uri: Uri?) {
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
        val oldUri = savedTreeUri()
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

private fun onFileResult(
    activity: Activity,
    result: Int,
    intent: Intent?,
    uri: Uri?,
    isNew: Boolean
) {
    val continuation = pendingFile.get() ?: return
    if (result != Activity.RESULT_OK || intent == null || uri == null) {
        finishFile(continuation, null)
        return
    }
    try {
        val grants = intent.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if ((grants and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
            throw SecurityException("Android did not grant write access to that file. Choose a writable audio file.")
        }

        val resolver = activity.contentResolver
        val name = queryDocumentString(resolver, uri, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            ?: "Selected audio file"

        if (isNew) {
            finishFile(continuation, Destination(uri, name, true))
            return
        }

        val flags = queryDocumentFlags(resolver, uri)
        if (flags == null || (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) == 0) {
            throw SecurityException("The selected file provider does not allow replacing this file. Choose another audio file.")
        }

        activity.runOnUiThread {
            if (!continuation.isActive) return@runOnUiThread
            try {
                AlertDialog.Builder(activity)
                    .setTitle("Replace existing file?")
                    .setMessage("The contents of \"" + name + "\" will be replaced by this voice message. This cannot be undone.")
                    .setPositiveButton("Replace file") { _, _ ->
                        finishFile(continuation, Destination(uri, name, false))
                    }
                    .setNegativeButton("Cancel") { _, _ -> finishFile(continuation, null) }
                    .setOnCancelListener { finishFile(continuation, null) }
                    .show()
            } catch (error: Throwable) {
                finishFile(continuation, error = error)
            }
        }
    } catch (error: Throwable) {
        finishFile(continuation, error = error)
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

private fun finishFile(
    continuation: CancellableContinuation<Destination?>,
    value: Destination? = null,
    error: Throwable? = null
) {
    if (!pendingFile.compareAndSet(continuation, null) || !continuation.isActive) return
    if (error != null) continuation.resumeWith(Result.failure(error))
    else continuation.resumeWith(Result.success(value))
}

private suspend fun downloadToDestination(
    url: String,
    filename: String,
    contentType: String,
    destination: Destination
): SavedFile = withContext(Dispatchers.IO) {
    val context = appContext ?: throw IllegalStateException("Android storage is not ready. Reopen Discord and try again.")
    if (!isAllowedDiscordAttachment(url)) {
        throw IllegalArgumentException("The attachment URL is not a supported Discord CDN URL.")
    }

    val temp = File.createTempFile("voice-message-", ".audio", context.cacheDir)
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
            if (remoteType.isNotEmpty() && !remoteType.startsWith("audio/") &&
                remoteType != "application/octet-stream"
            ) {
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
                        if (total > MAX_AUDIO_BYTES) {
                            throw IllegalArgumentException("The voice message exceeds the 50 MB download limit.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
            if (total == 0L) throw IllegalStateException("Discord returned an empty voice-message file.")
        } finally {
            connection.disconnect()
        }

        try {
            val resolver = context.contentResolver
            val output = resolver.openOutputStream(destination.uri, "wt")
                ?: throw IllegalStateException("Android could not open the selected file for writing.")
            temp.inputStream().use { input ->
                output.use { stream ->
                    input.copyTo(stream, 16 * 1024)
                    stream.flush()
                }
            }
            val finalName = queryDocumentString(
                resolver, destination.uri, DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ) ?: destination.name
            SavedFile(finalName, !destination.newFile)
        } catch (error: Throwable) {
            if (destination.newFile) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination.uri) }
            }
            val message = if (destination.newFile) {
                "The new file was created, but Android could not write the audio into it."
            } else {
                "Android could not replace the selected file. The provider may not support writing to it."
            }
            throw IllegalStateException(message, error)
        }
    } finally {
        temp.delete()
    }
}

private fun getSavedFolderName(): String? {
    val storage = pluginStorage ?: return null
    val uri = savedTreeUri() ?: return null
    val context = appContext ?: return null
    return readStoredValue(File(storage, FOLDER_NAME))
        ?: queryTreeName(context.contentResolver, uri)
        ?: "Selected folder"
}

private fun savedTreeUri(): Uri? {
    val context = appContext ?: return null
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
