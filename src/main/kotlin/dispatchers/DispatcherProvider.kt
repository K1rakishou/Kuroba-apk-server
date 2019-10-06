package dispatchers

import kotlinx.coroutines.CoroutineDispatcher

interface DispatcherProvider {
  fun IO(): CoroutineDispatcher
  fun APK_REMOVER(): CoroutineDispatcher
}