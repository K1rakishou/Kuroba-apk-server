package server

import java.io.File

open class ServerSettings(
  open val baseUrl: String,
  open val apksDir: File,
  open val secretKey: String,
  open val apkName: String,
  // ~10MB per apk * 1000 ~= 10GB
  open val maxApkFiles: Int = 1000,
  // 5 minutes
  open val apkDeletionInterval: Long = 5L * 1000L * 60L,
  open val listApksPerPageCount: Int = 50
)