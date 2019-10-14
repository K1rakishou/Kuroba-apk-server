package parser

import data.Commit
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

open class CommitParser {
  private val logger = LoggerFactory.getLogger(CommitParser::class.java)
  private val regex = Pattern.compile("(\\b[0-9a-f]{5,40}\\b); (.*); (.*)")

  fun parseCommits(apkVersion: Long, commitsString: String): List<Commit> {
    val split = commitsString.split('\n')
    if (split.isEmpty()) {
      logger.error("Couldn't split commits into separate lines")
      return emptyList()
    }

    val isFirstCommit = AtomicBoolean(false)

    val parsedCommits = split.mapNotNull { sp ->
      val matcher = regex.matcher(sp)
      if (!matcher.find()) {
        logger.info("Commit \"$sp\" doesn't match the regex")
        return@mapNotNull null
      }

      val hash = matcher.group(1)
      val timeString = matcher.group(2)
      val description = matcher.group(3)

      if (hash.isBlank() || timeString.isBlank() || description.isBlank()) {
        logger.info("Commit \"$sp\" has one of parameters blank " +
          "(hash blank = ${hash.isBlank()}, " +
          "timeString blank = ${timeString.isBlank()}," +
          " description blank = ${description.isBlank()})")
        return@mapNotNull null
      }

      val parsedTime = try {
        DateTime.parse(
          // FIXME: Very bad hack!!!
          timeString.replace(" UTC", "+00:00").replace(" ", "T"),
          Commit.COMMIT_DATE_TIME_PARSER
        )
      } catch (error: Throwable) {
        logger.info("Error while trying to parse commit date, error = ${error.message}")
        return@mapNotNull null
      }

      return@mapNotNull Commit(
        apkVersion,
        hash,
        parsedTime,
        description,
        isFirstCommit.compareAndSet(false, true)
      )
    }

    val headCommitsCount = parsedCommits
      .filter { it.head }
      .distinctBy { commit -> commit.apkUuid }.size

    check(headCommitsCount == 1) {
      "There are at least two commits that have different apkUuids, parsedCommits = ${parsedCommits}"
    }

    // First element should contain the latest commit
    return parsedCommits.sortedByDescending { commit -> commit.committedAt }
  }

  fun commitsToString(parsedCommits: List<Commit>): String {
    return parsedCommits.joinToString(separator = "\n") { commit -> commit.serializeToString() }
  }
}