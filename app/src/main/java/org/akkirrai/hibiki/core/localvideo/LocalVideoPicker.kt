package org.akkirrai.hibiki.core.localvideo

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.akkirrai.hibiki.R

/**
 * Lets the user pick one video through the system document picker.
 *
 * The Storage Access Framework grants access to that single document only, so the app
 * never needs `READ_MEDIA_VIDEO` / `MANAGE_EXTERNAL_STORAGE`.
 *
 * @param onPicked receives the chosen `content://` URI and its display name.
 */
@Composable
fun rememberVideoPicker(onPicked: (uri: String, displayName: String) -> Unit): () -> Unit {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onPicked(uri.toString(), queryDisplayName(appContext, uri))
        }
    }
    return remember(launcher) {
        { launcher.launch(arrayOf("video/*")) }
    }
}

/**
 * Hands [uri] to whatever other app the user picks, using the system app chooser.
 *
 * This is the "open with another app" path: playback happens outside Hibiki and no
 * extra permission is needed because the SAF read grant travels with the URI.
 */
fun openVideoWithAnotherApp(context: Context, uri: Uri, errorMessage: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, context.getString(R.string.local_video_open_with))
    runCatching {
        context.startActivity(chooser)
    }.onFailure {
        Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                null
            }
        }
    }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()
}
