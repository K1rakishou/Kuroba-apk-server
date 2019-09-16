package repository

import data.Commit
import io.vertx.core.logging.LoggerFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import parser.CommitParser
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.ArrayList

class CommitsRepository(
  private val commitParser: CommitParser
) {
  private val logger = LoggerFactory.getLogger(CommitsRepository::class.java)

  private val latestCommitsSource: TreeMap<Long, Commit> = TreeMap(naturalOrder<Long>())
  private val commitsPerApkSource: HashMap<String, List<Long>> = hashMapOf()
  private val mutex = Mutex()
  private val idGen = AtomicLong(0)

  suspend fun storeNewCommits(apkVersionString: String, latestCommits: String): Result<Unit> {
    if (latestCommits.isEmpty()) {
      return Result.failure(IllegalArgumentException("latestCommits is empty"))
    }

    val parsedData = commitParser.parseCommits(latestCommits)
    if (parsedData.isEmpty()) {
      logger.info("Couldn't parse any commits, latestCommits = $latestCommits")
      return Result.success(Unit)
    }

    val newIds = ArrayList<Long>(parsedData.size)

    mutex.withLock {
      for (parsed in parsedData) {
        val id = idGen.getAndIncrement()
        latestCommitsSource[id] = parsed

        newIds.add(id)
      }

      commitsPerApkSource[apkVersionString] = newIds
    }

    // TODO: error handling when DB introduced
    return Result.success(Unit)
  }

  suspend fun getCommitsByApkVersion(apkVersionString: String): Result<List<Commit>> {
    val commits = mutex.withLock {
      return@withLock commitsPerApkSource[apkVersionString]
        ?.mapNotNull { id -> latestCommitsSource[id] }
        ?: emptyList()
    }

    // TODO: error handling when DB introduced
    return Result.success(commits)
  }

  fun getLatestCommitHash(): Result<String?> {
    val hash = latestCommitsSource.firstEntry()?.value?.hash

    // TODO: error handling when DB introduced
    return Result.success(hash)
  }
}