package com.duhw.grgysd

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.duhw.grgysd.ui.theme.ComposeEmptyActivityTheme
import com.duhw.grgysd.ui.theme.*

class MainActivity : AppCompatActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MusicViewModel = viewModel()
            val darkTheme = when(viewModel.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            ComposeEmptyActivityTheme(
                darkTheme = darkTheme,
                dynamicColor = viewModel.useDynamicColor,
                seedColor = viewModel.accentColor
            ) {
                PermissionWrapper { MusicApp(viewModel) }
            }
        }
    }
}

@Composable
fun PermissionWrapper(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(permission) }
    if (hasPermission) content() else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.permission_denied)) }
}

sealed class Screen {
    object Library : Screen()
    object Playlists : Screen()
    data class PlaylistDetail(val playlist: Playlist) : Screen()
    object Online : Screen()
    object Settings : Screen()
    object SourceManager : Screen()
    object HiddenSongs : Screen()
}

val Screen.index: Int
    get() = when (this) {
        is Screen.Library -> 0
        is Screen.Playlists -> 1
        is Screen.Online -> 2
        is Screen.PlaylistDetail -> 3
        is Screen.Settings -> 4
        is Screen.SourceManager -> 5
        is Screen.HiddenSongs -> 6
    }


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicApp(viewModel: MusicViewModel) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Library) }
    var isPlayerSheetVisible by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    
    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.errorMessage = null
        }
    }

    LaunchedEffect(Unit) { viewModel.loadLocalSongs() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (currentScreen !is Screen.Settings && currentScreen !is Screen.HiddenSongs && currentScreen !is Screen.PlaylistDetail) {
                CenterAlignedTopAppBar(
                    title = { Text(if (currentScreen is Screen.Library) stringResource(R.string.nav_library) else stringResource(R.string.nav_playlists), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { currentScreen = Screen.Settings }) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (currentScreen is Screen.Library || currentScreen is Screen.Playlists || currentScreen is Screen.Online) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.MusicNote, null) },
                        label = { Text(stringResource(R.string.nav_library)) },
                        selected = currentScreen is Screen.Library,
                        onClick = { currentScreen = Screen.Library }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.PlaylistPlay, null) },
                        label = { Text(stringResource(R.string.nav_playlists)) },
                        selected = currentScreen is Screen.Playlists,
                        onClick = { currentScreen = Screen.Playlists }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Cloud, null) },
                        label = { Text(stringResource(R.string.nav_online)) },
                        selected = currentScreen is Screen.Online,
                        onClick = { 
                            currentScreen = Screen.Online
                            viewModel.isOnlineEnabled = true 
                        }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    val direction = if (targetState.index > initialState.index) 1 else -1
                    (fadeIn(animationSpec = tween(300)) + slideInHorizontally(animationSpec = tween(300), initialOffsetX = { direction * it / 2 }))
                        .togetherWith(fadeOut(animationSpec = tween(300)) + slideOutHorizontally(animationSpec = tween(300), targetOffsetX = { -direction * it / 2 }))
                },
                label = "screen_transition"
            ) { screen ->
                when (screen) {
                    is Screen.Library -> LibraryScreen(viewModel)
                    is Screen.Playlists -> PlaylistsScreen(viewModel, onPlaylistClick = { currentScreen = Screen.PlaylistDetail(it) })
                    is Screen.Online -> OnlineScreen(viewModel)
                    is Screen.PlaylistDetail -> PlaylistDetailScreen(viewModel, screen.playlist, onBack = { currentScreen = Screen.Playlists })
                    is Screen.Settings -> SettingsScreen(viewModel, onBack = { currentScreen = Screen.Library }, onNavigateToHidden = { currentScreen = Screen.HiddenSongs }, onNavigateToSources = { currentScreen = Screen.SourceManager })
                    is Screen.SourceManager -> SourceManagerScreen(viewModel, onBack = { currentScreen = Screen.Settings })
                    is Screen.HiddenSongs -> HiddenSongsScreen(viewModel, onBack = { currentScreen = Screen.Settings })
                }
            }

            val currentSong = viewModel.currentSong
            if (currentSong != null && !isPlayerSheetVisible && currentScreen !is Screen.Settings && currentScreen !is Screen.HiddenSongs) {
                Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                    MiniPlayer(song = currentSong, isPlaying = viewModel.isPlaying, onTogglePlay = { viewModel.togglePlay() }, onClick = { isPlayerSheetVisible = true })
                }
            }
        }
    }

    AnimatedVisibility(
        visible = isPlayerSheetVisible && viewModel.currentSong != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        PlayerDetailScreen(viewModel = viewModel, onClose = { isPlayerSheetVisible = false })
        BackHandler { isPlayerSheetVisible = false }
    }
    
    // 返回键处理：如果当前在二级页面，返回上一级
    if (currentScreen !is Screen.Library) {
        BackHandler {
            currentScreen = when (currentScreen) {
                is Screen.PlaylistDetail -> Screen.Playlists
                is Screen.HiddenSongs -> Screen.Settings
                else -> Screen.Library
            }
        }
    }
}

@Composable
fun OnlineScreen(viewModel: MusicViewModel) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = viewModel.searchQuery,
            onValueChange = { viewModel.onSearchQueryChanged(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text(stringResource(R.string.search_online_placeholder)) },
            leadingIcon = { Icon(Icons.Default.CloudSync, null) },
            trailingIcon = {
                if (viewModel.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                        Icon(Icons.Default.Clear, null)
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )

        if (viewModel.searchQuery.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Search, null, Modifier.size(64.dp), Color.Gray.copy(0.3f))
                    Text(stringResource(R.string.search_online_hint), color = Color.Gray)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(
                    items = viewModel.onlineSongs,
                    key = { it.uri.toString() }
                ) { song ->
                    Box(Modifier.animateItem()) {
                        SongItem(
                            song = song,
                            isSelected = song.uri == viewModel.currentSong?.uri,
                            isFavorite = viewModel.isFavorite(song.id),
                            onFavoriteToggle = { viewModel.toggleFavorite(song.id) },
                            onHide = { viewModel.hideSong(song.id) },
                            onClick = { viewModel.playSong(song) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(viewModel: MusicViewModel) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = viewModel.searchQuery,
            onValueChange = { viewModel.onSearchQueryChanged(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            placeholder = { Text(stringResource(R.string.search_local_placeholder)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (viewModel.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.searchQuery = "" }) {
                        Icon(Icons.Default.Clear, null)
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
        
        if (viewModel.isLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        
        LazyColumn(Modifier.fillMaxSize()) {
            items(
                items = viewModel.localFilteredSongs,
                key = { it.id }
            ) { song ->
                Box(Modifier.animateItem()) {
                    SongItem(
                        song = song,
                        isSelected = song == viewModel.currentSong,
                        isFavorite = viewModel.isFavorite(song.id),
                        onFavoriteToggle = { viewModel.toggleFavorite(song.id) },
                        onHide = { viewModel.hideSong(song.id) },
                        onClick = { viewModel.playSong(song) }
                    )
                }
            }
        }
    }
}

@Composable
fun SongItem(song: Song, isSelected: Boolean, isFavorite: Boolean, onFavoriteToggle: () -> Unit, onHide: () -> Unit, onClick: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { onClick() },
        headlineContent = { Text(song.title, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, color = if(isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified) },
        supportingContent = { Text(song.artist, maxLines = 1) },
        leadingContent = {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(song.coverColor), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, tint = Color.White)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onFavoriteToggle) {
                    Icon(if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, null, tint = if (isFavorite) Color.Red else Color.Gray)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_hide_song)) }, onClick = { onHide(); showMenu = false }, leadingIcon = { Icon(Icons.Default.VisibilityOff, null) })
                    }
                }
            }
        }
    )
}

@Composable
fun PlaylistsScreen(viewModel: MusicViewModel, onPlaylistClick: (Playlist) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.create_playlist), fontWeight = FontWeight.Bold) },
                leadingContent = { Icon(Icons.Default.Add, null) },
                modifier = Modifier.clickable { showDialog = true }
            )
        }
        items(viewModel.userPlaylists) { playlist ->
            ListItem(
                headlineContent = { Text(playlist.name) },
                supportingContent = { Text("${playlist.songIds.size} songs") },
                leadingContent = { Icon(Icons.Default.LibraryMusic, null) },
                modifier = Modifier.clickable { onPlaylistClick(playlist) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) }
            )
        }
    }

    if (showDialog) {
        AlertDialog(onDismissRequest = { showDialog = false }, title = { Text(stringResource(R.string.new_playlist_title)) }, text = { TextField(value = newName, onValueChange = { newName = it }) }, confirmButton = { TextButton(onClick = { if(newName.isNotBlank()) viewModel.createPlaylist(newName); showDialog = false; newName = "" }) { Text(stringResource(R.string.create_button)) } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(viewModel: MusicViewModel, playlist: Playlist, onBack: () -> Unit) {
    val songs = viewModel.getSongsInPlaylist(playlist)
    Scaffold(
        topBar = { TopAppBar(title = { Text(playlist.name) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }
    ) { padding ->
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_songs_playlist)) }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(songs) { song ->
                    SongItem(song = song, isSelected = song == viewModel.currentSong, isFavorite = viewModel.isFavorite(song.id), onFavoriteToggle = { viewModel.toggleFavorite(song.id) }, onHide = { viewModel.hideSong(song.id) }, onClick = { viewModel.playSong(song) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MusicViewModel, onBack: () -> Unit, onNavigateToHidden: () -> Unit, onNavigateToSources: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppLanguage.values().forEach { lang ->
                            FilterChip(
                                selected = viewModel.currentLanguage == lang,
                                onClick = { viewModel.setLanguage(lang) },
                                label = { Text(lang.label) }
                            )
                        }
                    }
                }
            )

            ListItem(headlineContent = { Text(stringResource(R.string.settings_theme_mode)) }, trailingContent = {
                Row { ThemeMode.values().forEach { mode -> FilterChip(selected = viewModel.themeMode == mode, onClick = { viewModel.updateThemeMode(mode) }, label = { Text(mode.name) }) } }
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(headlineContent = { Text(stringResource(R.string.settings_dynamic_color)) }, trailingContent = { Switch(checked = viewModel.useDynamicColor, onCheckedChange = { viewModel.updateDynamicColor(it) }) })
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_online_music), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_enable_online)) },
                trailingContent = { Switch(checked = viewModel.isOnlineEnabled, onCheckedChange = { viewModel.updateOnlineEnabled(it) }) }
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_source_management)) },
                supportingContent = { Text(stringResource(R.string.settings_current_source, viewModel.selectedSource.name)) },
                leadingContent = { Icon(Icons.Default.Dns, null) },
                modifier = Modifier.clickable { onNavigateToSources() },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) }
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_library_management), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            ListItem(headlineContent = { Text(stringResource(R.string.settings_hidden_songs)) }, leadingContent = { Icon(Icons.Default.VisibilityOff, null) }, modifier = Modifier.clickable { onNavigateToHidden() }, trailingContent = { Icon(Icons.Default.ChevronRight, null) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManagerScreen(viewModel: MusicViewModel, onBack: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text(stringResource(R.string.source_manager_title)) }, 
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = { IconButton(onClick = { showDialog = true }) { Icon(Icons.Default.Add, null) } }
            ) 
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(viewModel.availableSources) { source ->
                val isSelected = viewModel.selectedSource == source
                ListItem(
                    modifier = Modifier.clickable { viewModel.updateSelectedSource(source) },
                    headlineContent = { Text(source.name, fontWeight = if(isSelected) FontWeight.Bold else FontWeight.Normal) },
                    supportingContent = { Text(source.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { 
                        RadioButton(selected = isSelected, onClick = { viewModel.updateSelectedSource(source) }) 
                    },
                    trailingContent = {
                        if (viewModel.availableSources.size > 1) {
                            IconButton(onClick = { 
                                viewModel.availableSources.remove(source)
                                if (isSelected) viewModel.selectedSource = viewModel.availableSources[0]
                            }) {
                                Icon(Icons.Default.Delete, null, tint = Color.Gray)
                            }
                        }
                    }
                )
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.add_source_title)) },
            text = {
                Column {
                    TextField(value = newName, onValueChange = { newName = it }, label = { Text(stringResource(R.string.source_name_label)) })
                    Spacer(Modifier.height(8.dp))
                    TextField(value = newUrl, onValueChange = { newUrl = it }, label = { Text(stringResource(R.string.source_url_label)) })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank() && newUrl.isNotBlank()) {
                        viewModel.availableSources.add(MusicSource(newName, newUrl))
                        showDialog = false
                        newName = ""; newUrl = ""
                    }
                }) { Text(stringResource(R.string.add_button)) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.cancel_button)) } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenSongsScreen(viewModel: MusicViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.hidden_songs_title)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }
    ) { padding ->
        val hiddenSongs = viewModel.allSongs.filter { it.id in viewModel.hiddenSongIds }
        if (hiddenSongs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_hidden_songs)) }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(hiddenSongs) { song ->
                    ListItem(headlineContent = { Text(song.title) }, trailingContent = { Button(onClick = { viewModel.unhideSong(song.id) }) { Text(stringResource(R.string.restore_button)) } })
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(song: Song, isPlaying: Boolean, onTogglePlay: () -> Unit, onClick: () -> Unit) {
    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(64.dp).clickable { onClick() }, shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(song.coverColor), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Color.White) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(song.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = onTogglePlay) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
        }
    }
}

@Composable
fun PlayerDetailScreen(viewModel: MusicViewModel, onClose: () -> Unit) {
    val song = viewModel.currentSong ?: return
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(song.coverColor.copy(0.8f), Color.Black))).statusBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onClose, Modifier.align(Alignment.Start)) { Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.weight(1f))
            Surface(Modifier.size(300.dp), shape = RoundedCornerShape(24.dp), color = song.coverColor) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, Modifier.size(100.dp), Color.White.copy(0.5f)) } }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(song.artist, color = Color.White.copy(0.7f), style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = { viewModel.toggleFavorite(song.id) }) {
                    val isFav = viewModel.isFavorite(song.id)
                    Icon(if (isFav) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder, tint = if (isFav) Color.Red else Color.White, contentDescription = null)
                }
            }
            val progress = if (song.durationMs > 0) viewModel.currentPositionMs.toFloat() / song.durationMs else 0f
            Slider(value = progress, onValueChange = { viewModel.seekTo(it) }, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(viewModel.currentPositionMs), color = Color.White.copy(0.6f))
                Text(formatTime(song.durationMs), color = Color.White.copy(0.6f))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { viewModel.previous() }) { Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                FilledIconButton(onClick = { viewModel.togglePlay() }, Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black)) { Icon(if (viewModel.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(40.dp)) }
                IconButton(onClick = { viewModel.next() }) { Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}