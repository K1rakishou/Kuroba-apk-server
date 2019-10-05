package repository

import data.ApkFileName
import data.Commit
import db.CommitTable
import extensions.selectFilterDuplicates
import io.vertx.core.logging.LoggerFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.jetbrains.exposed.sql.*
import org.koin.core.inject
import parser.CommitParser

open class CommitRepository : BaseRepository() {
  private val logger = LoggerFactory.getLogger(CommitRepository::class.java)
  private val commitParser by inject<CommitParser>()

  suspend fun insertCommits(apkVersion: Long, latestCommits: String): Result<List<Commit>> {
    if (latestCommits.isEmpty()) {
      return Result.failure(IllegalArgumentException("latestCommits is empty"))
    }

    val parsedCommits = commitParser.parseCommits(apkVersion, latestCommits)
    if (parsedCommits.isEmpty()) {
      // We must have at least one commit parsed, otherwise we fail the build
      logger.info("Couldn't parse any commits, latestCommits = $latestCommits")
      return Result.failure(CommitsParseException())
    }

    if (!validateCommits(parsedCommits)) {
      return Result.failure(CommitValidationException())
    }

    val insertResult = insertCommits(parsedCommits)
    if (insertResult.isFailure) {
      logger.error("Couldn't insert new commits")
      return Result.failure(insertResult.exceptionOrNull()!!)
    }

    return Result.success(insertResult.getOrNull()!!)
  }

  private fun validateCommits(parsedCommits: List<Commit>): Boolean {
    for (commit in parsedCommits) {
      if (!commit.validate()) {
        logger.error("Commit ${commit} is not a valid commit")
        return false
      }
    }

    return true
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

      if (resultRow == null) {
        return@dbRead null
      }

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
      // TODO: make this chunked
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
      })

      return@dbWrite CommitTable.batchInsert(filtered) { commit ->
        this[CommitTable.uuid] = commit.apkUuid
        this[CommitTable.groupUuid] = groupUuid
        this[CommitTable.hash] = commit.commitHash
        this[CommitTable.apkVersion] = commit.apkVersion
        this[CommitTable.committedAt] = commit.committedAt
        this[CommitTable.description] = commit.description
        this[CommitTable.head] = commit.head
      }.map { resultRow -> Commit.fromResultRow(resultRow) }
    }
  }

  suspend fun removeCommits(commits: List<Commit>): Result<Unit> {
    require(commits.isNotEmpty()) { "removeCommits() commits must not be empty" }

    return dbWrite {
      val apkVersions = commits.map { commit -> commit.apkVersion }
      val hashes = commits.map { commit -> commit.commitHash }

      CommitTable.deleteWhere {
        CommitTable.apkVersion.inList(apkVersions) and
          CommitTable.hash.inList(hashes)
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

  companion object {
    private const val COMMITS_CHUNK_SIZE = 512
  }
}

class CommitsParseException : Exception("Couldn't parse commits, resulted in empty parsed data")
class CommitValidationException : Exception("One of the parsed commits is not valid")
class NoNewCommitsLeftAfterFiltering(commits: List<Commit>) :
  Exception("No new commits left after filtering, commits = ${commits}")

class CommitUuidIsBlank : Exception("Commit uuid is blank")