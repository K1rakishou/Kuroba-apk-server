package repository

import data.ApkFileName
import data.ApkVersion
import data.Commit
import data.CommitHash
import db.table.CommitTable
import io.vertx.core.logging.LoggerFactory
import org.jetbrains.exposed.sql.*
import org.koin.core.inject
import parser.CommitParser

class CommitRepository : BaseRepository() {
  private val logger = LoggerFactory.getLogger(CommitRepository::class.java)
  private val commitParser by inject<CommitParser>()

  suspend fun insertNewCommits(apkVersion: ApkVersion, latestCommits: String): Result<List<Commit>> {
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

    return Result.success(parsedCommits)
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

  suspend fun getLatestCommitHash(): Result<CommitHash?> {
    return dbRead {
      val resultRow = CommitTable.selectAll()
        .orderBy(CommitTable.committedAt, SortOrder.DESC)
        .limit(1)
        .firstOrNull()

      if (resultRow == null) {
        return@dbRead null
      }

      return@dbRead CommitHash(resultRow[CommitTable.hash])
    }
  }

  private suspend fun insertCommits(commits: List<Commit>): Result<Unit> {
    require(commits.isNotEmpty())

    return dbWrite {
      val apkVersions = commits.map { commit -> commit.apkVersion.version }
      val hashes = commits.map { commit -> commit.commitHash.hash }
      val groupUuid = commits.first().apkUuid

      val alreadyInserted = CommitTable.select {
        CommitTable.apkVersion.inList(apkVersions) and
          CommitTable.hash.inList(hashes)
      }
        .map { resultRow -> Commit.fromResultRow(resultRow) }
        .toSet()

      val filtered = commits.filter { commit ->
        !alreadyInserted.contains(commit)
      }

      if (filtered.isEmpty()) {
        throw NoNewCommitsLeftAfterFiltering()
      }

      CommitTable.batchInsert(filtered) { commit ->
        this[CommitTable.uuid] = commit.apkUuid
        this[CommitTable.groupUuid] = groupUuid
        this[CommitTable.hash] = commit.commitHash.hash
        this[CommitTable.apkVersion] = commit.apkVersion.version
        this[CommitTable.committedAt] = commit.committedAt
        this[CommitTable.description] = commit.description
      }

      return@dbWrite
    }
  }

  suspend fun removeCommits(commits: List<Commit>): Result<Unit> {
    require(commits.isNotEmpty())

    return dbWrite {
      val apkVersions = commits.map { commit -> commit.apkVersion.version }
      val hashes = commits.map { commit -> commit.commitHash.hash }

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

}

class CommitsParseException : Exception("Couldn't parse commits, resulted in empty parsed data")
class CommitValidationException : Exception("One of the parsed commits is not valid")
class NoNewCommitsLeftAfterFiltering : Exception("No new commits left after filtering")
class CommitUuidIsBlank : Exception("Commit uuid is blank")