package echo.music.iad1tya.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import echo.music.iad1tya.extensions.toEnum
import java.util.Collections
import java.util.WeakHashMap
import kotlin.properties.ReadOnlyProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private val dataStoreCacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val cachedPreferencesMap =
  Collections.synchronizedMap(WeakHashMap<DataStore<Preferences>, Preferences>())
private val observedDataStores =
  Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<DataStore<Preferences>, Boolean>()))

fun DataStore<Preferences>.currentPreferences(): Preferences {
  cachedPreferencesMap[this]?.let { return it }
  synchronized(cachedPreferencesMap) {
    cachedPreferencesMap[this]?.let { return it }
    val initial =
      runBlocking(Dispatchers.IO) {
        try {
          data.first()
        } catch (e: Exception) {
          emptyPreferences()
        }
      }
    cachedPreferencesMap[this] = initial
    if (observedDataStores.add(this)) {
      val store = this
      dataStoreCacheScope.launch {
        try {
          store.data.collect { prefs -> cachedPreferencesMap[store] = prefs }
        } catch (_: Exception) {}
      }
    }
    return initial
  }
}

operator fun <T> DataStore<Preferences>.get(key: Preferences.Key<T>): T? =
  try {
    currentPreferences()[key]
  } catch (e: Exception) {
    null
  }

fun <T> DataStore<Preferences>.get(
  key: Preferences.Key<T>,
  defaultValue: T,
): T =
  (try {
    currentPreferences()[key]
  } catch (e: Exception) {
    null
  }) ?: defaultValue

fun <T> preference(
  context: Context,
  key: Preferences.Key<T>,
  defaultValue: T,
) =
  ReadOnlyProperty<Any?, T> { _, _ ->
    (try {
      context.dataStore[key]
    } catch (e: Exception) {
      null
    }) ?: defaultValue
  }

inline fun <reified T : Enum<T>> enumPreference(
  context: Context,
  key: Preferences.Key<String>,
  defaultValue: T,
) =
  ReadOnlyProperty<Any?, T> { _, _ ->
    (try {
        context.dataStore[key]
      } catch (e: Exception) {
        null
      })
      .toEnum(defaultValue)
  }
