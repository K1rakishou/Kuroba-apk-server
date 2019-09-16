package repository

import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.KoinComponent
import org.koin.core.inject
import java.sql.Connection
import kotlin.coroutines.CoroutineContext

abstract class BaseRepository : KoinComponent, CoroutineScope {
  protected val database by inject<Database>()
  private val job = SupervisorJob()

  override val coroutineContext: CoroutineContext
    get() = Dispatchers.IO + job

  protected suspend fun <T> dbWrite(
    transactionIsolation: Int = Connection.TRANSACTION_REPEATABLE_READ,
    repetitionAttempts: Int = 5,
    func: suspend () -> T
  ): Result<T> {
    return dbQueryInternal(transactionIsolation, repetitionAttempts, func)
  }

  protected suspend fun <T> dbRead(
    transactionIsolation: Int = Connection.TRANSACTION_READ_COMMITTED,
    repetitionAttempts: Int = 5,
    func: suspend () -> T
  ): Result<T> {
    return dbQueryInternal(transactionIsolation, repetitionAttempts, func)
  }

  private suspend fun <T> dbQueryInternal(
    transactionIsolation: Int,
    repetitionAttempts: Int,
    func: suspend () -> T
  ): Result<T> {
    return withContext(coroutineContext) {
      runCatching {
        transaction(transactionIsolation, repetitionAttempts, database) {
          runBlocking {
            func()
          }
        }
      }
    }
  }

}