package data

import java.io.File

data class BackendParams(
  val baseUrl: String,
  val testMode: Boolean,
  val secretKey: String,
  val apksDir: File,
  val reportsDir: File,
  val sslCertDir: File,
  val dvachAppIdPubKey: String,
  val dvachAppIdPrivKey: String
) {

  fun validate() {
    check(apksDir.exists()) { "${apksDir.absolutePath} does not exist" }
    check(apksDir.canRead()) { "${apksDir.absolutePath} cannot be read" }

    check(reportsDir.exists()) { "${reportsDir.absolutePath} does not exist" }
    check(reportsDir.canRead()) { "${reportsDir.absolutePath} cannot be read" }

    check(sslCertDir.exists()) { "${sslCertDir.absolutePath} does not exist" }
    check(sslCertDir.canRead()) { "${sslCertDir.absolutePath} cannot be read" }
  }

  companion object {
    fun fromParamsFile(paramsFilePath: String) : BackendParams {
      val paramsFile = File(paramsFilePath)
      check(paramsFile.exists()) { "paramsFile ${paramsFile.absolutePath} does not exist" }
      check(paramsFile.canRead()) { "paramsFile ${paramsFile.absolutePath} cannot be read" }

      var baseUrl: String? = null
      var testMode: Boolean? = null
      var secretKey: String? = null
      var apksDirPath: String? = null
      var reportsDirPath: String? = null
      var sslCertDirPath: String? = null
      var dvachAppIdPubKey: String? = null
      var dvachAppIdPrivKey: String? = null

      paramsFile.readLines().forEach { line ->
        val (paramName, paramValue) = line.split("=")

        when (paramName) {
          "baseUrl" -> baseUrl = paramValue
          "testMode" -> testMode = paramValue.toBoolean()
          "secretKey" -> secretKey = paramValue
          "apksDirPath" -> apksDirPath = paramValue
          "reportsDirPath" -> reportsDirPath = paramValue
          "sslCertDirPath" -> sslCertDirPath = paramValue
          "dvachAppIdPubKey" -> dvachAppIdPubKey = paramValue
          "dvachAppIdPrivKey" -> dvachAppIdPrivKey = paramValue
          else -> throw IllegalArgumentException("Unknown paramName: \'${paramName}\'")
        }
      }

      return BackendParams(
        baseUrl = baseUrl!!,
        testMode = testMode!!,
        secretKey = secretKey!!,
        apksDir = File(apksDirPath!!),
        reportsDir = File(reportsDirPath!!),
        sslCertDir = File(sslCertDirPath!!),
        dvachAppIdPubKey = dvachAppIdPubKey!!,
        dvachAppIdPrivKey = dvachAppIdPrivKey!!
      ).also { backendParams -> backendParams.validate() }
    }
  }
}