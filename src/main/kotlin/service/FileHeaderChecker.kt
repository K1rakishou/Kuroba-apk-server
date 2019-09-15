package service

class FileHeaderChecker {
  private val allowedApkHeaders = listOf(
    byteArrayOf(0x50, 0x4B)
  )

  fun isValidApkFileHeader(header: ByteArray): Boolean {
    return allowedApkHeaders.any { apkHeader ->
      if (apkHeader.size != header.size) {
        return@any false
      }

      return@any apkHeader.contentEquals(header)
    }
  }
}