package com.example.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Wjust macro Light Palette (Clean Ice Slate + Electric Cyan + Soft Violet)
val TealPrimaryLight = Color(0xFF0891B2)
val OnTealPrimaryLight = Color(0xFFFFFFFF)
val TealPrimaryContainerLight = Color(0xFFCFFAFE)
val OnTealPrimaryContainerLight = Color(0xFF083344)

val SlateSecondaryLight = Color(0xFF6D28D9)
val OnSlateSecondaryLight = Color(0xFFFFFFFF)
val SlateSecondaryContainerLight = Color(0xFFEDE9FE)
val OnSlateSecondaryContainerLight = Color(0xFF2E1065)

val IndigoTertiaryLight = Color(0xFF475569)
val OnIndigoTertiaryLight = Color(0xFFFFFFFF)
val IndigoTertiaryContainerLight = Color(0xFFE2E8F0)
val OnIndigoTertiaryContainerLight = Color(0xFF0F172A)

val ErrorLight = Color(0xFFDC2626)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFEE2E2)
val OnErrorContainerLight = Color(0xFF7F1D1D)

val BackgroundLight = Color(0xFFF6F9FC)
val OnBackgroundLight = Color(0xFF0F172A)
val SurfaceLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF0F172A)
val SurfaceVariantLight = Color(0xFFE6EEF6)
val OnSurfaceVariantLight = Color(0xFF475569)
val OutlineLight = Color(0xFF94A3B8)
val OutlineVariantLight = Color(0xFFCBD5E1)

// Wjust macro Dark Palette (Midnight Navy #0A1020 + Logo Cyan #22D3EE + Logo Lavender #A78BFA)
val TealPrimaryDark = Color(0xFF22D3EE)
val OnTealPrimaryDark = Color(0xFF04222E)
val TealPrimaryContainerDark = Color(0xFF12364A)
val OnTealPrimaryContainerDark = Color(0xFFA5F3FC)

val SlateSecondaryDark = Color(0xFFA78BFA)
val OnSlateSecondaryDark = Color(0xFF1F1147)
val SlateSecondaryContainerDark = Color(0xFF2A2152)
val OnSlateSecondaryContainerDark = Color(0xFFEDE9FE)

val IndigoTertiaryDark = Color(0xFF93C5FD)
val OnIndigoTertiaryDark = Color(0xFF0B2545)
val IndigoTertiaryContainerDark = Color(0xFF1A3152)
val OnIndigoTertiaryContainerDark = Color(0xFFDBEAFE)

val ErrorDark = Color(0xFFF87171)
val OnErrorDark = Color(0xFF450A0A)
val ErrorContainerDark = Color(0xFF7F1D1D)
val OnErrorContainerDark = Color(0xFFFEE2E2)

val BackgroundDark = Color(0xFF0A1020)
val OnBackgroundDark = Color(0xFFF1F5F9)
val SurfaceDark = Color(0xFF10192D)
val OnSurfaceDark = Color(0xFFF1F5F9)
val SurfaceVariantDark = Color(0xFF18243E)
val OnSurfaceVariantDark = Color(0xFF94A3B8)
val OutlineDark = Color(0xFF475569)
val OutlineVariantDark = Color(0xFF233252)

// Semantic Status & Lua Syntax Tokens
val StatusEmerald = Color(0xFF10B981)
val StatusAmber = Color(0xFFF59E0B)
val StatusCrimson = Color(0xFFEF4444)
val CodeSurfaceDark = Color(0xFF0B1222)
val CodeGutterDark = Color(0xFF131D33)
val SyntaxKeyword = Color(0xFF7DD3FC)
val SyntaxApi = Color(0xFF22D3EE)
val SyntaxString = Color(0xFFFCD34D)
val SyntaxNumber = Color(0xFFA78BFA)
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
