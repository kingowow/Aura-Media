package org.akkirrai.hibiki.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import org.akkirrai.hibiki.R
import org.akkirrai.hibiki.core.subtitle.SubtitleArchiveListing
import org.akkirrai.hibiki.core.subtitle.SubtitleSearchResult

/**
 * The language options the search bar's filter can hold. The value is the language code passed to
 * [org.akkirrai.hibiki.core.subtitle.PersianSubtitleSearcher] ("fa" for Persian, "all" for every
 * language); the label is a string resource ID.
 */
private val SUBTITLE_LANGUAGE_FILTERS = listOf(
    "fa" to R.string.subtitle_search_language_persian,
    "en" to R.string.subtitle_search_language_english,
    "zh" to R.string.subtitle_search_language_chinese,
    "ja" to R.string.subtitle_search_language_japanese,
    "ko" to R.string.subtitle_search_language_korean,
    "ar" to R.string.subtitle_search_language_arabic,
    "all" to R.string.subtitle_search_language_all,
)

/** The subtitle-search drawer. */
@Composable
internal fun SubtitleSearchSheet(
    state: SubtitleSearchState,
    initialQuery: String,
    purpose: SubtitleSearchPurpose,
    onSearch: (String, String) -> Unit,
    onSelectResult: (SubtitleSearchResult) -> Unit,
    onSelectEntry: (SubtitleArchiveListing, String) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    // "fa" = Persian only, "all" = every language.
    var languageFilter by remember { mutableStateOf("fa") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val listing = (state as? SubtitleSearchState.Archive)?.listing

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank() && state is SubtitleSearchState.Idle) {
            onSearch(initialQuery, languageFilter)
        }
    }

    LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank()) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (listing != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.subtitle_search_back),
                        tint = Color.White,
                    )
                }
            } else {
                gap(8.dp)
            }
            Text(
                text = stringResource(
                    when {
                        listing != null -> R.string.subtitle_search_archive_title
                        purpose == SubtitleSearchPurpose.ForTranslation -> R.string.subtitle_search_translation_title
                        else -> R.string.subtitle_search_title
                    },
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (listing == null) {
            SubtitleSearchBar(
                query = query,
                languageFilter = languageFilter,
                onQueryChange = { query = it },
                onLanguageChange = { languageFilter = it },
                onSearch = {
                    keyboard?.hide()
                    onSearch(query, languageFilter)
                },
                focusRequester = focusRequester,
            )
        }

        Box(modifier = Modifier.heightIn(max = SUBTITLE_SEARCH_LIST_MAX_HEIGHT)) {
            when (state) {
                is SubtitleSearchState.Idle -> {
                    SubtitleSearchHint(initialQuery)
                }
                is SubtitleSearchState.Searching -> {
                    SubtitleSearchLoading()
                }
                is SubtitleSearchState.Failed -> {
                    SubtitleSearchEmpty(
                        text = stringResource(R.string.subtitle_search_failed),
                    )
                }
                is SubtitleSearchState.LoadingArchive -> {
                    SubtitleSearchLoading()
                }
                is SubtitleSearchState.Applying -> {
                    SubtitleSearchLoading()
                }
                is SubtitleSearchState.Results -> {
                    if (state.results.isEmpty()) {
                        SubtitleSearchEmpty(
                            text = stringResource(R.string.subtitle_search_no_results),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(
                                start = 8.dp,
                                end = 8.dp,
                                bottom = 12.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(state.results, key = { it.pageUrl }) { result ->
                                SubtitleSearchResultRow(
                                    result = result,
                                    onClick = { onSelectResult(result) },
                                )
                            }
                        }
                    }
                }
                is SubtitleSearchState.Archive -> {
                    val archive = state.listing
                    if (archive.entries.isEmpty()) {
                        SubtitleSearchEmpty(
                            text = stringResource(R.string.subtitle_search_no_results),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(
                                start = 8.dp,
                                end = 8.dp,
                                bottom = 12.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            itemsIndexed(archive.entries, key = { index, _ -> index }) { _, entry ->
                                SubtitleArchiveEntryRow(
                                    name = entry.name,
                                    size = entry.size,
                                    onClick = { onSelectEntry(archive, entry.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleSearchBar(
    query: String,
    languageFilter: String,
    onQueryChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
) {
    var languageMenuExpanded by remember { mutableStateOf(false) }
    val languageLabel = stringResource(
        SUBTITLE_LANGUAGE_FILTERS
            .firstOrNull { it.first == languageFilter }
            ?.second
            ?: R.string.subtitle_search_language_all,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Color.White.copy(alpha = 0.6f),
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.subtitle_search_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                cursorBrush = Brush.verticalGradient(listOf(Color.White, Color.White)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
        }
        // Language filter dropdown: فارسی / انگلیسی / چینی / ژاپنی / کره‌ای / عربی / همه زبان‌ها.
        Box {
            TextButton(onClick = { languageMenuExpanded = true }) {
                Text(
                    text = languageLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            DropdownMenu(
                expanded = languageMenuExpanded,
                onDismissRequest = { languageMenuExpanded = false },
            ) {
                SUBTITLE_LANGUAGE_FILTERS.forEach { (code, labelRes) ->
                    val selected = languageFilter == code
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) Color(0xFF7CB7FF) else Color.White,
                            )
                        },
                        leadingIcon = {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFF7CB7FF),
                                )
                            }
                        },
                        onClick = {
                            onLanguageChange(code)
                            languageMenuExpanded = false
                        },
                    )
                }
            }
        }
        if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.subtitle_search_clear),
                    modifier = Modifier.size(16.dp),
                    tint = Color.White.copy(alpha = 0.6f),
                )
            }
        }
        // Search button — text, no custom icon.
        TextButton(onClick = onSearch) {
            Text(
                text = stringResource(R.string.subtitle_search_action),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun SubtitleSearchResultRow(
    result: SubtitleSearchResult,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(
                    result.source,
                    result.language?.takeIf { it.isNotBlank() },
                    result.releaseNotes?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (result.openInBrowser) {
                    Text(
                        text = stringResource(R.string.subtitle_search_open_in_browser),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7CB7FF),
                        maxLines = 1,
                    )
                }
            }
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.White.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun SubtitleArchiveEntryRow(
    name: String,
    size: Long,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.White.copy(alpha = 0.55f),
            )
            Text(
                text = name.substringAfterLast('/'),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (size > 0) {
                Text(
                    text = formatSubtitleFileSize(size),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.45f),
                )
            }
        }
    }
}

@Composable
private fun SubtitleSearchLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 2.dp,
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun SubtitleSearchEmpty(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun SubtitleSearchHint(initialQuery: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (initialQuery.isBlank()) {
                stringResource(R.string.subtitle_search_hint_empty)
            } else {
                stringResource(R.string.subtitle_search_hint_ready, initialQuery)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
    }
}

private fun formatSubtitleFileSize(size: Long): String = when {
    size >= 1_048_576 -> "%.1f MB".format(size / 1_048_576.0)
    size >= 1024 -> "%.0f KB".format(size / 1024.0)
    else -> "$size B"
}

private val SUBTITLE_SEARCH_LIST_MAX_HEIGHT = 320.dp

@Composable
private fun gap(dp: Dp) = androidx.compose.foundation.layout.Spacer(Modifier.size(dp))
