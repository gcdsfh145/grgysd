package com.duhw.grgysd.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun ComposeEmptyActivityTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    seedColor: Color? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    val colorScheme = when {
        // 1. 莫奈动态取色 (Android 12+)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // 2. 自定义基色生成的配色方案
        seedColor != null -> {
            // 在实际复杂项目中可以用 ColorScheme 进行更细致的生成
            // 这里我们根据 seedColor 动态调整
            if (darkTheme) {
                darkColorScheme(primary = seedColor, secondary = seedColor.copy(alpha = 0.8f))
            } else {
                lightColorScheme(primary = seedColor, secondary = seedColor.copy(alpha = 0.8f))
            }
        }
        // 3. 默认配色
        darkTheme -> darkColorScheme(primary = Purple80)
        else -> lightColorScheme(primary = Purple40)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
