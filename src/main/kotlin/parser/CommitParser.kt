package parser

import data.Commit
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.regex.Pattern

class CommitParser {
  private val regex = Pattern.compile("(\\b[0-9a-f]{5,40}\\b); (.*); (.*)")

  fun parseCommits(commitsString: String): List<Commit> {
    val split = commitsString.split('\n')
    if (split.isEmpty()) {
      return emptyList()
    }

    return split.mapNotNull { sp ->
      val matcher = regex.matcher(sp)
      if (!matcher.find()) {
        return@mapNotNull null
      }

      val hash = matcher.group(1)
      val timeString = matcher.group(2)
      val description = matcher.group(3)

      if (hash.isBlank() || timeString.isBlank() || description.isBlank()) {
        return@mapNotNull null
      }

      val parsedTime = try {
        LocalDateTime.parse(timeString, COMMIT_DATE_TIME_FORMAT)
      } catch (ignored: DateTimeParseException) {
        return@mapNotNull null
      }

      return@mapNotNull Commit(
        hash,
        parsedTime,
        description)
    }
  }

  companion object {
    val COMMIT_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  }
}