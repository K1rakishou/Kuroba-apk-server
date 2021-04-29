package service

import data.Apk
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import persister.ApkPersister
import persister.CommitPersister
import repository.ApkRepository
import repository.CommitRepository
import server.FatalHandlerException

open class DeleteApkFullyService : KoinComponent {
  private val logger = LoggerFactory.getLogger(DeleteApkFullyService::class.java)

  private val commitsRepository by inject<CommitRepository>()
  private val apksRepository by inject<ApkRepository>()
  private val commitPersister by inject<CommitPersister>()
  private val apkPersister by inject<ApkPersister>()

  open suspend fun deleteApks(apks: List<Apk>): Result<Unit> {
    // If one of these fails that means that the data is not consistent anymore so we can't do anything in such cause
    // and we need to terminate the server

    // Order matters!!!
    run {
      val result = commitPersister.removeCommitsByApkList(apks)
      if (result.isFailure) {
        throw FatalHandlerException(
          "Couldn't remove commits file from disk after unknown error during storing",
          result.exceptionOrNull()!!
        )
      }
    }

    run {
      val result = apkPersister.removeApks(apks)
      if (result.isFailure) {
        throw FatalHandlerException(
          "Couldn't remove apk file from disk after unknown error during storing",
          result.exceptionOrNull()!!
        )
      }
    }

    run {
      val result = apksRepository.removeApks(apks)
      if (result.isFailure) {
        throw FatalHandlerException(
          "Couldn't remove inserted apk after unknown error during storing",
          result.exceptionOrNull()!!
        )
      }
    }

    run {
      val result = commitsRepository.removeCommitsByApkList(apks)
      if (result.isFailure) {
        throw FatalHandlerException("Couldn't remove inserted commits by apk list", result.exceptionOrNull()!!)
      }
    }

    return Result.success(Unit)
  }

}