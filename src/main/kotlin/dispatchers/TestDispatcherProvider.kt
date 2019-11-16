package dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class TestDispatcherProvider : DispatcherProvider {
  override fun IO(): CoroutineDispatcher = Dispatchers.Unconfined
  override fun APK_REMOVER(): CoroutineDispatcher = Dispatchers.Unconfined
  override fun STATE_SAVER(): CoroutineDispatcher = Dispatchers.Unconfined
}