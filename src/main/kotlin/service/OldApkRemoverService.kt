package service

import ServerSettings
import dispatchers.DispatcherProvider
import io.vertx.core.logging.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.isActive
import org.joda.time.Instant
import org.koin.core.KoinComponent
import org.koin.core.inject
import repository.ApkRepository
import kotlin.coroutines.CoroutineContext

open class OldApkRemoverService(
  private val dispatcherProvider: DispatcherProvider
) : KoinComponent, CoroutineScope {
  private val logger = LoggerFactory.getLogger(OldApkRemoverService::class.java)

  private val job = SupervisorJob()
  private val apkRepository by inject<ApkRepository>()
  private val serverSettings by inject<ServerSettings>()
  private val deleteApkFullyService by inject<DeleteApkFullyService>()
  private val apksToDeleteCount by lazy { (serverSettings.maxApkFiles / 100) * 10 } // Delete 10% of the apks

  override val coroutineContext: CoroutineContext
    get() = job + dispatcherProvider.APK_REMOVER()

  @UseExperimental(ObsoleteCoroutinesApi::class)
  private var myActor = actor<Unit> {
    var lastTimeCheck = Instant.now()

    for (event in channel) {
      val now = Instant.now()
      val nextRunTime = lastTimeCheck.plus(serverSettings.apkDeletionInterval)

      if (!isActive) {
        break
      }

      if (!now.isAfter(nextRunTime)) {
        continue
      }

      deleteOldApksIfThereAreAny()
      lastTimeCheck = now
    }
  }

  open suspend fun onNewApkUploaded() {
    myActor.offer(Unit)
  }

  private suspend fun deleteOldApksIfThereAreAny() {
    try {
      val apksCountResult = apkRepository.getTotalApksCount()
      if (apksCountResult.isFailure) {
        logger.error("Couldn't get total apks count", apksCountResult.exceptionOrNull()!!)
        return
      }

      val apksCount = apksCountResult.getOrNull()!!
      if (apksCount < serverSettings.maxApkFiles) {
        logger.info("Nothing to delete, apksCount = $apksCount, maxApkFiles = ${serverSettings.maxApkFiles}")
        return
      }

      logger.info("apksCount = ${apksCount}, maxApkFiles = ${serverSettings.maxApkFiles}, apksToDeleteCount = $apksToDeleteCount")
      val oldestApksResult = apkRepository.getOldestApks(apksToDeleteCount)
      if (oldestApksResult.isFailure) {
        logger.error("Couldn't get oldest apks", oldestApksResult.exceptionOrNull()!!)
        return
      }

      val deleteApksResult = deleteApkFullyService.deleteApks(oldestApksResult.getOrNull()!!)
      if (deleteApksResult.isFailure) {
        logger.error("Couldn't delete apks", deleteApksResult.exceptionOrNull()!!)
        return
      }

      logger.info("Successfully deleted $apksToDeleteCount oldest apks")
    } catch (error: Throwable) {
      logger.error("Unknown error when running deleteOldApksIfThereAny()", error)
    }
  }
}