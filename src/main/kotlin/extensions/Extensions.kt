package extensions

import org.jetbrains.exposed.sql.*
import java.lang.Byte.toUnsignedInt

fun ByteArray.toHex(): String {
  return joinToString("") {
    Integer.toUnsignedString(toUnsignedInt(it), 16).padStart(2, '0')
  }
}

fun <T> List<T>.filterDuplicates(possibleDuplicates: List<T>): Set<T> {
  val set = this.toSet()

  if (possibleDuplicates.isEmpty()) {
    return set
  }

  return set.subtract(possibleDuplicates)
}

fun <T> Table.selectFilterDuplicates(
  originalList: List<T>,
  where: SqlExpressionBuilder.() -> Op<Boolean>,
  mapper: (ResultRow) -> T): Set<T> {
  val possibleDuplicates = select { where() }
    .map { resultRow -> mapper(resultRow) }

  return originalList.filterDuplicates(possibleDuplicates)
}