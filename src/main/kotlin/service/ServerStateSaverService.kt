package service

import data.json.ApkDownloadTimes
import data.json.ApkDownloadTimesState
import dispatchers.DispatcherProvider
import fs.FileSystem
import io.vertx.core.buffer.Buffer
import io.vertx.core.file.CopyOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.isActive
import org.joda.time.Instant
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import repository.ApkRepository
import server.ServerSettings
import util.TimeUtils
import java.io.File
import kotlin.coroutines.CoroutineContext

class ServerStateSaverService(
  private val dispatcherProvider: DispatcherProvider,
  private val serverSettings: ServerSettings
) : KoinComponent, CoroutineScope {
  private val logger = LoggerFactory.getLogger(ServerStateSaverService::class.java)

  private val job = SupervisorJob()
  private val timeUtils by inject<TimeUtils>()
  private val apkRepository by inject<ApkRepository>()
  private val jsonConverter by inject<JsonConverter>()
  private val fileSystem by inject<FileSystem>()

  override val coroutineContext: CoroutineContext
    get() = job + dispatcherProvider.STATE_SAVER()

  @UseExperimental(ObsoleteCoroutinesApi::class)
  private val stateSaverActor = actor<Boolean>(context = coroutineContext, capacity = 1) {
    var lastTimeCheck = Instant.now()

    for (isForced in channel) {
      if (!isActive) {
        break
      }

      val now = Instant.now()
      val nextRunTime = lastTimeCheck.plus(serverSettings.serverStateSavingInterval)

      if (!timeUtils.isItTimeToSaveServerState(now, nextRunTime)) {
        if (!isForced) {
          continue
        } else {
          logger.info("Forcing server state saving")
        }
      }

      lastTimeCheck = now
      startServerStateSaving()
    }

    logger.warn("ServerStateSaverService actor was terminated!")
  }

  open suspend fun newSaveServerStateRequest(forced: Boolean) {
    stateSaverActor.offer(forced)
  }

  private suspend fun startServerStateSaving() {
    try {
      logger.info("startServerStateSaving started")

      saveApkDownloadTimes()

      logger.info("startServerStateSaving finished")
    } catch (error: Throwable) {
      logger.error("Unknown error when running startServerStateSaving()", error)
    }
  }

  private suspend fun saveApkDownloadTimes() {
    try {
      logger.info("saveApkDownloadTimes started")

      val apkDownloadsStateJsonNewFile = File(
        serverSettings.apksDir,
        APK_DOWNLOADS_STATE_JSON_NEW_FILE_NAME
      )

      val oldFileExistsResult = fileSystem.fileExistsAsync(apkDownloadsStateJsonNewFile.absolutePath)
      val oldFileExists = if (oldFileExistsResult.isFailure) {
        throw oldFileExistsResult.exceptionOrNull()!!
      } else {
        oldFileExistsResult.getOrNull()!!
      }

      if (oldFileExists) {
        val deleteOldFileResult = fileSystem.removeFileAsync(apkDownloadsStateJsonNewFile.absolutePath)
        if (deleteOldFileResult.isFailure) {
          throw deleteOldFileResult.exceptionOrNull()!!
        }
      }

      val createFileResult = fileSystem.createFile(apkDownloadsStateJsonNewFile.absolutePath)
      if (createFileResult.isFailure) {
        throw createFileResult.exceptionOrNull()!!
      }

      val getAllApksResult = apkRepository.getAllApks()
      if (getAllApksResult.isFailure) {
        throw getAllApksResult.exceptionOrNull()!!
      }

      val apkDownloadTimesList = getAllApksResult.getOrNull()!!.map { apk ->
        ApkDownloadTimes(apk.apkUuid, apk.downloadedTimes)
      }

      val apkDownloadTimesState = ApkDownloadTimesState(
        apkDownloadTimesList = apkDownloadTimesList
      )

      val json = jsonConverter.toJson(apkDownloadTimesState)
      val writeFileResult = fileSystem.writeFileAsync(
        apkDownloadsStateJsonNewFile.absolutePath,
        Buffer.buffer(json)
      )

      if (writeFileResult.isFailure) {
        throw writeFileResult.exceptionOrNull()!!
      }

      val apkDownloadsStateJsonFile = File(
        serverSettings.apksDir,
        APK_DOWNLOADS_STATE_JSON_FILE_NAME
      )

      val moveFileResult = fileSystem.moveFile(
        apkDownloadsStateJsonNewFile.absolutePath,
        apkDownloadsStateJsonFile.absolutePath,
        CopyOptions().setReplaceExisting(true).setAtomicMove(true)
      )

      if (moveFileResult.isFailure) {
        throw moveFileResult.exceptionOrNull()!!
      }

      logger.info("saveApkDownloadTimes finished")
    } catch (error: Throwable) {
      logger.error("Unknown error when running saveApkDownloadTimes()", error)
    }

  }

  companion object {
    const val APK_DOWNLOADS_STATE_JSON_FILE_NAME = "apk_downloads_state.json"
    private const val APK_DOWNLOADS_STATE_JSON_NEW_FILE_NAME = "apk_downloads_state_new.json"
  }
}