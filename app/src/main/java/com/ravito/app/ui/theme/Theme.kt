package com.ravito.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.ravito.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Style « premium épuré » : fond gris très clair façon iPhone, cartes blanches arrondies,
 * une seule couleur d'accent (vert Ravito), texte presque noir, gris doux pour le secondaire.
 */
val Green = Color(0xFF0B8A5A)
val GreenSoft = Color(0xFFE3F5EC)
val Ink = Color(0xFF101418)
val InkSoft = Color(0xFF6E7781)
val Canvas = Color(0xFFF2F3F6)
val Hairline = Color(0xFFE6E8EC)
val AmberSoft = Color(0xFFFFF3DC)
val Amber = Color(0xFFB7791F)
val Danger = Color(0xFFD93025)

private val colors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = GreenSoft,
    onPrimaryContainer = Color(0xFF05402A),
    secondary = Amber,
    onSecondary = Color.White,
    // Boutons secondaires et touches du code : blancs sur fond gris, comme sur iPhone.
    secondaryContainer = Color.White,
    onSecondaryContainer = Ink,
    tertiary = Ink,
    background = Canvas,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0F1F4),
    onSurfaceVariant = InkSoft,
    surfaceContainer = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFF5F6F8),
    surfaceContainerHighest = Color(0xFFEDEEF1),
    outline = Color(0xFFD5D8DD),
    outlineVariant = Hairline,
    error = Danger,
)

/** Police intégrée (Inter) : même rendu net sur tous les téléphones, quelle que soit la police du système. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val base = Typography().let { t ->
    fun TextStyle.inter() = copy(fontFamily = Inter)
    Typography(
        displayLarge = t.displayLarge.inter(), displayMedium = t.displayMedium.inter(), displaySmall = t.displaySmall.inter(),
        headlineLarge = t.headlineLarge.inter(), headlineMedium = t.headlineMedium.inter(), headlineSmall = t.headlineSmall.inter(),
        titleLarge = t.titleLarge.inter(), titleMedium = t.titleMedium.inter(), titleSmall = t.titleSmall.inter(),
        bodyLarge = t.bodyLarge.inter(), bodyMedium = t.bodyMedium.inter(), bodySmall = t.bodySmall.inter(),
        labelLarge = t.labelLarge.inter(), labelMedium = t.labelMedium.inter(), labelSmall = t.labelSmall.inter(),
    )
}

private val typography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.8).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.sp),
    bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    labelLarge = base.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    labelMedium = base.labelMedium.copy(fontSize = 13.sp, letterSpacing = 0.sp),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Gros montant mis en avant (accueil), façon application de paiement. */
val AmountHero = TextStyle(fontFamily = Inter, fontSize = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp)

@Composable
fun RavitoTheme(content: @Composable () -> Unit) {
    // Le texte suit le réglage « taille de police » du téléphone, mais plafonné à +15 %
    // pour que la mise en page reste nette (sinon les libellés sont coupés).
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale.coerceAtMost(1.15f)),
    ) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
    }
}
