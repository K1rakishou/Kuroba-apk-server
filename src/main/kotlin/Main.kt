import db.ApkTable
import db.CommitTable
import db.ReportTable
import di.MainModule
import dispatchers.RealDispatcherProvider
import init.MainInitializer
import io.vertx.core.Vertx
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.context.startKoin
import org.slf4j.impl.SimpleLogger
import server.FatalHandlerException
import server.HttpsServerVerticle
import server.ServerSettings
import java.io.File


suspend fun main(args: Array<String>) {
  if (args.size != 6) {
    println("Not enough arguments! (base url, secret key, apks dir, reports dir and ssl cert dir must be provided!)")
    return
  }

  System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "INFO")

  val vertx = Vertx.vertx()
  val baseUrl = args[0]
  val testMode = args[1].toBoolean()
  val secretKey = args[2]
  val apksDirPath = args[3]
  val reportsDirPath = args[4]
  val sslCertDirPath = args[5]

  println(
    "baseUrl = $baseUrl, testMode=$testMode secretKey = $secretKey, apksDirPath = $apksDirPath, " +
      "reportsDirPath = ${reportsDirPath}, sslCertDirPath = ${sslCertDirPath}"
  )

  val database = initDatabase()
  val dispatcherProvider = RealDispatcherProvider()

  val koinApplication = startKoin {
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

  val serverSettings = koinApplication.koin.get<ServerSettings>()
  val mainInitializer = koinApplication.koin.get<MainInitializer>()

  if (!serverSettings.apksDir.exists()) {
    throw FatalHandlerException("apksDir does not exist! dir = ${serverSettings.apksDir.absolutePath}")
  }

  if (!mainInitializer.initEverything()) {
    throw FatalHandlerException("Initialization error")
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
      SchemaUtils.createMissingTablesAndColumns(CommitTable, ApkTable, ReportTable)
    }

    println("Done")
    return@run database
  }
}