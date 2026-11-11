package echo.music.iad1tya.ui.theme

import android.graphics.Bitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import com.materialkolor.score.Score
import echo.music.iad1tya.constants.AppFont
import echo.music.iad1tya.constants.SelectedFontKey
import echo.music.iad1tya.utils.rememberPreference

val DefaultThemeColor = Color(0xFFED5564)

val LightColorScheme =
  lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = LightInversePrimary,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    inverseSurface = LightPrimary,
    inverseOnSurface = LightBackground,
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = LightSurfaceContainerLowest,
    surfaceDim = LightOutlineVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
  )

val DarkColorScheme =
  darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = DarkInversePrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    inverseSurface = DarkPrimary,
    inverseOnSurface = DarkOnPrimary,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA),
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = DarkSurfaceContainerHighest,
    surfaceDim = DarkSurfaceContainerLowest,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
  )

@Composable
fun echomusicTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  pureBlack: Boolean = false,
  isMonochrome: Boolean = false,
  themeColor: Color = DefaultThemeColor,
  content: @Composable () -> Unit,
) {
  val selectedFontValue by rememberPreference(SelectedFontKey, AppFont.SYSTEM.value)
  val customFontPathValue by rememberPreference(echo.music.iad1tya.constants.CustomFontPathKey, "")

  val brandFont =
    remember(selectedFontValue, customFontPathValue) {
      when (AppFont.fromValue(selectedFontValue)) {
        AppFont.SYSTEM -> FontFamily.Default
        AppFont.GOOGLE_SANS -> GoogleSansFontFamily
        AppFont.SANS_FLEX -> SansFlexFontFamily
        AppFont.OUTFIT -> OutfitFontFamily
        AppFont.PLUS_JAKARTA_SANS -> PlusJakartaSansFontFamily
        AppFont.CUSTOM -> {
          try {
            if (customFontPathValue.isNotEmpty() && java.io.File(customFontPathValue).exists()) {
              val typeface = android.graphics.Typeface.createFromFile(customFontPathValue)
              FontFamily(androidx.compose.ui.text.font.Typeface(typeface))
            } else {
              FontFamily.Default
            }
          } catch (e: Exception) {
            FontFamily.Default
          }
        }
      }
    }

  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
  val typography = remember(brandFont) { getTypography(brandFont) }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = typography,
    shapes =
      MaterialTheme.shapes.copy(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(24.dp),
      ),
    content = content
  )
}

fun Bitmap.extractThemeColor(): Color {
  val colorsToPopulation =
    Palette.from(this).maximumColorCount(8).generate().swatches.associate {
      it.rgb to it.population
    }
  val rankedColors = Score.score(colorsToPopulation)
  return Color(rankedColors.first())
}

fun Bitmap.extractGradientColors(): List<Color> {
  val extractedColors =
    Palette.from(this).maximumColorCount(64).generate().swatches.associate {
      it.rgb to it.population
    }

  val orderedColors =
    Score.score(extractedColors, 2, 0xff4285f4.toInt(), true).sortedByDescending {
      Color(it).luminance()
    }

  return if (orderedColors.size >= 2) listOf(Color(orderedColors[0]), Color(orderedColors[1]))
  else listOf(Color(0xFF595959), Color(0xFF0D0D0D))
}

fun ColorScheme.pureBlack(apply: Boolean) = this

fun ColorScheme.toGrayscaleColorScheme(): ColorScheme = this

val ColorSaver =
  object : Saver<Color, Int> {
    override fun restore(value: Int): Color = Color(value)

    override fun SaverScope.save(value: Color): Int = value.toArgb()
  }
