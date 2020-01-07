package extensions

import org.jetbrains.exposed.sql.*
import java.lang.Byte.toUnsignedInt
import java.nio.charset.Charset

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

fun getResourceString(clazz: Class<*>, fileName: String): String {
  val resourceInputStream = checkNotNull(clazz.classLoader.getResourceAsStream(fileName)) {
    "Couldn't get input stream for resource \"$fileName\""
  }

  return resourceInputStream.use { inStream ->
    String(inStream.readAllBytes(), Charset.defaultCharset())
  }
}

fun String.trimEndIfLongerThan(maxSize: Int): String {
  if (length <= maxSize) {
    return this
  }

  return substring(0, maxSize)
}