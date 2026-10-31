package org.akkirrai.hibiki.feature.settings

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import org.akkirrai.hibiki.core.design.component.AppModalBottomSheet
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import org.akkirrai.hibiki.R
import org.akkirrai.hibiki.BuildConfig
import org.akkirrai.hibiki.app.settings.AppPreferences
import org.akkirrai.hibiki.app.settings.LanguageMode
import org.akkirrai.hibiki.app.settings.LocalAppLanguage
import org.akkirrai.hibiki.app.settings.LocalAppPreferences
import org.akkirrai.hibiki.app.settings.LocalAppPreferencesState
import org.akkirrai.hibiki.app.settings.LocalizedAppContext
import org.akkirrai.hibiki.core.subtitle.SubtitleTranslator
import org.akkirrai.hibiki.core.subtitle.SubtitleTranslatorShared
import org.akkirrai.hibiki.core.subtitle.SubtitleTranslationTarget
import org.akkirrai.hibiki.core.subtitle.LlmProviderPresets
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import org.akkirrai.hibiki.core.subtitle.TranslationEngine
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.HorizontalDivider
import org.akkirrai.hibiki.app.settings.NotificationPermissionState
import org.akkirrai.hibiki.app.settings.ThemeMode
import org.akkirrai.hibiki.core.log.AppLogger
import org.akkirrai.hibiki.core.log.PerfLogger
import org.akkirrai.hibiki.core.source.AnimeSourceRegistry
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    bottomContentPadding: Dp = 24.dp,
    onCheckForUpdates: () -> Unit = {},
    onConfigureNotifications: () -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val haptic = LocalHapticFeedback.current
    val appPreferences = LocalAppPreferences.current
    val preferences = LocalAppPreferencesState.current
    var isLanguageDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isSubtitleTargetDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isSubtitleEngineDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isAniListSyncOpen by rememberSaveable { mutableStateOf(false) }
    // The offer to move a library off retired scripted sources stays reachable from here, but only while
    // such titles are still there.
    val hasLegacyLibrary by produceState(false, context) {
        value = runCatching {
            org.akkirrai.hibiki.core.migration.LegacyLibraryMigration(context).scan().isNotEmpty()
        }.getOrDefault(false)
    }
    val versionName = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    LaunchedEffect(Unit) {
        PerfLogger.mark("SettingsScreen composed")
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 18.dp,
                top = 84.dp,
                end = 18.dp,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {

        item(key = "appearance") {
            SettingsSection(title = stringResource(R.string.settings_appearance)) {
                SettingsItems(count = 3) { index, shape ->
                    when (index) {
                        0 -> SettingsVerticalItem(
                            icon = Icons.Outlined.DarkMode,
                            title = stringResource(R.string.settings_theme),
                            shape = shape,
                        ) {
                            SettingsSegmentedControl(
                                options = listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM),
                                selectedOption = preferences.themeMode,
                                label = ::themeModeLabel,
                                onSelect = { mode ->
                                    appPreferences.setThemeMode(mode)
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                },
                            )
                        }

                        1 -> SettingsSwitchItem(
                            icon = Icons.Outlined.Palette,
                            title = stringResource(R.string.settings_use_system_color_scheme),
                            checked = preferences.useSystemColorScheme,
                            shape = shape,
                            onCheckedChange = appPreferences::setUseSystemColorScheme,
                        )

                        2 -> SettingsSwitchItem(
                            icon = Icons.Outlined.Contrast,
                            title = stringResource(R.string.settings_amoled),
                            checked = preferences.useAmoledTheme,
                            shape = shape,
                            onCheckedChange = appPreferences::setUseAmoledTheme,
                        )
                    }
                }
            }
        }

        item(key = "preferences") {
            SettingsSection(title = stringResource(R.string.settings_preferences)) {
                SettingsItems(count = 4) { index, shape ->
                    when (index) {
                        0 -> SettingsActionItem(
                            icon = Icons.Outlined.Translate,
                            title = stringResource(R.string.settings_language),
                            subtitle = languageModeLabel(preferences.languageMode),
                            shape = shape,
                            showNavigationArrow = true,
                            onClick = { isLanguageDialogOpen = true },
                        )

                        1 -> SettingsActionItem(
                            icon = Icons.Outlined.Notifications,
                            title = stringResource(R.string.settings_notifications),
                            subtitle = notificationPermissionLabel(preferences.notificationPermissionState),
                            shape = shape,
                            showNavigationArrow = true,
                            onClick = onConfigureNotifications,
                        )

                        2 -> SettingsActionItem(
                            icon = Icons.Outlined.Subtitles,
                            title = stringResource(R.string.settings_subtitle_translation_target),
                            subtitle = preferences.subtitleTranslationTarget.label,
                            shape = shape,
                            showNavigationArrow = true,
                            onClick = { isSubtitleTargetDialogOpen = true },
                        )

                        3 -> SettingsActionItem(
                            icon = Icons.Outlined.Translate,
                            title = stringResource(R.string.settings_subtitle_translation_engine),
                            subtitle = preferences.subtitleTranslationEngine.displayName,
                            shape = shape,
                            showNavigationArrow = true,
                            onClick = { isSubtitleEngineDialogOpen = true },
                        )
                    }
                }
            }
        }

        item(key = "sources") {
            SettingsSection(title = stringResource(R.string.settings_sources)) {
                SettingsItems(count = if (hasLegacyLibrary) 3 else 2) { index, shape ->
                    when (index) {
                        0 -> SettingsSwitchItem(
                            icon = Icons.Filled.VisibilityOff,
                            title = stringResource(R.string.settings_hide_nsfw_sources),
                            checked = preferences.hideNsfwSources,
                            shape = shape,
                            onCheckedChange = appPreferences::setHideNsfwSources,
                        )

                        2 -> SettingsActionItem(
                            icon = Icons.Outlined.SwapHoriz,
                            title = stringResource(R.string.migration_settings_title),
                            subtitle = stringResource(R.string.migration_settings_summary),
                            shape = shape,
                            showNavigationArrow = true,
                            onClick = { org.akkirrai.hibiki.feature.migration.LegacyMigrationRequests.reopen() },
                        )

                        1 -> SettingsActionItem(
                            icon = Icons.Outlined.Sync,
                            title = stringResource(R.string.settings_anilist_sync),
                            subtitle = stringResource(R.string.settings_anilist_sync_summary),
                            shape = shape,
                            showNavigationArrow = true,
                            onClick = { isAniListSyncOpen = true },
                        )
                    }
                }
            }
        }

        item(key = "player") {
            SettingsSection(title = stringResource(R.string.settings_player)) {
                SettingsItems(count = 4) { index, shape ->
                    when (index) {
                        0 -> SettingsSwitchItem(
                            icon = Icons.Outlined.FastForward,
                            title = stringResource(R.string.settings_auto_skip_segments),
                            checked = preferences.autoSkipSegments,
                            shape = shape,
                            onCheckedChange = appPreferences::setAutoSkipSegments,
                        )
                        1 -> SettingsSliderItem(
                            icon = Icons.Outlined.Timer,
                            title = stringResource(R.string.settings_auto_skip_delay),
                            hint = stringResource(R.string.settings_auto_skip_delay_hint),
                            value = preferences.autoSkipDelaySeconds,
                            valueRange = AppPreferences.SKIP_TIMER_MIN_SECONDS..AppPreferences.SKIP_TIMER_MAX_SECONDS,
                            valueLabel = { current -> stringResource(R.string.settings_seconds_value, current) },
                            shape = shape,
                            onValueChange = appPreferences::setAutoSkipDelaySeconds,
                        )
                        2 -> SettingsSliderItem(
                            icon = Icons.Outlined.TouchApp,
                            title = stringResource(R.string.settings_skip_button_timeout),
                            hint = stringResource(R.string.settings_skip_button_timeout_hint),
                            value = preferences.skipButtonTimeoutSeconds,
                            valueRange = AppPreferences.SKIP_TIMER_MIN_SECONDS..AppPreferences.SKIP_TIMER_MAX_SECONDS,
                            valueLabel = { current -> stringResource(R.string.settings_seconds_value, current) },
                            shape = shape,
                            onValueChange = appPreferences::setSkipButtonTimeoutSeconds,
                        )
                        else -> SettingsSliderItem(
                            icon = Icons.Outlined.CheckCircle,
                            title = stringResource(R.string.settings_watched_threshold),
                            hint = stringResource(R.string.settings_watched_threshold_hint),
                            value = preferences.watchedThresholdPercent,
                            valueRange = AppPreferences.WATCHED_THRESHOLD_MIN_PERCENT..AppPreferences.WATCHED_THRESHOLD_MAX_PERCENT,
                            valueLabel = { current -> stringResource(R.string.settings_percent_value, current) },
                            shape = shape,
                            onValueChange = appPreferences::setWatchedThresholdPercent,
                        )
                    }
                }
            }
        }

        item(key = "updates") {
            SettingsSection(title = stringResource(R.string.settings_updates)) {
                SettingsItems(count = 1) { _, _ ->
                    SettingsActionItem(
                        icon = Icons.Filled.Refresh,
                        title = stringResource(R.string.settings_check_updates),
                        shape = CircleShape,
                        onClick = onCheckForUpdates,
                    )
                }
            }
        }

        item(key = "support") {
            SettingsSection(title = stringResource(R.string.settings_support)) {
                SettingsItems(count = 1) { _, _ ->
                    SettingsActionItem(
                        icon = Icons.Outlined.Share,
                        title = stringResource(R.string.settings_export_logs),
                        shape = CircleShape,
                        onClick = {
                            AppLogger.shareLogs(context).onFailure {
                                Toast.makeText(
                                    context,
                                    R.string.settings_export_logs_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
            }
        }

        item(key = "about") {
            SettingsSection(title = stringResource(R.string.settings_about)) {
                SettingsAboutItem(
                    versionName = versionName,
                    onGitHubClick = { uriHandler.openUri(HIBIKI_GITHUB_URL) },
                )
            }
        }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .height(56.dp),
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 18.dp)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_title),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }

    if (isAniListSyncOpen) {
        AniListSyncDialog(
            defaultSourceId = preferences.animeSource.value,
            onDismiss = { isAniListSyncOpen = false },
        )
    }

    if (isLanguageDialogOpen) {
        LanguageSelectionDialog(
            selectedLanguage = preferences.languageMode,
            onSelect = { mode ->
                appPreferences.setLanguageMode(mode)
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            },
            onDismiss = { isLanguageDialogOpen = false },
        )
    }

    if (isSubtitleTargetDialogOpen) {
        SubtitleTranslationTargetDialog(
            selectedTarget = preferences.subtitleTranslationTarget,
            onSelect = {
                appPreferences.setSubtitleTranslationTarget(it)
                isSubtitleTargetDialogOpen = false
            },
            onDismiss = { isSubtitleTargetDialogOpen = false },
        )
    }

    if (isSubtitleEngineDialogOpen) {
        SubtitleTranslationEngineDialog(
            selectedEngine = preferences.subtitleTranslationEngine,
            apiKey = preferences.subtitleTranslationApiKey,
            apiUrl = preferences.subtitleTranslationApiUrl,
            apiRegion = preferences.subtitleTranslationApiRegion,
            apiModel = preferences.subtitleTranslationApiModel,
            onSelectEngine = { appPreferences.setSubtitleTranslationEngine(it) },
            onApiKeyChange = { appPreferences.setSubtitleTranslationApiKey(it) },
            onApiUrlChange = { appPreferences.setSubtitleTranslationApiUrl(it) },
            onApiRegionChange = { appPreferences.setSubtitleTranslationApiRegion(it) },
            onApiModelChange = { appPreferences.setSubtitleTranslationApiModel(it) },
            onDismiss = { isSubtitleEngineDialogOpen = false },
        )
    }

}

@Composable
private fun LanguageSelectionDialog(
    selectedLanguage: LanguageMode,
    onSelect: (LanguageMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val appLanguage = LocalAppLanguage.current
    Dialog(onDismissRequest = onDismiss) {
        LocalizedAppContext(languageMode = appLanguage) {
            Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_language),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Column(Modifier.padding(top = 12.dp).selectableGroup()) {
                        listOf(LanguageMode.SYSTEM, LanguageMode.PERSIAN, LanguageMode.ENGLISH).forEach { mode ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .selectable(
                                        selected = mode == selectedLanguage,
                                        role = Role.RadioButton,
                                        onClick = { onSelect(mode) },
                                    )
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                RadioButton(selected = mode == selectedLanguage, onClick = null)
                                Text(languageModeLabel(mode), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(end = 16.dp)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}

/**
 * Lets the viewer point "translate subtitle" at a different language.
 *
 * The list is flat and each language is spelled in its own script ("فارسی", not "Persian"), so
 * the row stays findable whichever app language is active - the same convention every language
 * picker uses.
 */
@Composable
private fun TestAndModelsSection(
    engine: TranslationEngine,
    apiKey: String,
    apiUrl: String,
    apiRegion: String,
    apiModel: String,
    onApiKeyChange: (String) -> Unit,
    onApiUrlChange: (String) -> Unit,
    onApiRegionChange: (String) -> Unit,
    onApiModelChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isTesting by remember { mutableStateOf(false) }
    var testResult: String? by remember { mutableStateOf(null) }
    var modelList: List<String> by remember { mutableStateOf(emptyList()) }
    var isListingModels by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Quick-pick provider chips for LLM engine
        if (engine == TranslationEngine.LLM) {
            Text(
                text = stringResource(R.string.subtitle_engine_llm_provider),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LlmProviderPresets.all.forEach { preset ->
                    val isSelected = apiUrl == preset.url
                    Surface(
                        onClick = {
                            onApiUrlChange(preset.url)
                            onApiModelChange(preset.model)
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // Model input (LLM + Qwen-MT) with "fetch list" button
        if (engine.requiresModel) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = apiModel,
                    onValueChange = onApiModelChange,
                    label = { Text(stringResource(R.string.subtitle_engine_model)) },
                    placeholder = { Text(engine.modelHint, maxLines = 1) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                // "Fetch model list" button — only shown for LLM engine with a URL
                if (engine == TranslationEngine.LLM && apiUrl.isNotBlank()) {
                    TextButton(onClick = {
                        isListingModels = true
                        modelList = emptyList()
                        scope.launch {
                            val list = SubtitleTranslatorShared.get().listModels(apiUrl, apiKey)
                            modelList = list
                            isListingModels = false
                        }
                    }, modifier = Modifier.padding(start = 4.dp)) {
                        if (isListingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.subtitle_engine_list_models),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
            // Show fetched model list as tappable chips
            if (modelList.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.subtitle_engine_available_models),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    modelList.forEach { model ->
                        val isSelected = apiModel == model
                        Surface(
                            onClick = { onApiModelChange(model) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Text(
                                text = model,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        // API key
        if (engine.requiresApiKey) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text(stringResource(R.string.subtitle_engine_api_key)) },
                placeholder = { Text(engine.keyHint, maxLines = 1) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Custom URL (DeepLX + LLM)
        if (engine.requiresUrl) {
            OutlinedTextField(
                value = apiUrl,
                onValueChange = onApiUrlChange,
                label = { Text(stringResource(R.string.subtitle_engine_api_url)) },
                placeholder = { Text(engine.urlHint, maxLines = 1) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Region (Azure)
        if (engine.requiresRegion) {
            OutlinedTextField(
                value = apiRegion,
                onValueChange = onApiRegionChange,
                label = { Text(stringResource(R.string.subtitle_engine_api_region)) },
                placeholder = { Text("eastasia", maxLines = 1) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Test button — available for all engines
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    testResult = null
                    isTesting = true
                    scope.launch {
                        testResult = try {
                            SubtitleTranslatorShared.get().testEndpoint(
                                target = SubtitleTranslationTarget.PERSIAN,
                                engine = engine,
                                apiKey = apiKey,
                                apiUrl = apiUrl,
                                apiRegion = apiRegion,
                                apiModel = apiModel,
                            )
                        } catch (e: Exception) {
                            "FAIL: ${e.message ?: e.javaClass.simpleName}"
                        }
                        isTesting = false
                    }
                },
                enabled = !isTesting,
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Text(
                    stringResource(R.string.subtitle_engine_test),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        // Test result
        testResult?.let { result ->
            val isSuccess = !result.startsWith("FAIL")
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSuccess)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else
                    MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            ) {
                Text(
                    text = (if (isSuccess) stringResource(R.string.subtitle_engine_test_ok) else stringResource(R.string.subtitle_engine_test_fail)) + ": " + result,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(10.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


@Composable
private fun SubtitleTranslationEngineDialog(
    selectedEngine: TranslationEngine,
    apiKey: String,
    apiUrl: String,
    apiRegion: String,
    apiModel: String,
    onSelectEngine: (TranslationEngine) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onApiUrlChange: (String) -> Unit,
    onApiRegionChange: (String) -> Unit,
    onApiModelChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val appLanguage = LocalAppLanguage.current
    Dialog(onDismissRequest = onDismiss) {
        LocalizedAppContext(languageMode = appLanguage) {
            Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_subtitle_translation_engine),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    // Engine selector rows
                    Column(Modifier.padding(top = 12.dp).selectableGroup()) {
                        TranslationEngine.entries.forEach { engine ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = engine == selectedEngine,
                                        role = Role.RadioButton,
                                        onClick = { onSelectEngine(engine) },
                                    )
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                RadioButton(selected = engine == selectedEngine, onClick = null)
                                Column {
                                    Text(engine.displayName, style = MaterialTheme.typography.bodyLarge)
                                    if (engine.isFree) {
                                        Text(
                                            text = stringResource(R.string.subtitle_engine_free),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // API key / URL / region / model fields + test + list models
                    if (selectedEngine.requiresApiKey || selectedEngine.requiresUrl ||
                        selectedEngine.requiresRegion || selectedEngine.requiresModel) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                        TestAndModelsSection(
                            engine = selectedEngine,
                            apiKey = apiKey,
                            apiUrl = apiUrl,
                            apiRegion = apiRegion,
                            apiModel = apiModel,
                            onApiKeyChange = onApiKeyChange,
                            onApiUrlChange = onApiUrlChange,
                            onApiRegionChange = onApiRegionChange,
                            onApiModelChange = onApiModelChange,
                        )
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(end = 16.dp, top = 8.dp),
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleTranslationTargetDialog(
    selectedTarget: SubtitleTranslationTarget,
    onSelect: (SubtitleTranslationTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val appLanguage = LocalAppLanguage.current
    Dialog(onDismissRequest = onDismiss) {
        LocalizedAppContext(languageMode = appLanguage) {
            Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_subtitle_translation_target),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Column(Modifier.padding(top = 12.dp).selectableGroup()) {
                        SubtitleTranslationTarget.entries.forEach { target ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .selectable(
                                        selected = target == selectedTarget,
                                        role = Role.RadioButton,
                                        onClick = { onSelect(target) },
                                    )
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                RadioButton(selected = target == selectedTarget, onClick = null)
                                Text(target.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(end = 16.dp)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun notificationPermissionLabel(state: NotificationPermissionState): String = stringResource(
    when (state) {
        NotificationPermissionState.NOT_ASKED -> R.string.settings_notifications_not_asked
        NotificationPermissionState.GRANTED -> R.string.settings_notifications_enabled
        NotificationPermissionState.DENIED -> R.string.settings_notifications_disabled
    },
)

@Composable
internal fun SettingsSheetGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        content()
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        content()
    }
}

@Composable
internal fun SettingsItems(
    count: Int,
    content: @Composable ColumnScope.(index: Int, shape: Shape) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { index ->
            content(index, settingsItemShape(index, count))
        }
    }
}

private fun settingsItemShape(index: Int, count: Int): Shape {
    if (count == 1) return RoundedCornerShape(24.dp)
    return RoundedCornerShape(
        topStart = if (index == 0) 24.dp else 4.dp,
        topEnd = if (index == 0) 24.dp else 4.dp,
        bottomStart = if (index == count - 1) 24.dp else 4.dp,
        bottomEnd = if (index == count - 1) 24.dp else 4.dp,
    )
}

@Composable
private fun SettingsVerticalItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    shape: Shape,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsItemHeader(icon = icon, title = title)
        content()
    }
}

// Enough for the widest label any of these sliders can show ("100%" / "30 s").
private val SETTINGS_SLIDER_VALUE_WIDTH = 48.dp

@Composable
private fun SettingsSliderItem(
    icon: ImageVector,
    title: String,
    hint: String,
    value: Int,
    valueRange: IntRange,
    valueLabel: @Composable (Int) -> String,
    shape: Shape,
    onValueChange: (Int) -> Unit,
) {
    // The slider drives a local value while it is being dragged and only writes to preferences on
    // release: every intermediate step would otherwise be a SharedPreferences commit that comes
    // back through the state flow and recomposes the whole settings screen mid-gesture.
    var draggedValue by remember(value) { mutableStateOf<Int?>(null) }
    val shownValue = draggedValue ?: value
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = valueLabel(shownValue),
                // Fixed width, end-aligned: the label's own width otherwise changes with the digits
                // it happens to be showing (7 s vs 8 s vs 10 s are all different widths in this
                // font), which moves the wrap point of the hint next to it - so the whole card grew
                // and shrank by a line as the slider passed certain values.
                modifier = Modifier.width(SETTINGS_SLIDER_VALUE_WIDTH),
                textAlign = TextAlign.End,
                maxLines = 1,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = shownValue.toFloat(),
            onValueChange = { draggedValue = it.roundToInt() },
            onValueChangeFinished = { draggedValue?.let(onValueChange) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
            // No tick marks: 30 steps' worth of dots reads as noise on a track this wide, and the
            // exact value is already spelled out next to the title. The value stays integral -
            // onValueChange rounds it - the slider just doesn't draw the stops.
            steps = 0,
        )
    }
}

@Composable
internal fun SettingsSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String? = null,
    checked: Boolean,
    shape: Shape,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    SettingsItemRow(
        icon = icon,
        shape = shape,
        onClick = {
            onCheckedChange(!checked)
            haptic.performHapticFeedback(
                if (checked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
            )
        },
        trailing = {
            SettingsSwitch(
                checked = checked,
                onCheckedChange = {
                    onCheckedChange(it)
                    haptic.performHapticFeedback(
                        if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                    )
                },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    shape: Shape,
    showNavigationArrow: Boolean = false,
    onClick: () -> Unit,
) {
    SettingsItemRow(
        icon = icon,
        shape = shape,
        onClick = onClick,
        trailing = if (showNavigationArrow) {
            {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SettingsItemRow(
    icon: ImageVector,
    shape: Shape,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
        trailing?.invoke()
    }
}

@Composable
private fun SettingsItemHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
        )
    }
}

@Composable
private fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        thumbContent = if (checked) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun <T> SettingsSegmentedControl(
    options: List<T>,
    selectedOption: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = option == selectedOption
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(
                        if (selected) {
                            CircleShape
                        } else {
                            segmentShape(index, options.lastIndex)
                        },
                    )
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.background
                        },
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    modifier = Modifier.fillMaxWidth(),
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun segmentShape(index: Int, lastIndex: Int): Shape {
    return when (index) {
        0 -> RoundedCornerShape(
            topStart = 16.dp,
            bottomStart = 16.dp,
            topEnd = 6.dp,
            bottomEnd = 6.dp,
        )

        lastIndex -> RoundedCornerShape(
            topStart = 6.dp,
            bottomStart = 6.dp,
            topEnd = 16.dp,
            bottomEnd = 16.dp,
        )

        else -> RoundedCornerShape(6.dp)
    }
}

@Composable
private fun SettingsAboutItem(
    versionName: String,
    onGitHubClick: () -> Unit,
) {
    val context = LocalContext.current
    val appIcon = remember(context) {
        context.packageManager
            .getApplicationIcon(context.packageName)
            .toBitmap(config = Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }
    val isDarkMode = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val background = if (isDarkMode) Color(0x190FFF66) else Color(0x59FFC0CB)
    val textColor = if (isDarkMode) Color(0xFF3BFF84) else Color(0xFFDA6482)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        ) {
        Image(
            bitmap = appIcon,
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
            )
            Text(
                text = "v$versionName",
                style = MaterialTheme.typography.labelMedium,
                color = textColor.copy(alpha = 0.75f),
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(onClick = onGitHubClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = stringResource(R.string.settings_github),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun languageModeLabel(mode: LanguageMode): String {
    return stringResource(
        when (mode) {
            LanguageMode.SYSTEM -> R.string.settings_language_system
            LanguageMode.PERSIAN -> R.string.settings_language_persian
            LanguageMode.ENGLISH -> R.string.settings_language_english
        },
    )
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String {
    return stringResource(
        when (mode) {
            ThemeMode.SYSTEM -> R.string.settings_theme_system
            ThemeMode.LIGHT -> R.string.settings_theme_light
            ThemeMode.DARK -> R.string.settings_theme_dark
        },
    )
}

private const val HIBIKI_GITHUB_URL = "https://github.com/kingowow/Aura-Media"
