package data

import java.time.LocalDateTime

data class Commit(
  val hash: String,
  val committedAt: LocalDateTime,
  val description: String
) {
  fun asString(): String {
    return String.format("%s; %s; %s", hash, committedAt.toString(), description)
  }
}