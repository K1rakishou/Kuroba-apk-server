import java.io.File

data class ServerSettings(
  val baseUrl: String,
  val apksDir: File,
  val secretKey: String,
  val apkName: String
)