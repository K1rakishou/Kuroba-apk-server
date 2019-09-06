package extensions

import java.lang.Byte.toUnsignedInt

fun ByteArray.toHex(): String {
  return joinToString("") {
    Integer.toUnsignedString(toUnsignedInt(it), 16).padStart(2, '0')
  }
}