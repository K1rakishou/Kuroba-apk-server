package dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class RealDispatcherProvider : DispatcherProvider {
  override fun IO(): CoroutineDispatcher = Dispatchers.IO
}