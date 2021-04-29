package init

import data.Apk
import data.ApkFileName
import data.Commit
import data.json.ApkDownloadTimesState
import fs.FileSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import repository.ApkRepository
import repository.CommitRepository
import server.ServerSettings
import service.JsonConverter
import service.ServerStateSaverService.Companion.APK_DOWNLOADS_STATE_JSON_FILE_NAME
import java.io.File

open class ApkRepositoryInitializer : Initializer, KoinComponent {
  private val logger = LoggerFactory.getLogger(ApkRepositoryInitializer::class.java)

  private val serverSettings by inject<ServerSettings>()
  private val fileSystem by inject<FileSystem>()
  private val commitRepository by inject<CommitRepository>()
  private val apkRepository by inject<ApkRepository>()
  private val jsonConverter by inject<JsonConverter>()

  @ExperimentalCoroutinesApi
  override suspend fun init(): Result<Unit> {
    val result = kotlin.runCatching {
      var totalApksCount = 0
      var totalCommitsCount = 0

      val apkDownloadTimesMap = restoreApkDownloadTimes()

      commitRepository.getAllCommitsStream()
        .catch { error -> throw error }
        .collect { commitsChunk ->
          val apks = mapCommitsToApks(commitsChunk, apkDownloadTimesMap)
          val insertResult = apkRepository.insertApks(apks)
          if (insertResult.isFailure) {
            throw insertResult.exceptionOrNull()!!
          }

          totalApksCount += apks.size
          totalCommitsCount += commitsChunk.size
        }

      logger.info("Restored ${totalApksCount} apks and ${totalCommitsCount} commits")
    }

    if (result.isFailure) {
      return result
    }

    return Result.success(Unit)
  }

  private suspend fun restoreApkDownloadTimes(): Map<String, Int> {
    val file = File(serverSettings.apksDir, APK_DOWNLOADS_STATE_JSON_FILE_NAME)

    val fileExistsResult = fileSystem.fileExistsAsync(file.absolutePath)
    val fileExists = if (fileExistsResult.isFailure) {
      logger.error(
        "Error while trying to figure out whether ${APK_DOWNLOADS_STATE_JSON_FILE_NAME} file exists",
        fileExistsResult.exceptionOrNull()
      )

      return emptyMap()
    } else {
      fileExistsResult.getOrNull()!!
    }

    if (!fileExists) {
      logger.error("File ${APK_DOWNLOADS_STATE_JSON_FILE_NAME} does not exist, nothing to load")
      return emptyMap()
    }

    val fileReadResult = fileSystem.readFileAsStringAsync(file.absolutePath)
    val fileContent = if (fileReadResult.isFailure) {
      logger.error(
        "Error while trying to read ${APK_DOWNLOADS_STATE_JSON_FILE_NAME} file exists",
        fileReadResult.exceptionOrNull()
      )

      return emptyMap()
    } else {
      fileReadResult.getOrNull()
    }

    if (fileContent == null || fileContent.isEmpty()) {
      logger.error("File ${APK_DOWNLOADS_STATE_JSON_FILE_NAME} is empty, nothing to load")
      return emptyMap()
    }

    val apkDownloadTimesState = jsonConverter.fromJson<ApkDownloadTimesState>(fileContent)
    val resultMap = mutableMapOf<String, Int>()

    apkDownloadTimesState.apkDownloadTimesList.forEach { apkDownloadTimes ->
      resultMap.put(apkDownloadTimes.apkUuid, apkDownloadTimes.downloadedTimes)
    }

    val loadedCount = apkDownloadTimesState.apkDownloadTimesList.count { apkDownloadTimes ->
      apkDownloadTimes.downloadedTimes > 0
    }

    logger.info("Loaded ${loadedCount} apk download times")
    return resultMap
  }

  private suspend fun mapCommitsToApks(
    commits: List<Commit>,
    apkDownloadTimesMap: Map<String, Int>
  ): List<Apk> {
    if (commits.isEmpty()) {
      return emptyList()
    }

    val apkSet = HashSet<Apk>(32)
    for (commit in commits) {
      if (!commit.head) {
        continue
      }

      val result = fileSystem.findApkFileAsync(
        serverSettings.apksDir.absolutePath,
        commit.apkUuid
      )

      if (result.isFailure) {
        continue
      }

      val path = result.getOrNull()
        ?: continue

      val apkFileName = ApkFileName.fromString(path)
        ?: continue

      if (apkFileName.getUuid() != commit.apkUuid) {
        continue
      }

      apkSet += Apk(
        commit.apkUuid,
        commit.apkVersion,
        path,
        apkFileName.uploadedOn,
        apkDownloadTimesMap.getOrDefault(commit.apkUuid, 0)
      )
    }

    return apkSet.toList()
  }
}