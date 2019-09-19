package parser

import data.ApkVersion
import data.Commit
import data.CommitHash
import io.vertx.core.logging.LoggerFactory
import org.joda.time.DateTime
import java.util.regex.Pattern

class CommitParser {
  private val logger = LoggerFactory.getLogger(CommitParser::class.java)
  private val regex = Pattern.compile("(\\b[0-9a-f]{5,40}\\b); (.*); (.*)")

  fun parseCommits(apkVersion: ApkVersion, commitsString: String): List<Commit> {
    val split = commitsString.split('\n')
    if (split.isEmpty()) {
      logger.error("Couldn't split commits into separate lines")
      return emptyList()
    }

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
        DateTime.parse(timeString, Commit.COMMIT_DATE_TIME_PARSER)
      } catch (error: Throwable) {
        logger.info("Error while trying to parse commit date, error = ${error.message}")
        return@mapNotNull null
      }

      return@mapNotNull Commit(
        apkVersion,
        CommitHash(hash),
        parsedTime,
        description)
    }

    // First element should contain the latest commit
    return parsedCommits.sortedByDescending { commit -> commit.committedAt }
  }

  fun commitsToString(parsedCommits: List<Commit>): String {
    return parsedCommits.joinToString(separator = "\n") { commit ->
      commit.asString()
    }
  }
}