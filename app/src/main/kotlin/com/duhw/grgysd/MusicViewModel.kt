package com.duhw.grgysd

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DefaultDataSource

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AppLanguage(val tag: String, val label: String) {
    SYSTEM("", "Default"),
    EN("en", "English"),
    ZH("zh", "中文")
}

enum class SourcePlatform(val label: String) {
    NETEASE("网易云"),
    KUGOU("酷狗"),
    KUWO("酷我"),
    BODIAN("波点")
}

data class MusicSource(val name: String, val url: String, val platform: SourcePlatform)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "http://www.kuwo.cn/")
                .build()
            chain.proceed(request)
        }
        .build()

    private val player: ExoPlayer = ExoPlayer.Builder(application)
        .setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(application)
                .setDataSourceFactory(DefaultDataSource.Factory(application, OkHttpDataSource.Factory(client)))
        )
        .build()

    private var searchJob: Job? = null
    val availableSources = mutableStateListOf<MusicSource>()
    val selectedSources = mutableStateMapOf<SourcePlatform, MusicSource>()
    
    var allSongs by mutableStateOf<List<Song>>(emptyList())
    var onlineSongs by mutableStateOf<List<Song>>(emptyList())
    var onlineSongsStore = mutableStateListOf<Song>()
    var userPlaylists by mutableStateOf<List<Playlist>>(listOf(Playlist("fav", "Favorites")))
    var hiddenSongIds = mutableStateListOf<Long>()
    var searchQuery by mutableStateOf("")
    
    var currentSong by mutableStateOf<Song?>(null)
    var isPlaying by mutableStateOf(false)
    var currentPositionMs by mutableStateOf(0L)
    var isLoading by mutableStateOf(true)

    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    var useDynamicColor by mutableStateOf(true)
    var accentColor by mutableStateOf(Color(0xFF3F51B5))
    var currentLanguage by mutableStateOf(AppLanguage.SYSTEM)
    var isOnlineEnabled by mutableStateOf(false)
    var searchPlatform by mutableStateOf<SourcePlatform?>(null)
    var errorMessage by mutableStateOf<String?>(null)

    val localFilteredSongs: List<Song>
        get() = allSongs.filter {
            it.id !in hiddenSongIds && 
            (searchQuery.isBlank() || it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true))
        }

    init {
        loadSettings()
        initDefaultSources()
        loadOnlineSongsStore()
        loadPlaylists()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                errorMessage = "Playback failed: ${error.localizedMessage}"
                if (player.hasNextMediaItem()) { player.seekToNext(); player.play() }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val mediaId = mediaItem?.mediaId
                if (mediaId != null) {
                    currentSong = (allSongs + onlineSongs + onlineSongsStore).find { it.id.toString() == mediaId }
                }
            }
        })

        viewModelScope.launch {
            while (true) {
                currentPositionMs = player.currentPosition
                delay(500)
            }
        }
    }

    private fun loadSettings() {
        themeMode = ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name)!!)
        useDynamicColor = prefs.getBoolean("dynamic_color", true)
        accentColor = Color(prefs.getInt("accent_color", Color(0xFF3F51B5).toArgb().toInt()))
        isOnlineEnabled = prefs.getBoolean("online_enabled", false)
        
        val savedLang = prefs.getString("app_lang", AppLanguage.SYSTEM.name)
        currentLanguage = AppLanguage.valueOf(savedLang ?: AppLanguage.SYSTEM.name)
        if (currentLanguage != AppLanguage.SYSTEM) setLanguage(currentLanguage)
        
        val hiddenIds = prefs.getStringSet("hidden_songs", emptySet()) ?: emptySet()
        hiddenSongIds.clear()
        hiddenSongIds.addAll(hiddenIds.mapNotNull { it.toLongOrNull() })
    }

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun updateDynamicColor(enabled: Boolean) {
        useDynamicColor = enabled
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
    }

    fun updateAccentColor(color: Color) {
        accentColor = color
        prefs.edit().putInt("accent_color", color.toArgb()).apply()
    }

    fun setLanguage(language: AppLanguage) {
        currentLanguage = language
        prefs.edit().putString("app_lang", language.name).apply()
        val appLocale = if (language == AppLanguage.SYSTEM) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(language.tag)
        AppCompatDelegate.setApplicationLocales(appLocale)
    }

    fun updateOnlineEnabled(enabled: Boolean) {
        isOnlineEnabled = enabled
        prefs.edit().putBoolean("online_enabled", enabled).apply()
    }

    private fun initDefaultSources() {
        val defaults = listOf(
            MusicSource("默认 (Qijieya)", "https://163api.qijieya.cn", SourcePlatform.NETEASE),
            MusicSource("官方源", "http://antiserver.kuwo.cn", SourcePlatform.KUWO),
            MusicSource("官方源", "https://findmusic-api.com", SourcePlatform.BODIAN),
            MusicSource("官方源", "http://mobilecdn.kugou.com", SourcePlatform.KUGOU)
        )
        val customJson = prefs.getString("custom_sources", "[]") ?: "[]"
        try {
            val arr = JSONArray(customJson)
            val list = mutableListOf<MusicSource>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(MusicSource(obj.getString("name"), obj.getString("url"), SourcePlatform.valueOf(obj.getString("platform"))))
            }
            availableSources.clear()
            availableSources.addAll(defaults + list)
        } catch (e: Exception) { availableSources.addAll(defaults) }

        SourcePlatform.values().forEach { platform ->
            val savedUrl = prefs.getString("selected_source_${platform.name}", "")
            val source = availableSources.find { it.platform == platform && it.url == savedUrl } ?: availableSources.find { it.platform == platform }
            if (source != null) selectedSources[platform] = source
        }
    }

    fun addCustomSource(name: String, url: String, platform: SourcePlatform) {
        val newSource = MusicSource(name, url, platform)
        availableSources.add(newSource)
        saveCustomSources()
    }

    fun removeSource(source: MusicSource) {
        availableSources.remove(source)
        saveCustomSources()
    }

    private fun saveCustomSources() {
        val defaultsUrls = listOf("https://163api.qijieya.cn", "http://antiserver.kuwo.cn", "https://findmusic-api.com", "http://mobilecdn.kugou.com")
        val customs = availableSources.filter { it.url !in defaultsUrls }
        val arr = JSONArray()
        customs.forEach {
            val obj = JSONObject()
            obj.put("name", it.name); obj.put("url", it.url); obj.put("platform", it.platform.name)
            arr.put(obj)
        }
        prefs.edit().putString("custom_sources", arr.toString()).apply()
    }

    fun updateSelectedSource(platform: SourcePlatform, source: MusicSource) {
        selectedSources[platform] = source
        prefs.edit().putString("selected_source_${platform.name}", source.url).apply()
    }

    private fun loadPlaylists() {
        val plJson = prefs.getString("user_playlists", null) ?: return
        try {
            val arr = JSONArray(plJson)
            val list = mutableListOf<Playlist>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val sIds = obj.getJSONArray("songIds")
                val idList = mutableListOf<Long>()
                for (j in 0 until sIds.length()) idList.add(sIds.getLong(j))
                list.add(Playlist(obj.getString("id"), obj.getString("name"), idList))
            }
            userPlaylists = list
        } catch (e: Exception) {}
    }

    private fun savePlaylist(playlist: Playlist) {
        val playlistsJson = JSONArray()
        userPlaylists.map { if(it.id == playlist.id) playlist else it }.forEach { pl ->
            val pObj = JSONObject()
            pObj.put("id", pl.id); pObj.put("name", pl.name)
            val sIds = JSONArray(); pl.songIds.forEach { sIds.put(it) }
            pObj.put("songIds", sIds); playlistsJson.put(pObj)
        }
        prefs.edit().putString("user_playlists", playlistsJson.toString()).apply()
    }

    private fun loadOnlineSongsStore() {
        val json = prefs.getString("online_songs_store", "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            val list = mutableListOf<Song>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Song(obj.getLong("id"), obj.getString("title"), obj.getString("artist"), obj.getLong("duration"), android.net.Uri.parse(obj.getString("uri")), Color(obj.getLong("color").toULong())))
            }
            onlineSongsStore.clear()
            onlineSongsStore.addAll(list)
        } catch (e: Exception) {}
    }

    private fun saveOnlineSongsStore() {
        val arr = JSONArray()
        onlineSongsStore.forEach {
            val obj = JSONObject()
            obj.put("id", it.id); obj.put("title", it.title); obj.put("artist", it.artist); obj.put("duration", it.durationMs); obj.put("uri", it.uri.toString()); obj.put("color", it.coverColor.value.toLong())
            arr.put(obj)
        }
        prefs.edit().putString("online_songs_store", arr.toString()).apply()
    }

    fun onSearchQueryChanged(newQuery: String) {
        searchQuery = newQuery; searchJob?.cancel()
        if (newQuery.isNotBlank()) {
            searchJob = viewModelScope.launch {
                delay(600)
                if (isOnlineEnabled) performOnlineSearch(newQuery)
            }
        } else { onlineSongs = emptyList() }
    }

    private suspend fun performOnlineSearch(query: String) {
        val results = withContext(Dispatchers.IO) {
            coroutineScope {
                val tasks = mutableListOf<kotlinx.coroutines.Deferred<List<Song>>>()
                if (searchPlatform == null || searchPlatform == SourcePlatform.KUWO) tasks.add(async { searchKuwo(query) })
                if (searchPlatform == null || searchPlatform == SourcePlatform.BODIAN) tasks.add(async { searchBodian(query) })
                if (searchPlatform == null || searchPlatform == SourcePlatform.NETEASE) tasks.add(async { searchNetease(query) })
                if (searchPlatform == null || searchPlatform == SourcePlatform.KUGOU) tasks.add(async { searchKugou(query) })
                tasks.flatMap { it.await() }.distinctBy { it.uri.toString() }
            }
        }
        withContext(Dispatchers.Main) { onlineSongs = results }
    }

    private fun searchKuwo(query: String): List<Song> {
        val list = mutableListOf<Song>()
        val selected = selectedSources[SourcePlatform.KUWO] ?: return emptyList()
        try {
            val baseUrl = if (selected.url.contains("kuwo.cn")) "http://search.kuwo.cn" else selected.url.removeSuffix("/")
            val response = client.newCall(Request.Builder().url("$baseUrl/r.s?all=$query&ft=music&itemset=web_2013&client=kt&cluster=0&pn=0&rn=15&rformat=json&encoding=utf8").build()).execute().body?.string() ?: ""
            val abslist = JSONObject(response.removePrefix("('").removeSuffix("')").replace("'", "\"")).getJSONArray("abslist")
            for (i in 0 until abslist.length()) {
                val item = abslist.getJSONObject(i); val rid = item.getString("MUSICRID").removePrefix("MUSIC_")
                list.add(Song(rid.toLongOrNull() ?: UUID.randomUUID().hashCode().toLong(), item.getString("SONGNAME"), item.getString("ARTIST"), 0, android.net.Uri.parse("https://antiserver.kuwo.cn/anti.s?format=mp3&rid=MUSIC_$rid&type=convert_url&response=res&cp=0"), Color(0xFF2196F3)))
            }
        } catch (e: Exception) {}
        return list
    }

    private fun searchKugou(query: String): List<Song> {
        val list = mutableListOf<Song>()
        val selected = selectedSources[SourcePlatform.KUGOU] ?: return emptyList()
        try {
            val response = client.newCall(Request.Builder().url("${selected.url.removeSuffix("/")}/api/v3/search/song?keyword=$query&page=1&pagesize=20").build()).execute().body?.string() ?: ""
            val data = JSONObject(response).getJSONObject("data").getJSONArray("info")
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i); val hash = item.getString("hash")
                list.add(Song(UUID.randomUUID().hashCode().toLong(), item.getString("songname"), item.getString("singername"), item.getLong("duration") * 1000, android.net.Uri.parse("https://www.kugou.com/song/#hash=$hash"), Color(0xFF03A9F4)))
            }
        } catch (e: Exception) {}
        return list
    }

    private fun searchBodian(query: String): List<Song> {
        val list = mutableListOf<Song>()
        val selected = selectedSources[SourcePlatform.BODIAN] ?: return emptyList()
        try {
            val response = client.newCall(Request.Builder().url("${selected.url.removeSuffix("/")}/search?keywords=$query&type=bodian").build()).execute().body?.string() ?: ""
            val data = JSONObject(response).optJSONArray("data")
            if (data != null) for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                list.add(Song(UUID.randomUUID().hashCode().toLong(), item.optString("title", "Unknown"), item.optString("artist", "Unknown"), 0, android.net.Uri.parse(item.optString("url", "")), Color(0xFF4CAF50)))
            }
        } catch (e: Exception) {}
        return list
    }

    private fun searchNetease(query: String): List<Song> {
        val list = mutableListOf<Song>()
        val selected = selectedSources[SourcePlatform.NETEASE] ?: return emptyList()
        try {
            val response = client.newCall(Request.Builder().url("${selected.url.removeSuffix("/")}/cloudsearch?keywords=$query&limit=20&type=1").build()).execute().body?.string() ?: ""
            val songs = JSONObject(response).optJSONObject("result")?.optJSONArray("songs")
            if (songs != null) for (i in 0 until songs.length()) {
                val item = songs.getJSONObject(i); val ar = item.optJSONArray("ar")
                list.add(Song(item.getLong("id"), item.optString("name", "Unknown"), if (ar != null && ar.length() > 0) ar.getJSONObject(0).optString("name", "Unknown") else "Unknown", item.optLong("dt", 0), android.net.Uri.parse("https://music.163.com/song/media/outer/url?id=${item.getLong("id")}.mp3"), Color(0xFFF44336)))
            }
        } catch (e: Exception) {}
        return list
    }

    fun loadLocalSongs() {
        viewModelScope.launch {
            val songs = mutableListOf<Song>()
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION)
            try {
                getApplication<Application>().contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    while (cursor.moveToNext()) {
                        songs.add(Song(cursor.getLong(idCol), cursor.getString(titleCol), cursor.getString(artistCol), cursor.getLong(durationCol), ContentUris.withAppendedId(collection, cursor.getLong(idCol))))
                    }
                }
            } catch (e: Exception) {}
            allSongs = songs; isLoading = false
            if (localFilteredSongs.isNotEmpty()) { syncPlayerQueue(); if (currentSong == null) currentSong = localFilteredSongs[0] }
        }
    }

    private fun syncPlayerQueue() {
        player.setMediaItems(localFilteredSongs.map { MediaItem.Builder().setUri(it.uri).setMediaId(it.id.toString()).build() })
        player.prepare()
    }

    private suspend fun getPlayableUrl(song: Song): android.net.Uri {
        return withContext(Dispatchers.IO) {
            try {
                if (song.coverColor == Color(0xFFF44336)) {
                    val selected = selectedSources[SourcePlatform.NETEASE] ?: return@withContext song.uri
                    val response = client.newCall(Request.Builder().url("${selected.url.removeSuffix("/")}/song/url/v1?id=${song.id}&level=standard").build()).execute().body?.string() ?: ""
                    val dataArray = JSONObject(response).optJSONArray("data")
                    if (dataArray != null && dataArray.length() > 0) {
                        val realUrl = dataArray.getJSONObject(0).optString("url", "")
                        if (realUrl.isNotBlank() && realUrl != "null") return@withContext android.net.Uri.parse(realUrl)
                    }
                    return@withContext android.net.Uri.parse("https://music.163.com/song/media/outer/url?id=${song.id}.mp3")
                } else if (song.coverColor == Color(0xFF2196F3)) {
                    val realUrl = client.newCall(Request.Builder().url("http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=MUSIC_${song.id}&format=mp3&response=url").build()).execute().body?.string() ?: ""
                    if (realUrl.startsWith("http")) return@withContext android.net.Uri.parse(realUrl)
                } else if (song.coverColor == Color(0xFF03A9F4)) {
                    val hash = song.uri.fragment?.split("=")?.get(1) ?: ""
                    val response = client.newCall(Request.Builder().url("http://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$hash").build()).execute().body?.string() ?: ""
                    val realUrl = JSONObject(response).optString("url", "")
                    if (realUrl.isNotBlank()) return@withContext android.net.Uri.parse(realUrl)
                }
                song.uri
            } catch (e: Exception) { song.uri }
        }
    }

    fun playSong(song: Song) {
        viewModelScope.launch {
            isLoading = true
            val playableUri = if (onlineSongs.contains(song) || onlineSongsStore.contains(song)) getPlayableUrl(song) else song.uri
            val isLocal = allSongs.any { it.id == song.id && it.uri == song.uri }
            val currentList = if (isLocal) localFilteredSongs else if (onlineSongs.contains(song)) onlineSongs else onlineSongsStore
            val index = currentList.indexOf(song)
            if (index != -1) {
                player.setMediaItems(currentList.map { MediaItem.Builder().setUri(if (it == song) playableUri else it.uri).setMediaId(it.id.toString()).build() })
                player.prepare(); player.seekTo(index, 0); player.play(); currentSong = song
            }
            isLoading = false
        }
    }

    fun togglePlay() { 
        if (player.mediaItemCount == 0 && localFilteredSongs.isNotEmpty()) syncPlayerQueue()
        if (player.isPlaying) player.pause() else player.play() 
    }
    fun next() { if (player.hasNextMediaItem()) player.seekToNext() }
    fun previous() { if (player.hasPreviousMediaItem()) player.seekToPrevious() }
    fun seekTo(position: Float) { player.seekTo((position * (currentSong?.durationMs ?: 0)).toLong()) }
    fun isFavorite(songId: Long): Boolean = userPlaylists.find { it.id == "fav" }?.songIds?.contains(songId) == true

    fun addSongToPlaylist(song: Song, playlistId: String) {
        if (!allSongs.any { it.id == song.id } && !onlineSongsStore.any { it.id == song.id }) { onlineSongsStore.add(song); saveOnlineSongsStore() }
        userPlaylists = userPlaylists.map {
            if (it.id == playlistId && !it.songIds.contains(song.id)) { val newList = it.copy(songIds = it.songIds + song.id); savePlaylist(newList); newList } else it
        }
    }

    fun toggleFavorite(song: Song) {
        if (!isFavorite(song.id)) addSongToPlaylist(song, "fav")
        else {
            userPlaylists = userPlaylists.map {
                if (it.id == "fav") { val newList = it.copy(songIds = it.songIds - song.id); savePlaylist(newList); newList } else it
            }
        }
    }

    fun hideSong(songId: Long) {
        hiddenSongIds.add(songId)
        prefs.edit().putStringSet("hidden_songs", hiddenSongIds.map { it.toString() }.toSet()).apply()
        syncPlayerQueue()
    }

    fun unhideSong(songId: Long) {
        hiddenSongIds.remove(songId)
        prefs.edit().putStringSet("hidden_songs", hiddenSongIds.map { it.toString() }.toSet()).apply()
        syncPlayerQueue()
    }

    fun createPlaylist(name: String) {
        val newList = Playlist(UUID.randomUUID().toString(), name)
        userPlaylists = userPlaylists + newList
        savePlaylist(newList)
    }

    fun getSongsInPlaylist(playlist: Playlist): List<Song> {
        return (allSongs + onlineSongsStore).filter { it.id in playlist.songIds }
    }

    override fun onCleared() { super.onCleared(); player.release() }
}