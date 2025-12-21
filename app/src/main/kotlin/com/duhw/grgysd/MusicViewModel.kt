package com.duhw.grgysd

import android.app.Application
import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
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

data class MusicSource(val name: String, val url: String)

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
    
    val availableSources = mutableStateListOf(
        MusicSource("默认 (Qijieya)", "https://163api.qijieya.cn"),
        MusicSource("Armoe", "https://zm.armoe.cn"),
        MusicSource("DG-T", "http://dg-t.cn:3000"),
        MusicSource("XHILY", "https://wyy.xhily.com"),
        MusicSource("Focalors", "https://music-api.focalors.ltd")
    )
    
    var selectedSource by mutableStateOf(availableSources[0])
    var allSongs by mutableStateOf<List<Song>>(emptyList())
    var onlineSongs by mutableStateOf<List<Song>>(emptyList())
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
    var errorMessage by mutableStateOf<String?>(null)

    val localFilteredSongs: List<Song>
        get() = allSongs.filter { 
            it.id !in hiddenSongIds && 
            (searchQuery.isBlank() || it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true))
        }

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun updateDynamicColor(enabled: Boolean) {
        useDynamicColor = enabled
        prefs.edit().putBoolean("dynamic_color", enabled).apply()
    }

    fun setLanguage(language: AppLanguage) {
        currentLanguage = language
        prefs.edit().putString("app_lang", language.name).apply()
        val appLocale: LocaleListCompat = if (language == AppLanguage.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.tag)
        }
        AppCompatDelegate.setApplicationLocales(appLocale)
    }

    fun updateOnlineEnabled(enabled: Boolean) {
        isOnlineEnabled = enabled
        prefs.edit().putBoolean("online_enabled", enabled).apply()
    }

    fun updateSelectedSource(source: MusicSource) {
        selectedSource = source
        prefs.edit().putString("selected_source_name", source.name).apply()
    }

    init {
        // 加载持久化设置
        themeMode = ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name)!!)
        useDynamicColor = prefs.getBoolean("dynamic_color", true)
        isOnlineEnabled = prefs.getBoolean("online_enabled", false)
        
        val savedLang = prefs.getString("app_lang", AppLanguage.SYSTEM.name)
        val initialLang = AppLanguage.valueOf(savedLang ?: AppLanguage.SYSTEM.name)
        currentLanguage = initialLang
        // 初始应用保存的语言
        if (initialLang != AppLanguage.SYSTEM) {
            setLanguage(initialLang)
        }
        
        val sourceName = prefs.getString("selected_source_name", "默认 (Qijieya)")
        availableSources.find { it.name == sourceName }?.let { selectedSource = it }

        val hiddenIds = prefs.getStringSet("hidden_songs", emptySet()) ?: emptySet()
        hiddenSongIds.addAll(hiddenIds.mapNotNull { it.toLongOrNull() })

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                errorMessage = "Playback failed: ${error.localizedMessage}"
                if (player.hasNextMediaItem()) {
                    player.seekToNext()
                    player.play()
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = player.currentMediaItemIndex
                if (index >= 0 && index < player.mediaItemCount) {
                    val uri = player.getMediaItemAt(index).localConfiguration?.uri
                    currentSong = (allSongs + onlineSongs).find { it.uri == uri }
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

    fun onSearchQueryChanged(newQuery: String) {
        searchQuery = newQuery
        searchJob?.cancel()
        if (newQuery.isNotBlank()) {
            searchJob = viewModelScope.launch {
                delay(600) // 增加一点抖动，防止请求过快
                if (isOnlineEnabled) {
                    performOnlineSearch(newQuery)
                }
            }
        } else {
            onlineSongs = emptyList()
        }
    }

    private suspend fun performOnlineSearch(query: String) {
        val results = withContext(Dispatchers.IO) {
            coroutineScope {
                val kuwo = async { searchKuwo(query) }
                val bodian = async { searchBodian(query) }
                val netease = async { searchNetease(query) }
                
                (kuwo.await() + bodian.await() + netease.await()).distinctBy { it.uri.toString() }
            }
        }
        withContext(Dispatchers.Main) {
            onlineSongs = results
        }
    }

    private fun searchKuwo(query: String): List<Song> {
        val list = mutableListOf<Song>()
        try {
            val url = "http://search.kuwo.cn/r.s?all=$query&ft=music&itemset=web_2013&client=kt&cluster=0&pn=0&rn=15&rformat=json&encoding=utf8"
            val response = client.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: ""
            val json = JSONObject(response.removePrefix("('").removeSuffix("')").replace("'", "\""))
            val abslist = json.getJSONArray("abslist")
            for (i in 0 until abslist.length()) {
                val item = abslist.getJSONObject(i)
                val rid = item.getString("MUSICRID").removePrefix("MUSIC_")
                list.add(Song(
                    id = rid.toLongOrNull() ?: UUID.randomUUID().hashCode().toLong(),
                    title = item.getString("SONGNAME"),
                    artist = item.getString("ARTIST"),
                    durationMs = 0,
                    // antiserver 接口会自动重定向到真实的 .mp3 链接，配合 OkHttpDataSource 可以完美播放
                    uri = android.net.Uri.parse("https://antiserver.kuwo.cn/anti.s?format=mp3&rid=MUSIC_$rid&type=convert_url&response=res&cp=0"),
                    coverColor = Color(0xFF2196F3)
                ))
            }
        } catch (e: Exception) { }
        return list
    }

    private fun searchBodian(query: String): List<Song> {
        val list = mutableListOf<Song>()
        try {
            val url = "https://findmusic-api.com/search?keywords=$query&type=bodian"
            val response = client.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: ""
            val data = JSONObject(response).optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    list.add(Song(
                        id = UUID.randomUUID().hashCode().toLong(),
                        title = item.optString("title", "Unknown"),
                        artist = item.optString("artist", "Unknown"),
                        durationMs = 0,
                        uri = android.net.Uri.parse(item.optString("url", "")),
                        coverColor = Color(0xFF4CAF50)
                    ))
                }
            }
        } catch (e: Exception) { }
        return list
    }

    private fun searchNetease(query: String): List<Song> {
        val list = mutableListOf<Song>()
        try {
            val baseUrl = selectedSource.url.removeSuffix("/")
            // NeteaseCloudMusicApi 推荐使用 cloudsearch 获取更全的数据
            val url = "$baseUrl/cloudsearch?keywords=$query&limit=20&type=1"
            val response = client.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: ""
            
            val json = JSONObject(response)
            val resultObj = json.optJSONObject("result")
            if (resultObj != null) {
                val songs = resultObj.optJSONArray("songs")
                if (songs != null) {
                    for (i in 0 until songs.length()) {
                        val item = songs.getJSONObject(i)
                        val id = item.getLong("id")
                        val arArray = item.optJSONArray("ar")
                        val artistName = if (arArray != null && arArray.length() > 0) {
                            arArray.getJSONObject(0).optString("name", "Unknown")
                        } else {
                            "Unknown Artist"
                        }
                        
                        list.add(Song(
                            id = id,
                            title = item.optString("name", "Unknown"),
                            artist = artistName,
                            durationMs = item.optLong("dt", 0),
                            uri = android.net.Uri.parse("https://music.163.com/song/media/outer/url?id=$id.mp3"),
                            coverColor = Color(0xFFF44336)
                        ))
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    fun loadLocalSongs() {
        viewModelScope.launch {
            val songs = mutableListOf<Song>()
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
            )
            
            try {
                getApplication<Application>().contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                    while (cursor.moveToNext()) {
                        songs.add(Song(
                            id = cursor.getLong(idCol),
                            title = cursor.getString(titleCol),
                            artist = cursor.getString(artistCol),
                            durationMs = cursor.getLong(durationCol),
                            uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                        ))
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            
            allSongs = songs
            isLoading = false
            if (localFilteredSongs.isNotEmpty()) {
                syncPlayerQueue()
                if (currentSong == null) currentSong = localFilteredSongs[0]
            }
        }
    }

    private fun syncPlayerQueue() {
        val mediaItems = localFilteredSongs.map { MediaItem.fromUri(it.uri) }
        player.setMediaItems(mediaItems)
        player.prepare()
    }

    // 尝试为在线歌曲获取可播放的真实 URL
    private suspend fun getPlayableUrl(song: Song): android.net.Uri {
        val uriStr = song.uri.toString()
        // 判断是否为网易云或在线音源
        if (uriStr.contains("music.163.com") || song.coverColor == Color(0xFFF44336)) {
            return withContext(Dispatchers.IO) {
                try {
                    val baseUrl = selectedSource.url.removeSuffix("/")
                    // 尝试通过镜像 API 获取真实播放地址
                    val url = "$baseUrl/song/url/v1?id=${song.id}&level=standard"
                    val response = client.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: ""
                    val json = JSONObject(response)
                    val dataArray = json.optJSONArray("data")
                    if (dataArray != null && dataArray.length() > 0) {
                        val realUrl = dataArray.getJSONObject(0).optString("url", "")
                        if (realUrl.isNotBlank() && realUrl != "null") {
                            // 某些镜像 API 返回 http，我们尽量转成 https (如果支持)
                            val finalUrl = if (realUrl.startsWith("http://")) realUrl.replaceFirst("http://", "https://") else realUrl
                            return@withContext android.net.Uri.parse(finalUrl)
                        }
                    }
                    // 如果镜像 API 没返回有效链接，退而求其次使用官方外链
                    android.net.Uri.parse("https://music.163.com/song/media/outer/url?id=${song.id}.mp3")
                } catch (e: Exception) { 
                    e.printStackTrace()
                    song.uri 
                }
            }
        }
        return song.uri
    }

    fun playSong(song: Song) {
        viewModelScope.launch {
            isLoading = true
            val playableUri = if (onlineSongs.contains(song)) getPlayableUrl(song) else song.uri
            
            // 确定当前播放队列
            val isLocal = allSongs.any { it.id == song.id && it.uri == song.uri }
            val currentList = if (isLocal) localFilteredSongs else onlineSongs
            val index = currentList.indexOf(song)
            
            if (index != -1) {
                val mediaItems = currentList.map { 
                    val uri = if (it == song) playableUri else it.uri
                    MediaItem.fromUri(uri) 
                }
                player.setMediaItems(mediaItems)
                player.prepare()
                player.seekTo(index, 0)
                player.play()
                currentSong = song
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

    fun toggleFavorite(songId: Long) {
        userPlaylists = userPlaylists.map {
            if (it.id == "fav") {
                val newIds = if (it.songIds.contains(songId)) it.songIds - songId else it.songIds + songId
                it.copy(songIds = newIds)
            } else it
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
        userPlaylists = userPlaylists + Playlist(UUID.randomUUID().toString(), name)
    }

    fun getSongsInPlaylist(playlist: Playlist): List<Song> {
        return allSongs.filter { it.id in playlist.songIds }
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
