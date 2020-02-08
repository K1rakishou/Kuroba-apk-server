package server

import java.io.File
import java.util.concurrent.TimeUnit

open class ServerSettings(
  open val baseUrl: String,
  open val apksDir: File,
  open val reportsDir: File,
  open val secretKey: String,
  open val sslCertDirPath: String,
  open val apkName: String = "kuroba-dev",
  // ~10MB per apk * 1000 ~= 10GB
  open val maxApkFiles: Int = 1000,
  open val apkDeletionInterval: Long = TimeUnit.MINUTES.toMillis(5),
  open val serverStateSavingInterval: Long = TimeUnit.HOURS.toMillis(1),
  open val listApksPerPageCount: Int = 50,
  open val throttlerSettings: ThrottlerSettings = ThrottlerSettings()
)

open class ThrottlerSettings(
  open val maxFastRequestsPerCheck: Int = 30,
  open val maxSlowRequestsPerCheck: Int = 5,

  open val throttlingCheckInterval: Long = TimeUnit.MINUTES.toMillis(1),

  // Slow requests are like when you are downloading an apk
  open val slowRequestsExceededBanTime: Long = TimeUnit.MINUTES.toMillis(2),

  // Fast request are just the regular requests (page refresh)
  open val fastRequestsExceededBanTime: Long = TimeUnit.MINUTES.toMillis(1),
  open val removeOldVisitorsInterval: Long = TimeUnit.MINUTES.toMillis(30),
  open val oldVisitorTime: Long = TimeUnit.HOURS.toMillis(1)
)