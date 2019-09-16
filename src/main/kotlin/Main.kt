import di.MainModule
import io.vertx.core.Vertx
import org.koin.core.context.startKoin
import java.io.File

fun main(args: Array<String>) {
  if (args.size != 3) {
    println("Not enough arguments! (base url, secret key and apks dir must be provided!)")
    return
  }

  val vertx = Vertx.vertx()
  val baseUrl = args[0]
  val secretKey = args[1]
  val apksDirPath = args[2]

  println("baseUrl = $baseUrl, secretKey = $secretKey, apksDirPath = $apksDirPath")

  startKoin {
    modules(MainModule(vertx, baseUrl, File(apksDirPath), secretKey).createMainModule())
  }

  vertx.deployVerticle(ServerVerticle()) { ar ->
    if (ar.succeeded()) {
      println("Server started")
    } else {
      println("Could not start server")
      ar.cause().printStackTrace()
    }
  }
}