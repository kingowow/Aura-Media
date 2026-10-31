package org.akkirrai.hibiki.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.akkirrai.hibiki.R
import org.akkirrai.hibiki.app.settings.LocalAppLanguage
import org.akkirrai.hibiki.app.settings.LocalizedAppContext
import org.akkirrai.hibiki.core.update.AppUpdate

@Composable
fun AppUpdateDialog(
    update: AppUpdate,
    downloadProgress: Float?,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    // The dialog floats above every screen, so it does not inherit the app's language from the
    // screen below it: without this wrapper its texts follow the system language instead of the
    // one the user picked in Settings (the progress line read "Downloading: 42%" in a Persian
    // app). The provider has to sit *inside* the dialog's own content, because a dialog is a
    // separate window with its own context.
    val appLanguage = LocalAppLanguage.current
    AlertDialog(
        onDismissRequest = { if (downloadProgress == null) onLater() },
        title = {
            LocalizedAppContext(languageMode = appLanguage) {
                AppUpdateDialogTitle()
            }
        },
        text = {
            LocalizedAppContext(languageMode = appLanguage) {
                AppUpdateDialogBody(
                    update = update,
                    downloadProgress = downloadProgress,
                )
            }
        },
        confirmButton = {
            LocalizedAppContext(languageMode = appLanguage) {
                Button(onClick = onUpdate, enabled = downloadProgress == null) {
                    Text(
                        stringResource(
                            if (update.isDownloaded) R.string.update_action_install else R.string.update_action_download,
                        ),
                    )
                }
            }
        },
        dismissButton = {
            LocalizedAppContext(languageMode = appLanguage) {
                TextButton(onClick = onLater, enabled = downloadProgress == null) {
                    Text(stringResource(R.string.update_action_later))
                }
            }
        },
    )
}

@Composable
private fun AppUpdateDialogTitle() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                imageVector = Icons.Outlined.Update,
                contentDescription = null,
                modifier = Modifier.padding(8.dp),
            )
        }
        Text(
            text = stringResource(R.string.update_available_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AppUpdateDialogBody(
    update: AppUpdate,
    downloadProgress: Float?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        UpdateInfoPill(
            version = update.version,
            size = formatFileSize(update.apkSizeBytes),
        )
        if (downloadProgress != null) {
            LinearProgressIndicator(
                progress = { downloadProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.update_downloading, (downloadProgress * 100).toInt()))
        }
        if (update.notes.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.update_whats_new),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = update.notes.trim(),
                    modifier = Modifier.heightIn(max = 210.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UpdateInfoPill(
    version: String,
    size: String,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.update_version_label, version),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(8.dp, 1.dp))
            Text(
                text = size,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val mib = bytes / (1024.0 * 1024.0)
    return "%.1f MB".format(java.util.Locale.getDefault(), mib)
}
