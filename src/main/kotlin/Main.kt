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
import server.HttpServerVerticle
import server.HttpsServerVerticle
import java.io.File


fun main(args: Array<String>) {
  if (args.size != 5) {
    println("Not enough arguments! (base url, secret key, apks dir, reports dir and ssl cert dir must be provided!)")
    return
  }

  System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "INFO")

  val vertx = Vertx.vertx()
  val baseUrl = args[0]
  val secretKey = args[1]
  val apksDirPath = args[2]
  val reportsDirPath = args[3]
  val sslCertDirPath = args[4]

  println(
    "baseUrl = $baseUrl, secretKey = $secretKey, apksDirPath = $apksDirPath, " +
      "reportsDirPath = ${reportsDirPath}, sslCertDirPath = ${sslCertDirPath}"
  )
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
        sslCertDirPath,
        dispatcherProvider
      ).createMainModule()
    )
  }

  vertx
    .deployVerticle(HttpServerVerticle()) { ar ->
      if (ar.succeeded()) {
        println("HttpServerVerticle started")
      } else {
        println("Could not start HttpServerVerticle")
        ar.cause().printStackTrace()
      }
    }

  vertx
    .deployVerticle(HttpsServerVerticle(dispatcherProvider)) { ar ->
      if (ar.succeeded()) {
        println("HttpsServerVerticle started")
      } else {
        println("Could not start HttpsServerVerticle")
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