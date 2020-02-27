package extensions

import io.vertx.ext.web.RoutingContext
import org.jetbrains.exposed.sql.*
import server.ServerConstants
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

fun RoutingContext.isAuthorized(secretKey: String): Boolean {
  val authCookie = this.cookieMap().getOrDefault(ServerConstants.AUTH_COOKIE_KEY, null)
  if (authCookie == null) {
    return false
  }

  if (authCookie.value != secretKey) {
    return false
  }

  return true
}