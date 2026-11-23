package com.example.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Precision Modern Utility Palette (Material 3 Adaptive Fallback)
val TealPrimaryLight = Color(0xFF006A6A)
val OnTealPrimaryLight = Color(0xFFFFFFFF)
val TealPrimaryContainerLight = Color(0xFF9CF1F0)
val OnTealPrimaryContainerLight = Color(0xFF002020)

val SlateSecondaryLight = Color(0xFF4A6363)
val OnSlateSecondaryLight = Color(0xFFFFFFFF)
val SlateSecondaryContainerLight = Color(0xFFCCE8E7)
val OnSlateSecondaryContainerLight = Color(0xFF051F1F)

val IndigoTertiaryLight = Color(0xFF4B607C)
val OnIndigoTertiaryLight = Color(0xFFFFFFFF)
val IndigoTertiaryContainerLight = Color(0xFFD3E4FF)
val OnIndigoTertiaryContainerLight = Color(0xFF041C35)

val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val BackgroundLight = Color(0xFFF4FBF9)
val OnBackgroundLight = Color(0xFF161D1D)
val SurfaceLight = Color(0xFFF4FBF9)
val OnSurfaceLight = Color(0xFF161D1D)
val SurfaceVariantLight = Color(0xFFDAE5E4)
val OnSurfaceVariantLight = Color(0xFF3F4948)
val OutlineLight = Color(0xFF6F7979)
val OutlineVariantLight = Color(0xFFBEC9C8)

// Dark Mode Tokens (Deep Charcoal Slate + Electric Cyan-Teal)
val TealPrimaryDark = Color(0xFF4CDADA)
val OnTealPrimaryDark = Color(0xFF003737)
val TealPrimaryContainerDark = Color(0xFF004F4F)
val OnTealPrimaryContainerDark = Color(0xFF9CF1F0)

val SlateSecondaryDark = Color(0xFFB0CCCB)
val OnSlateSecondaryDark = Color(0xFF1B3534)
val SlateSecondaryContainerDark = Color(0xFF324B4B)
val OnSlateSecondaryContainerDark = Color(0xFFCCE8E7)

val IndigoTertiaryDark = Color(0xFFB3C8E8)
val OnIndigoTertiaryDark = Color(0xFF1C314B)
val IndigoTertiaryContainerDark = Color(0xFF334863)
val OnIndigoTertiaryContainerDark = Color(0xFFD3E4FF)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)

val BackgroundDark = Color(0xFF0E1515)
val OnBackgroundDark = Color(0xFFDDE4E3)
val SurfaceDark = Color(0xFF0E1515)
val OnSurfaceDark = Color(0xFFDDE4E3)
val SurfaceVariantDark = Color(0xFF3F4948)
val OnSurfaceVariantDark = Color(0xFFBEC9C8)
val OutlineDark = Color(0xFF889392)
val OutlineVariantDark = Color(0xFF3F4948)

// Semantic Status & Lua Syntax Tokens
val StatusEmerald = Color(0xFF10B981)
val StatusAmber = Color(0xFFF59E0B)
val StatusCrimson = Color(0xFFEF4444)
val CodeSurfaceDark = Color(0xFF11191E)
val CodeGutterDark = Color(0xFF18242B)
val SyntaxKeyword = Color(0xFF7DD3FC)
val SyntaxApi = Color(0xFF2DD4BF)
val SyntaxString = Color(0xFFFCD34D)
val SyntaxNumber = Color(0xFFC4B5FD)
val SyntaxComment = Color(0xFF7B8E9B)

val TapLuaLightColorScheme = lightColorScheme(
    primary = TealPrimaryLight,
    onPrimary = OnTealPrimaryLight,
    primaryContainer = TealPrimaryContainerLight,
    onPrimaryContainer = OnTealPrimaryContainerLight,
    secondary = SlateSecondaryLight,
    onSecondary = OnSlateSecondaryLight,
    secondaryContainer = SlateSecondaryContainerLight,
    onSecondaryContainer = OnSlateSecondaryContainerLight,
    tertiary = IndigoTertiaryLight,
    onTertiary = OnIndigoTertiaryLight,
    tertiaryContainer = IndigoTertiaryContainerLight,
    onTertiaryContainer = OnIndigoTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
)

val TapLuaDarkColorScheme = darkColorScheme(
    primary = TealPrimaryDark,
    onPrimary = OnTealPrimaryDark,
    primaryContainer = TealPrimaryContainerDark,
    onPrimaryContainer = OnTealPrimaryContainerDark,
    secondary = SlateSecondaryDark,
    onSecondary = OnSlateSecondaryDark,
    secondaryContainer = SlateSecondaryContainerDark,
    onSecondaryContainer = OnSlateSecondaryContainerDark,
    tertiary = IndigoTertiaryDark,
    onTertiary = OnIndigoTertiaryDark,
    tertiaryContainer = IndigoTertiaryContainerDark,
    onTertiaryContainer = OnIndigoTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
)
