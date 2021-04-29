package service

import dispatchers.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.isActive
import org.joda.time.Duration
import org.joda.time.Instant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import repository.ApkRepository
import server.ServerSettings
import util.TimeUtils
import kotlin.coroutines.CoroutineContext

open class OldApkRemoverService(
  private val dispatcherProvider: DispatcherProvider
) : KoinComponent, CoroutineScope {
  private val logger = LoggerFactory.getLogger(OldApkRemoverService::class.java)

  private val job = SupervisorJob()
  private val apkRepository by inject<ApkRepository>()
  private val serverSettings by inject<ServerSettings>()
  private val deleteApkFullyService by inject<DeleteApkFullyService>()
  private val timeUtils by inject<TimeUtils>()

  // Delete 25% of the apks
  private val apksToDeleteCount by lazy { ((serverSettings.maxApkFiles.toFloat() / 100f) * 25f).toInt() }

  override val coroutineContext: CoroutineContext
    get() = job + dispatcherProvider.APK_REMOVER()

  @UseExperimental(ObsoleteCoroutinesApi::class)
  private val myActor = actor<Unit>(context = coroutineContext, capacity = 1) {
    // We subtract an hour here so that we can start deleting old apks right away
    var lastTimeCheck = Instant.now().minus(Duration.standardHours(1))

    for (event in channel) {
      if (!isActive) {
        break
      }

      val now = Instant.now()
      val nextRunTime = lastTimeCheck.plus(serverSettings.apkDeletionInterval)

      if (!timeUtils.isItTimeToDeleteOldApks(now, nextRunTime)) {
        logger.info("isItTimeToDeleteOldApks() returned false, now: $now, nextRunTime: $nextRunTime")
        continue
      }

      lastTimeCheck = now
      deleteOldApksIfThereAreAny()
    }

    logger.warn("OldApkRemoverService actor was terminated!")
  }

  open suspend fun onNewApkUploaded() {
    logger.info("onNewApkUploaded()")
    myActor.offer(Unit)
  }

  private suspend fun deleteOldApksIfThereAreAny() {
    try {
      logger.info("deleteOldApksIfThereAreAny started")

      val apksCountResult = apkRepository.getTotalApksCount()
      if (apksCountResult.isFailure) {
        logger.error("Couldn't get total apks count", apksCountResult.exceptionOrNull()!!)
        return
      }

      val apksCount = apksCountResult.getOrNull()!!
      if (apksCount <= serverSettings.maxApkFiles) {
        logger.info("Nothing to delete, apksCount = $apksCount, maxApkFiles = ${serverSettings.maxApkFiles}")
        return
      }

      logger.info("apksCount = ${apksCount}," +
        " maxApkFiles = ${serverSettings.maxApkFiles}," +
        " apksToDeleteCount = $apksToDeleteCount")

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
      logger.info("deleteOldApksIfThereAreAny finished")
    } catch (error: Throwable) {
      logger.error("Unknown error when running deleteOldApksIfThereAny()", error)
    }
  }
}