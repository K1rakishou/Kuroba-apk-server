package parser

import data.Commit
import java.util.regex.Pattern

class CommitParser {
  private val regex = Pattern.compile("(\\b[0-9a-f]{5,40}\\b) - (.*)")

  fun parseLatestCommits(latestCommits: String): List<Commit> {
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