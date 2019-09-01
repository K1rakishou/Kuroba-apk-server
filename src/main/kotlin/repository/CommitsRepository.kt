package repository

import data.Commit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class CommitsRepository(
  private val latestCommitsSource: TreeMap<Long, Commit> = TreeMap(naturalOrder<Long>()),
  private val commitsPerApkSource: HashMap<String, List<Long>> = hashMapOf()
) {
  private val mutex = Mutex()
  private val idGen = AtomicLong(0)
  private val regex = Pattern.compile("(\\b[0-9a-f]{5,40}\\b) - (.*)")

  suspend fun storeNewCommits(apkVersionString: String, latestCommits: String): Result<Unit> {
    val parsedData = parseLatestCommits(latestCommits)
    if (parsedData.isEmpty()) {
      // TODO: Log that nothing was parsed?
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

  private suspend fun parseLatestCommits(latestCommits: String): List<Commit> {
    val split = latestCommits.split('\n')
    if (split.isEmpty()) {
      return emptyList()
    }

    return split.mapNotNull { sp ->
      val matcher = regex.matcher(sp)

      if (!matcher.find()) {
        return@mapNotNull null
      }

      return@mapNotNull Commit(matcher.group(1), matcher.group(2))
    }
  }
}