import java.io.File

open class ServerSettings(
  val baseUrl: String,
  val apksDir: File,
  val secretKey: String,
  val apkName: String
)