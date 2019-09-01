package data

data class Commit(
  val hash: String,
  val description: String
) {
  fun asString(): String {
    return String.format("%s - %s", hash, description)
  }
}