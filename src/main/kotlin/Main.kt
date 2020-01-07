import db.ApkTable
import db.CommitTable
import db.ReportTable
import di.MainModule
import dispatchers.RealDispatcherProvider
import io.vertx.core.Vertx
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.context.startKoin
import org.slf4j.impl.SimpleLogger
import server.ServerVerticle
import java.io.File


fun main(args: Array<String>) {
  if (args.size != 4) {
    println("Not enough arguments! (base url, secret key, apks dir and reports dir must be provided!)")
    return
  }

  System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "INFO")

  val vertx = Vertx.vertx()
  val baseUrl = args[0]
  val secretKey = args[1]
  val apksDirPath = args[2]
  val reportsDirPath = args[3]

  println("baseUrl = $baseUrl, secretKey = $secretKey, apksDirPath = $apksDirPath, reportsDirPath = ${reportsDirPath}")
  val database = initDatabase()
  val dispatcherProvider = RealDispatcherProvider()

  startKoin {
    modules(
      MainModule(
        vertx,
        database,
        baseUrl,
        File(apksDirPath),
        File(reportsDirPath),
        secretKey,
        dispatcherProvider
      ).createMainModule()
    )
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
      SchemaUtils.create(ReportTable)
    }

    println("Done")
    return@run database
  }
}