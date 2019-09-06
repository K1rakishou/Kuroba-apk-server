import di.MainModule
import io.vertx.core.Vertx
import org.koin.core.context.startKoin
import java.io.File

fun main(args: Array<String>) {
  if (args.size != 2) {
    println("Not enough arguments! (secret key and apks dir must be provided!)")
    return
  }

  val vertx = Vertx.vertx()
  val secretKey = args[0]
  val apksDirPath = args[1]

  if (secretKey.length < 128) {
    println("Bad secret key length, must be at least 128 characters")
    return
  }

  startKoin {
    modules(MainModule(vertx, File(apksDirPath), secretKey).createMainModule())
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