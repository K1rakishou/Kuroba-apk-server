package service

class FileHeaderChecker {
  private val allowedApkHeaders = listOf(
    byteArrayOf(0x50, 0x4B)
  )

  fun isValidApkFileHeader(header: ByteArray): Boolean {
    return allowedApkHeaders.any { apkHeader -> apkHeader.contentEquals(header)}
  }
}