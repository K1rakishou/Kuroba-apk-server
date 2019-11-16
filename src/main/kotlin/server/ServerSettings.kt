package server

import java.io.File

open class ServerSettings(
  open val baseUrl: String,
  open val apksDir: File,
  open val secretKey: String,
  open val apkName: String = "kuroba-dev",
  // ~10MB per apk * 1000 ~= 10GB
  open val maxApkFiles: Int = 1000,
  // 5 minutes
  open val apkDeletionInterval: Long = 5L * 1000L * 60L,
  open val listApksPerPageCount: Int = 50,
  open val throttlerSettings: ThrottlerSettings = ThrottlerSettings()
)

open class ThrottlerSettings(
  open val maxFastRequestsPerCheck: Int = 30,
  open val maxSlowRequestsPerCheck: Int = 5,

  open val throttlingCheckInterval: Long = 60L * 1000L,

  // Slow requests are like when you are downloading an apk
  open val slowRequestsExceededBanTime: Int = 2 * 60 * 1000,

  // Fast request are just the regular requests (page refresh)
  open val fastRequestsExceededBanTime: Int = 60 * 1000,
  // 30 minutes
  open val removeOldVisitorsInterval: Long = 30L * 60L * 1000L,
  // 1 hour
  open val oldVisitorTime: Long = 60 * 60 * 1000
)