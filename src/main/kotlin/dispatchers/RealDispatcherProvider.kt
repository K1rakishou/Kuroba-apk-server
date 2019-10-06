package dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

class RealDispatcherProvider : DispatcherProvider {
  override fun IO(): CoroutineDispatcher = Dispatchers.IO
  override fun APK_REMOVER(): CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
}