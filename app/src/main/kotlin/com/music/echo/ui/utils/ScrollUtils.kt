package echo.music.iad1tya.ui.utils

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

@Composable
fun LazyListState.isScrollingUp(): Boolean {
  return remember(this) {
      var previousIndex = firstVisibleItemIndex
      var previousScrollOffset = firstVisibleItemScrollOffset
      derivedStateOf {
        if (previousIndex != firstVisibleItemIndex) {
            previousIndex > firstVisibleItemIndex
          } else {
            previousScrollOffset >= firstVisibleItemScrollOffset
          }
          .also {
            previousIndex = firstVisibleItemIndex
            previousScrollOffset = firstVisibleItemScrollOffset
          }
      }
    }
    .value
}

@Composable
fun LazyGridState.isScrollingUp(): Boolean {
  return remember(this) {
      var previousIndex = firstVisibleItemIndex
      var previousScrollOffset = firstVisibleItemScrollOffset
      derivedStateOf {
        if (previousIndex != firstVisibleItemIndex) {
            previousIndex > firstVisibleItemIndex
          } else {
            previousScrollOffset >= firstVisibleItemScrollOffset
          }
          .also {
            previousIndex = firstVisibleItemIndex
            previousScrollOffset = firstVisibleItemScrollOffset
          }
      }
    }
    .value
}

@Composable
fun ScrollState.isScrollingUp(): Boolean {
  return remember(this) {
      var previousScrollOffset = value
      derivedStateOf { (previousScrollOffset >= value).also { previousScrollOffset = value } }
    }
    .value
}
