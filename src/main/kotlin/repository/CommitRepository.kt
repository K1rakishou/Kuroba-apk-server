package repository

import data.Apk
import data.ApkFileName
import data.Commit
import db.CommitTable
import dispatchers.DispatcherProvider
import extensions.selectFilterDuplicates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.jetbrains.exposed.sql.*
import org.koin.core.component.inject
import org.slf4j.LoggerFactory
import parser.CommitParser

open class CommitRepository(
  dispatcherProvider: DispatcherProvider
) : BaseRepository(dispatcherProvider) {
  private val logger = LoggerFactory.getLogger(CommitRepository::class.java)
  private val commitParser by inject<CommitParser>()

  open suspend fun insertCommits(apkVersion: Long, latestCommits: String): Result<List<Commit>> {
    if (latestCommits.isEmpty()) {
      return Result.failure(IllegalArgumentException("latestCommits is empty"))
    }

    val parsedCommits = commitParser.parseCommits(apkVersion, latestCommits)
    if (parsedCommits.isEmpty()) {
      // We must have at least one commit parsed, otherwise we fail the build
      logger.info("Couldn't parse any commits, latestCommits = $latestCommits")
      return Result.failure(CommitsParseException())
    }

    val insertResult = insertCommits(parsedCommits)
    if (insertResult.isFailure) {
      logger.error("Couldn't insert new commits")
      return Result.failure(insertResult.exceptionOrNull()!!)
    }

    return Result.success(insertResult.getOrNull()!!)
  }

  suspend fun getCommitsByApkVersion(apkFileName: ApkFileName): Result<List<Commit>> {
    return dbRead {
      CommitTable.select {
        CommitTable.groupUuid.eq(apkFileName.getUuid())
      }
        .orderBy(CommitTable.committedAt, SortOrder.DESC)
        .map { resultRow -> Commit.fromResultRow(resultRow) }
    }
  }

  suspend fun getLatestCommitHash(): Result<String?> {
    return dbRead {
      val resultRow = CommitTable.selectAll()
        .orderBy(CommitTable.committedAt, SortOrder.DESC)
        .limit(1)
        .firstOrNull()
        ?: return@dbRead null

      return@dbRead resultRow[CommitTable.hash]
    }
  }

  private suspend fun insertCommits(commits: List<Commit>): Result<List<Commit>> {
    require(commits.isNotEmpty()) { "insertCommits() commits must not be empty" }

    val headCommitsCount = commits
      .filter { it.head }
      .distinctBy { commit -> commit.apkUuid }.size

    require(headCommitsCount == 1) {
      "There are at least two commits that have different apkUuids"
    }

    return dbWrite {
      val apkVersions = commits.map { commit -> commit.apkVersion }
      val hashes = commits.map { commit -> commit.commitHash }
      val groupUuid = checkNotNull(commits.firstOrNull { commit -> commit.head }?.apkUuid) {
        "Commits group does not have the head commit"
      }

      val filtered = CommitTable.selectFilterDuplicates(commits, {
        CommitTable.apkVersion.inList(apkVersions) and
          CommitTable.hash.inList(hashes)
      }, { resultRow ->
        Commit.fromResultRow(resultRow)
      }).filter { commit -> validateCommit(commit) }

      return@dbWrite CommitTable.batchInsert(filtered) { commit ->
        this[CommitTable.uuid] = commit.apkUuid
        this[CommitTable.groupUuid] = groupUuid
        this[CommitTable.hash] = commit.commitHash
        this[CommitTable.apkVersion] = commit.apkVersion
        this[CommitTable.committedAt] = commit.committedAt
        this[CommitTable.description] = trimDescription(commit.description)
        this[CommitTable.head] = commit.head
      }.map { resultRow -> Commit.fromResultRow(resultRow) }
    }
  }

  private fun trimDescription(description: String): String {
    val maxLength = Commit.MAX_DESCRIPTION_LENGTH - ELLIPSIZE.length

    if (description.length < maxLength) {
      return description
    }

    return description.take(maxLength) + ELLIPSIZE
  }

  private fun validateCommit(commit: Commit): Boolean {
    if (commit.commitHash.isBlank() || commit.commitHash.length > Commit.MAX_HASH_LENGTH) {
      logger.error("Commit ${commit} has incorrect commitHash")
      return false
    }

    return true
  }

  open suspend fun removeCommitsByApkList(apks: List<Apk>): Result<Unit> {
    require(apks.isNotEmpty()) { "removeCommitsByApkList() apks must not be empty" }

    return dbWrite {
      val apkUuidSet = apks.map { apk -> apk.apkUuid }.toSet()

      CommitTable.deleteWhere {
        CommitTable.groupUuid.inList(apkUuidSet)
      }

      return@dbWrite
    }
  }

  suspend fun getCommitsByUuid(commitUuid: String): Result<List<Commit>> {
    if (commitUuid.isBlank()) {
      return Result.failure(CommitUuidIsBlank())
    }

    return dbRead {
      CommitTable.select {
        CommitTable.groupUuid.eq(commitUuid)
      }
        .orderBy(CommitTable.committedAt, SortOrder.DESC)
        .map { resultRow -> Commit.fromResultRow(resultRow) }
    }
  }

  @ExperimentalCoroutinesApi
  suspend fun getAllCommitsStream(): Flow<List<Commit>> {
    return channelFlow {
      val result = dbRead {
        CommitTable.selectAll()
          .chunked(COMMITS_CHUNK_SIZE)
          .forEach { resultRowList ->
            val commits = resultRowList.map { resultRow -> Commit.fromResultRow(resultRow) }
            if (commits.isNotEmpty()) {
              send(commits)
            }
          }
      }

      if (result.isFailure) {
        throw result.exceptionOrNull()!!
      }
    }
  }

  suspend fun getCommitsCount(): Result<Int> {
    return dbRead {
      CommitTable.selectAll().count()
    }
  }

  suspend fun getHeadCommits(apks: List<Apk>): Result<List<Commit>> {
    return dbRead {
      val apkUuidSet = apks.map { apk -> apk.apkUuid }.toSet()

      return@dbRead CommitTable.select {
        CommitTable.uuid.inList(apkUuidSet) and
          CommitTable.head.eq(true)
      }
        .map { resultRow -> Commit.fromResultRow(resultRow) }
    }
  }

  suspend fun testGetAll(): Result<List<Commit>> {
    return dbRead {
      CommitTable.selectAll()
        .map { resultRow -> Commit.fromResultRow(resultRow) }
    }
  }

  companion object {
    private const val COMMITS_CHUNK_SIZE = 512
    private const val ELLIPSIZE = "..."
  }
}

class CommitsParseException : Exception("Couldn't parse commits, resulted in empty parsed data")
class CommitValidationException : Exception("One of the parsed commits is not valid")
class NoNewCommitsLeftAfterFiltering(commits: List<Commit>) :
  Exception("No new commits left after filtering, commits = ${commits}")

class CommitUuidIsBlank : Exception("Commit uuid is blank")