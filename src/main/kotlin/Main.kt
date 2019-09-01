import io.vertx.core.Vertx
import java.io.File

fun main(args: Array<String>) {
  if (args.size != 2) {
    println("Not enough arguments! (secret key and apks dir must be provided!)")
    return
  }

  val serverVerticle = ServerVerticle(args[0], File(args[1]))

  Vertx.vertx().deployVerticle(serverVerticle) { ar ->
    if (ar.succeeded()) {
      println("Server started")
    } else {
      println("Could not start server")
      ar.cause().printStackTrace()
    }
  }
}