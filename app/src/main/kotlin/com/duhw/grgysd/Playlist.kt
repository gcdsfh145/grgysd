package com.duhw.grgysd

data class Playlist(
    val id: String,
    val name: String,
    val songIds: List<Long> = emptyList()
)
