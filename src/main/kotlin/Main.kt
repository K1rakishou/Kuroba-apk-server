import db.ApkTable
import db.CommitTable
import di.MainModule
import dispatchers.RealDispatcherProvider
import io.vertx.core.Vertx
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
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
  val database = initDatabase()
  val dispatcherProvider = RealDispatcherProvider()

  startKoin {
    modules(MainModule(vertx, database, baseUrl, File(apksDirPath), secretKey, dispatcherProvider).createMainModule())
  }

  vertx.deployVerticle(ServerVerticle(dispatcherProvider)) { ar ->
    if (ar.succeeded()) {
      println("Server started")
    } else {
      println("Could not start server")
      ar.cause().printStackTrace()
    }
  }
}

private fun initDatabase(): Database {
  return run {
    println("Initializing DB")
    val database = Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

    transaction(database) {
      SchemaUtils.create(CommitTable)
      SchemaUtils.create(ApkTable)
    }

    println("Done")
    return@run database
  }
}