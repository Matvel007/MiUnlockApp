package com.miunlock.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalLanguage = staticCompositionLocalOf { "ru" }

@Composable
fun tr(ru: String, en: String): String = if (LocalLanguage.current == "en") en else ru
