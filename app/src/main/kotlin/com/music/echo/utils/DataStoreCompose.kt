package echo.music.iad1tya.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import echo.music.iad1tya.extensions.toEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@PublishedApi
internal object ComposePreferencesStore {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  val snapshotState = mutableStateOf<Preferences?>(null)

  @Volatile
  private var initialized = false

  fun ensureInitialized(context: Context): Preferences {
    snapshotState.value?.let { return it }
    synchronized(this) {
      snapshotState.value?.let { return it }
      val appContext = context.applicationContext ?: context
      val initial = appContext.dataStore.currentPreferences()
      snapshotState.value = initial
      if (!initialized) {
        initialized = true
        scope.launch {
          appContext.dataStore.data.collect { prefs ->
            snapshotState.value = prefs
          }
        }
      }
      return initial
    }
  }
}

@Composable
fun <T> rememberPreference(
  key: Preferences.Key<T>,
  defaultValue: T,
): MutableState<T> {
  val context = LocalContext.current
  remember { ComposePreferencesStore.ensureInitialized(context) }

  val state =
    remember(key, defaultValue) {
      derivedStateOf {
        val prefs = ComposePreferencesStore.snapshotState.value
        val value =
          try {
            prefs?.get(key)
          } catch (e: Exception) {
            null
          }
        @Suppress("UNCHECKED_CAST")
        if (
          value != null &&
            defaultValue != null &&
            (defaultValue !is Set<*>) &&
            value::class != defaultValue::class
        ) {
          defaultValue
        } else {
          (value ?: defaultValue) as T
        }
      }
    }

  return remember(key) {
    val appContext = context.applicationContext ?: context
    object : MutableState<T> {
      override var value: T
        get() = state.value
        set(value) {
          ComposePreferencesStore.scope.launch { appContext.dataStore.edit { it[key] = value } }
        }

      override fun component1() = value

      override fun component2(): (T) -> Unit = { value = it }
    }
  }
}

@Composable
inline fun <reified T : Enum<T>> rememberEnumPreference(
  key: Preferences.Key<String>,
  defaultValue: T,
): MutableState<T> {
  val context = LocalContext.current
  remember { ComposePreferencesStore.ensureInitialized(context) }

  val state =
    remember(key, defaultValue) {
      derivedStateOf {
        val prefs = ComposePreferencesStore.snapshotState.value
        val value =
          try {
            prefs?.get(key)
          } catch (e: Exception) {
            null
          }
        (if (value != null && value !is String) null else value as String?).toEnum(
          defaultValue = defaultValue
        )
      }
    }

  return remember(key) {
    val appContext = context.applicationContext ?: context
    object : MutableState<T> {
      override var value: T
        get() = state.value
        set(value) {
          ComposePreferencesStore.scope.launch {
            appContext.dataStore.edit { it[key] = value.name }
          }
        }

      override fun component1() = value

      override fun component2(): (T) -> Unit = { value = it }
    }
  }
}
