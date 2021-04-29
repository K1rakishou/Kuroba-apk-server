import data.BackendParams
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


suspend fun main(args: Array<String>) {
  if (args.size != 1) {
    println("Not enough arguments! (backend_parameters file path)")
    return
  }

  System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "INFO")

  val vertx = Vertx.vertx()
  val backendParams = BackendParams.fromParamsFile(args[0])

  println(
    "baseUrl = ${backendParams.baseUrl}, testMode=${backendParams.testMode} secretKey=${backendParams.secretKey}, " +
      "apksDir=${backendParams.apksDir}, reportsDir=${backendParams.reportsDir}, sslCertDir=${backendParams.sslCertDir}"
  )

  val database = initDatabase()
  val dispatcherProvider = RealDispatcherProvider()

  val koinApplication = startKoin {
    modules(
      MainModule(
        vertx = vertx,
        database = database,
        backendParams = backendParams,
        dispatcherProvider = dispatcherProvider
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