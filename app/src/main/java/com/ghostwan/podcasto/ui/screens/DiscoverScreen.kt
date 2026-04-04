package com.ghostwan.podcasto.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.google.ai.client.generativeai.GenerativeModel
import com.ghostwan.podcasto.BuildConfig
import com.ghostwan.podcasto.R
import com.ghostwan.podcasto.data.local.PodcastEntity
import com.ghostwan.podcasto.data.remote.ITunesPodcast
import com.ghostwan.podcasto.data.remote.YouTubeExtractor
import com.ghostwan.podcasto.data.repository.PodcastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject

@Serializable
data class AiSuggestion(
    val name: String,
    val reason: String,
    val searchQuery: String,
)

@Serializable
data class AiDiscoverResponse(
    val suggestions: List<AiSuggestion> = emptyList(),
    val intro: String = "",
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val repository: PodcastRepository,
) : ViewModel() {

    private fun getGeminiApiKey(): String {
        val prefs = appContext.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val key = prefs.getString("gemini_api_key", null)
        if (!key.isNullOrBlank()) return key
        return BuildConfig.GEMINI_API_KEY
    }

    data class CountryOption(val code: String?, val labelResId: Int)

    val countryOptions = listOf(
        CountryOption(null, R.string.country_all),
        CountryOption("FR", R.string.country_fr),
        CountryOption("US", R.string.country_us),
        CountryOption("GB", R.string.country_gb),
        CountryOption("DE", R.string.country_de),
        CountryOption("ES", R.string.country_es),
        CountryOption("IT", R.string.country_it),
        CountryOption("BR", R.string.country_br),
        CountryOption("JP", R.string.country_jp),
    )

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCountry = MutableStateFlow<String?>(null)
    val selectedCountry: StateFlow<String?> = _selectedCountry.asStateFlow()

    private val _results = MutableStateFlow<List<ITunesPodcast>>(emptyList())
    val results: StateFlow<List<ITunesPodcast>> = _results.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val subscribedIds: StateFlow<Set<Long>> = repository.getSubscribedPodcasts()
        .map { podcasts -> podcasts.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // AI Discovery state (for empty screen suggestions based on library)
    private val _aiSuggestions = MutableStateFlow<List<AiSuggestion>>(emptyList())
    val aiSuggestions: StateFlow<List<AiSuggestion>> = _aiSuggestions.asStateFlow()

    private val _aiIntro = MutableStateFlow("")
    val aiIntro: StateFlow<String> = _aiIntro.asStateFlow()

    private val _aiLoading = MutableStateFlow(false)
    val aiLoading: StateFlow<Boolean> = _aiLoading.asStateFlow()

    private val _aiError = MutableStateFlow<String?>(null)
    val aiError: StateFlow<String?> = _aiError.asStateFlow()

    // AI Search state (suggestions based on search query)
    private val _aiSearchSuggestions = MutableStateFlow<List<AiSuggestion>>(emptyList())
    val aiSearchSuggestions: StateFlow<List<AiSuggestion>> = _aiSearchSuggestions.asStateFlow()

    private val _aiSearchLoading = MutableStateFlow(false)
    val aiSearchLoading: StateFlow<Boolean> = _aiSearchLoading.asStateFlow()

    private val _aiSearchEnabled = MutableStateFlow(true)
    val aiSearchEnabled: StateFlow<Boolean> = _aiSearchEnabled.asStateFlow()

    // YouTube preview state
    private val _youtubeLoading = MutableStateFlow(false)
    val youtubeLoading: StateFlow<Boolean> = _youtubeLoading.asStateFlow()

    private val _youtubePreview = MutableStateFlow<PodcastEntity?>(null)
    val youtubePreview: StateFlow<PodcastEntity?> = _youtubePreview.asStateFlow()

    private val _youtubeError = MutableStateFlow<String?>(null)
    val youtubeError: StateFlow<String?> = _youtubeError.asStateFlow()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    fun onQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onCountryChange(country: String?) {
        _selectedCountry.value = country
        if (_searchQuery.value.isNotBlank()) {
            search()
        }
    }

    fun toggleAiSearch(enabled: Boolean) {
        _aiSearchEnabled.value = enabled
    }

    fun search() {
        val query = _searchQuery.value.trim()
        if (query.isEmpty()) return

        // Check if query is a YouTube channel URL
        if (BuildConfig.YOUTUBE_ENABLED && YouTubeExtractor.isYouTubeChannelUrl(query)) {
            previewYouTubeChannel(query)
            return
        }

        // iTunes search
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _results.value = repository.searchPodcasts(query, _selectedCountry.value)
            } catch (e: Exception) {
                e.printStackTrace()
                _results.value = emptyList()
            }
            _isLoading.value = false
        }

        // AI search in parallel (only if enabled)
        if (_aiSearchEnabled.value) {
            searchWithAi(query)
        } else {
            _aiSearchSuggestions.value = emptyList()
            _aiSearchLoading.value = false
        }
    }

    private fun searchWithAi(query: String) {
        val apiKey = getGeminiApiKey()
        if (apiKey.isEmpty()) return

        viewModelScope.launch {
            _aiSearchLoading.value = true
            _aiSearchSuggestions.value = emptyList()
            try {
                val prompt = """Tu es un expert en podcasts. L'utilisateur cherche des podcasts en rapport avec : "$query"

Suggère 4 podcasts qui correspondent à cette recherche. Inclus des podcasts connus et de qualité, en français et en anglais. Réponds entièrement en français.

Réponds UNIQUEMENT avec un objet JSON valide dans ce format exact, sans markdown, sans blocs de code :
{"intro": "", "suggestions": [{"name": "Nom du Podcast", "reason": "Courte raison de la pertinence", "searchQuery": "requête exacte pour le trouver sur iTunes"}]}"""

                val response = withContext(Dispatchers.IO) {
                    val model = GenerativeModel(
                        modelName = "gemini-2.0-flash",
                        apiKey = apiKey,
                    )
                    model.generateContent(prompt)
                }

                val rawText = response.text?.trim() ?: ""
                val cleanJson = rawText
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val parsed = jsonParser.decodeFromString<AiDiscoverResponse>(cleanJson)
                _aiSearchSuggestions.value = parsed.suggestions
            } catch (e: Exception) {
                e.printStackTrace()
                _aiSearchSuggestions.value = emptyList()
            }
            _aiSearchLoading.value = false
        }
    }

    private fun previewYouTubeChannel(channelUrl: String) {
        viewModelScope.launch {
            _youtubeLoading.value = true
            _youtubePreview.value = null
            _youtubeError.value = null
            try {
                val (podcast, _) = repository.fetchYouTubeChannelPreview(channelUrl)
                _youtubePreview.value = podcast
                _youtubeLoading.value = false
            } catch (e: Exception) {
                e.printStackTrace()
                _youtubeError.value = e.message ?: "Unknown error"
                _youtubeLoading.value = false
            }
        }
    }

    fun clearYoutubeState() {
        _youtubePreview.value = null
        _youtubeError.value = null
    }

    fun searchFromSuggestion(suggestion: AiSuggestion) {
        _searchQuery.value = suggestion.searchQuery
        // Only do iTunes search (not another AI search to avoid loops)
        viewModelScope.launch {
            _isLoading.value = true
            _aiSearchSuggestions.value = emptyList()
            _aiSearchLoading.value = false
            try {
                _results.value = repository.searchPodcasts(suggestion.searchQuery, _selectedCountry.value)
            } catch (e: Exception) {
                e.printStackTrace()
                _results.value = emptyList()
            }
            _isLoading.value = false
        }
    }

    fun loadAiSuggestions() {
        val apiKey = getGeminiApiKey()
        if (apiKey.isEmpty()) {
            _aiError.value = "no_api_key"
            return
        }

        viewModelScope.launch {
            _aiLoading.value = true
            _aiError.value = null
            try {
                val podcasts = repository.getSubscribedPodcasts().first()
                if (podcasts.isEmpty()) {
                    _aiError.value = "no_subscriptions"
                    _aiLoading.value = false
                    return@launch
                }

                val libraryDesc = podcasts.joinToString("\n") { "- ${it.title} by ${it.author}" }

                val prompt = """Tu es un expert en recommandation de podcasts. En te basant sur la bibliothèque de podcasts ci-dessous, suggère 6 nouveaux podcasts que l'utilisateur pourrait aimer. Les suggestions doivent être variées mais en lien avec ses centres d'intérêt. Réponds entièrement en français.

Bibliothèque :
$libraryDesc

Réponds UNIQUEMENT avec un objet JSON valide dans ce format exact, sans markdown, sans blocs de code :
{"intro": "Une courte phrase d'introduction personnalisée sur ses goûts", "suggestions": [{"name": "Nom du Podcast", "reason": "Courte raison pour laquelle il aimerait", "searchQuery": "requête de recherche pour le trouver sur iTunes"}]}"""

                val response = withContext(Dispatchers.IO) {
                    val model = GenerativeModel(
                        modelName = "gemini-2.0-flash",
                        apiKey = apiKey,
                    )
                    model.generateContent(prompt)
                }

                val rawText = response.text?.trim() ?: ""
                val cleanJson = rawText
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val parsed = jsonParser.decodeFromString<AiDiscoverResponse>(cleanJson)
                _aiIntro.value = parsed.intro
                _aiSuggestions.value = parsed.suggestions
            } catch (e: Exception) {
                e.printStackTrace()
                _aiError.value = e.message ?: "Unknown error"
            }
            _aiLoading.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onPodcastClick: (ITunesPodcast) -> Unit,
    onYouTubeChannelClick: (PodcastEntity) -> Unit = {},
    onBack: () -> Unit,
    sharedUrl: String = "",
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.results.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val subscribedIds by viewModel.subscribedIds.collectAsState()
    val selectedCountry by viewModel.selectedCountry.collectAsState()

    // AI library-based state
    val aiSuggestions by viewModel.aiSuggestions.collectAsState()
    val aiIntro by viewModel.aiIntro.collectAsState()
    val aiLoading by viewModel.aiLoading.collectAsState()
    val aiError by viewModel.aiError.collectAsState()

    // AI search-based state
    val aiSearchSuggestions by viewModel.aiSearchSuggestions.collectAsState()
    val aiSearchLoading by viewModel.aiSearchLoading.collectAsState()
    val aiSearchEnabled by viewModel.aiSearchEnabled.collectAsState()

    // YouTube state
    val youtubeLoading by viewModel.youtubeLoading.collectAsState()
    val youtubePreview by viewModel.youtubePreview.collectAsState()
    val youtubeError by viewModel.youtubeError.collectAsState()

    // Handle shared YouTube URL — set search query and trigger subscription
    LaunchedEffect(sharedUrl) {
        if (BuildConfig.YOUTUBE_ENABLED && sharedUrl.isNotEmpty()) {
            viewModel.onQueryChange(sharedUrl)
            viewModel.search()
        }
    }

    val showAiSection = query.isBlank() && results.isEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(stringResource(R.string.discover)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
        )

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(if (BuildConfig.YOUTUBE_ENABLED) R.string.search_podcasts_or_youtube_hint else R.string.search_podcasts_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search)) },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
        )

        // Country filter chips
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(viewModel.countryOptions) { option ->
                FilterChip(
                    selected = selectedCountry == option.code,
                    onClick = { viewModel.onCountryChange(option.code) },
                    label = { Text(stringResource(option.labelResId)) },
                )
            }
        }

        // AI search toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = aiSearchEnabled,
                onCheckedChange = viewModel::toggleAiSearch,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.ai_search_enabled),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { viewModel.toggleAiSearch(!aiSearchEnabled) },
            )
        }

        Button(
            onClick = viewModel::search,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            enabled = query.isNotBlank() && !isLoading && !youtubeLoading,
        ) {
            Text(stringResource(R.string.search))
        }

        // YouTube loading status (only in full flavor)
        if (BuildConfig.YOUTUBE_ENABLED && youtubeLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.youtube_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (BuildConfig.YOUTUBE_ENABLED) {
            youtubePreview?.let { preview ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { onYouTubeChannelClick(preview) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = preview.artworkUrl,
                            contentDescription = preview.title,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = preview.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            if (preview.description.isNotEmpty()) {
                                Text(
                                    text = preview.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            youtubeError?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { viewModel.clearYoutubeState() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.youtube_subscribe_error, error),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (showAiSection) {
            // AI Discovery section (library-based, when no search query)
            AiDiscoverySection(
                suggestions = aiSuggestions,
                intro = aiIntro,
                isLoading = aiLoading,
                error = aiError,
                onGenerate = viewModel::loadAiSuggestions,
                onSearchSuggestion = viewModel::searchFromSuggestion,
            )
        } else {
            // Search results + AI search suggestions
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // AI search suggestions header
                if (aiSearchLoading || aiSearchSuggestions.isNotEmpty()) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.ai_search_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }

                if (aiSearchLoading) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.ai_search_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (aiSearchSuggestions.isNotEmpty()) {
                    items(aiSearchSuggestions) { suggestion ->
                        AiSuggestionCard(
                            suggestion = suggestion,
                            onSearch = { viewModel.searchFromSuggestion(suggestion) },
                        )
                    }
                }

                // iTunes results header
                if (results.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.itunes_results_title, results.size),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }

                items(results) { podcast ->
                    PodcastSearchItem(
                        podcast = podcast,
                        isSubscribed = podcast.collectionId in subscribedIds,
                        onClick = { onPodcastClick(podcast) },
                    )
                }
            }
        }
    }
}

@Composable
fun AiDiscoverySection(
    suggestions: List<AiSuggestion>,
    intro: String,
    isLoading: Boolean,
    error: String?,
    onGenerate: () -> Unit,
    onSearchSuggestion: (AiSuggestion) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Generate button
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                ),
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.ai_generate_suggestions))
            }
        }

        // Loading
        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.ai_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Error
        if (error != null && !isLoading) {
            item {
                val errorText = when (error) {
                    "no_api_key" -> stringResource(R.string.ai_no_api_key)
                    "no_subscriptions" -> stringResource(R.string.ai_no_subscriptions)
                    else -> stringResource(R.string.ai_error, error)
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = errorText,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Intro text
        if (intro.isNotBlank() && !isLoading) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Text(
                        text = intro,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Suggestion cards
        if (suggestions.isNotEmpty() && !isLoading) {
            items(suggestions) { suggestion ->
                AiSuggestionCard(
                    suggestion = suggestion,
                    onSearch = { onSearchSuggestion(suggestion) },
                )
            }
        }
    }
}

@Composable
fun AiSuggestionCard(
    suggestion: AiSuggestion,
    onSearch: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = suggestion.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = suggestion.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onSearch,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.ai_search_suggestion))
            }
        }
    }
}

@Composable
fun PodcastSearchItem(
    podcast: ITunesPodcast,
    isSubscribed: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = podcast.artworkUrl100 ?: podcast.artworkUrl600,
                contentDescription = podcast.collectionName,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = podcast.collectionName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = podcast.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isSubscribed) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.subscribed),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
