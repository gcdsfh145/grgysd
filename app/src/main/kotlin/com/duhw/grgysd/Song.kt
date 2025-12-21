package com.duhw.grgysd

import android.net.Uri
import androidx.compose.ui.graphics.Color

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: Uri,
    val coverColor: Color = Color((0xFF000000..0xFFFFFFFF).random())
)