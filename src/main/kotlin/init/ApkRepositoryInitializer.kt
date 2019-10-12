package init

import data.Apk
import data.ApkFileName
import data.Commit
import fs.FileSystem
import io.vertx.core.logging.LoggerFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import org.koin.core.KoinComponent
import org.koin.core.inject
import repository.ApkRepository
import repository.CommitRepository
import server.ServerSettings

open class ApkRepositoryInitializer : Initializer, KoinComponent {
  private val logger = LoggerFactory.getLogger(ApkRepositoryInitializer::class.java)

  private val serverSettings by inject<ServerSettings>()
  private val fileSystem by inject<FileSystem>()
  private val commitRepository by inject<CommitRepository>()
  private val apkRepository by inject<ApkRepository>()

  @ExperimentalCoroutinesApi
  override suspend fun init(): Result<Unit> {
    val result = kotlin.runCatching {
      var totalInserted = 0

      commitRepository.getAllCommitsStream()
        .catch { error -> throw error }
        .onEach { commitsChunk ->
          val insertResult = apkRepository.insertApks(mapCommitsToApks(commitsChunk))
          if (insertResult.isFailure) {
            throw insertResult.exceptionOrNull()!!
          }

          totalInserted += commitsChunk.size
        }
        .collect()

      logger.info("Restored ${totalInserted} apks")
    }

    if (result.isFailure) {
      return result
    }

    return Result.success(Unit)
  }

  private suspend fun mapCommitsToApks(commits: List<Commit>): List<Apk> {
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
        apkFileName.uploadedOn
      )
    }

    return apkSet.toList()
  }
}