package parser

import data.Commit
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

open class CommitParser {
  private val logger = LoggerFactory.getLogger(CommitParser::class.java)

  fun parseCommits(apkVersion: Long, commitsString: String): List<Commit> {
    val split = commitsString.split('\n')
    if (split.isEmpty()) {
      logger.error("Couldn't split commits into separate lines, commitsString = ${commitsString}")
      return emptyList()
    }

    val isFirstCommit = AtomicBoolean(false)

    val parsedCommits = split.mapNotNull { sp ->
      val parsedCommitText = parseCommitString(sp)
        ?: return@mapNotNull null

      val (hash, timeString, description) = parsedCommitText

      if (hash.isBlank() || timeString.isBlank() || description.isBlank()) {
        logger.info("Commit \"$sp\" has one of parameters blank " +
          "(hash blank = ${hash.isBlank()}, " +
          "timeString blank = ${timeString.isBlank()}," +
          " description blank = ${description.isBlank()})")
        return@mapNotNull null
      }

      val parsedTime = try {
        // TODO: remove this hack when there are no commits without "UTC" at the end
        val fixedString = if (timeString.endsWith("UTC")) {
          timeString.replace(" UTC", "+00:00").replace(" ", "T")
        } else {
          timeString
        }

        DateTime.parse(
          fixedString,
          Commit.COMMIT_DATE_TIME_PARSER
        )
      } catch (error: Throwable) {
        logger.error("Error while trying to parse commit date, string = ${sp}, error = ${error.message}")
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
      "There are at least two commits that have different apkUuids, string = ${commitsString}, parsedCommits = ${parsedCommits}"
    }

    // First element should contain the latest commit
    return parsedCommits.sortedByDescending { commit -> commit.committedAt }
  }

  private fun parseCommitString(string: String): ParsedCommitText? {
    val semicolonCount = string.count { it == ';' }
    if (semicolonCount < 2) {
      logger.error("Bad amount of semicolons (${semicolonCount}) in commit text = ${string}")
      return null
    }

    if (semicolonCount > 2) {
      logger.info("Semicolon count is greater than 2 (${semicolonCount}) trying to parse it without regex")

      val commitHashIndex = string.indexOf(';')
      if (commitHashIndex == -1 || commitHashIndex >= string.length) {
        logger.error("Commit text doesn't have any semicolons at all, string = $string")
        return null
      }

      val commitDateIndex = string.indexOf(';', commitHashIndex + 1)
      if (commitDateIndex == -1 || commitDateIndex >= string.length) {
        logger.error("Commit text doesn't have the second semicolon, string = $string")
        return null
      }

      val commitHash = string.substring(0, commitHashIndex).trim()
      val commitDate = string.substring(commitHashIndex + 1, commitDateIndex).trim()
      val commitDescription = string.substring(commitDateIndex + 1).trim()

      return ParsedCommitText(
        commitHash,
        commitDate,
        commitDescription
      )
    } else {
      val matcher = UNPARSED_COMMIT_PATTERN.matcher(string)
      if (!matcher.find()) {
        logger.info("Commit \"$string\" doesn't match the regex")
        return null
      }

      return ParsedCommitText(
        matcher.group(1),
        matcher.group(2),
        matcher.group(3)
      )
    }
  }

  fun commitsToString(parsedCommits: List<Commit>): String {
    return parsedCommits.joinToString(separator = "\n") { commit -> commit.serializeToString() }
  }

  data class ParsedCommitText(
    val hash: String,
    val timeString: String,
    val description: String
  )

  companion object {
    private val UNPARSED_COMMIT_PATTERN = Pattern.compile("(\\b[0-9a-f]{5,40}\\b); (.*); (.*)")
  }
}