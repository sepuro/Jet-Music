package echo.music.iad1tya.models

import androidx.compose.runtime.Immutable
import com.music.innertube.models.YTItem
import echo.music.iad1tya.db.entities.LocalItem

@Immutable
data class SimilarRecommendation(
  val title: LocalItem,
  val items: List<YTItem>,
)
