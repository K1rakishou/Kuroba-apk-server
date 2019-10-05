package handler

import ServerVerticle
import ServerVerticle.Companion.APK_VERSION_HEADER_NAME
import ServerVerticle.Companion.SECRET_KEY_HEADER_NAME
import com.nhaarman.mockitokotlin2.doReturn
import data.ApkFileName
import data.Commit
import data.CommitFileName
import db.ApkTable
import db.CommitTable
import handler.UploadHandler.Companion.APK_FILE_NAME
import handler.UploadHandler.Companion.COMMITS_FILE_NAME
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.MultiMap
import io.vertx.core.Vertx
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.ext.web.multipart.MultipartForm
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.File
import java.nio.file.Paths


@ExtendWith(VertxExtension::class)
class UploadHandlerTest : AbstractHandlerTest() {
  private val APK_MIME_TYPE = "application/vnd.android.package-archive"
  private val COMMITS_MIME_TYPE = "text/plain"

  private val goodApk = getResourceFile("goodapk.apk")
  private val badApk = getResourceFile("badapk.apk")
  private val goodCommits = getResourceFile("goodcommits.txt")

  private lateinit var database: Database

  @BeforeEach
  fun init() {
    database = initDatabase()
  }

  @AfterEach
  fun destroy() {
    transaction(database) {
      SchemaUtils.drop(CommitTable)
      SchemaUtils.drop(ApkTable)
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

  private val goodHeaders = headersOf(
    Pair(APK_VERSION_HEADER_NAME, "112233"),
    Pair(SECRET_KEY_HEADER_NAME, "test_key")
  )

  private val goodFiles = multipartFormOf(
    Triple(APK_FILE_NAME, goodApk, APK_MIME_TYPE),
    Triple(COMMITS_FILE_NAME, goodCommits, COMMITS_MIME_TYPE)
  )

  private fun getResourceFile(fileName: String): File {
    val resourceDirectory = Paths.get("src", "test", "resources")
    return File(resourceDirectory.toFile().absolutePath, fileName)
  }

  private fun headersOf(vararg headers: Pair<String, String>): MultiMap {
    val headerMap = MultiMap.caseInsensitiveMultiMap()

    for ((key, value) in headers) {
      headerMap.add(key, value)
    }

    return headerMap
  }

  private fun multipartFormOf(vararg parts: Triple<String, File, String>): MultipartForm {
    val form = MultipartForm.create()

    for ((name, file, mime) in parts) {
      form.binaryFileUpload(name, file.name, file.absolutePath, mime)
    }

    return form
  }

  private fun uploadHandlerTestFunc(
    vertx: Vertx,
    testContext: VertxTestContext,
    headers: MultiMap = goodHeaders,
    form: MultipartForm = goodFiles,
    mocks: suspend () -> Unit,
    func: (HttpResponse<String>) -> Unit
  ) {
    startKoin {
      modules(getModule(vertx, database))
      runBlocking { mocks() }
    }

    vertx.deployVerticle(ServerVerticle(), testContext.succeeding { id ->
      val client = WebClient.create(vertx)

      client
        .post(8080, "localhost", "/upload")
        .putHeaders(headers)
        .`as`(BodyCodec.string())
        .sendMultipartForm(form) { asyncResult ->
          testContext.verify {
            try {
              if (asyncResult.failed()) {
                throw asyncResult.cause()
              }

              try {
                func(asyncResult.result())
                testContext.completeNow()
              } catch (error: Throwable) {
                testContext.failNow(error)
              } finally {
                serverSettings.apksDir.listFiles()!!.forEach { file -> file.delete() }
              }
            } finally {
              stopKoin()
            }
          }
        }
    })
  }

  @Test
  fun `test without any parameters`(vertx: Vertx, testContext: VertxTestContext) {
    uploadHandlerTestFunc(vertx, testContext, headersOf(), multipartFormOf(), {
      doReturn(true).`when`(mainInitializer).initEverything()
    }, { response ->
      assertEquals(HttpResponseStatus.BAD_REQUEST.code(), response.statusCode())
      assertEquals("No apk version provided", response.body())
    })
  }

  @Test
  fun `test with apk version param not being numeric`(vertx: Vertx, testContext: VertxTestContext) {
    uploadHandlerTestFunc(
      vertx,
      testContext,
      headersOf(Pair(APK_VERSION_HEADER_NAME, "abc")),
      multipartFormOf(), {
        doReturn(true).`when`(mainInitializer).initEverything()
      }, { response ->
        assertEquals(HttpResponseStatus.BAD_REQUEST.code(), response.statusCode())
        assertEquals("Apk version must be numeric", response.body())
      })
  }

  @Test
  fun `test with bad secret key param`(vertx: Vertx, testContext: VertxTestContext) {
    uploadHandlerTestFunc(
      vertx,
      testContext,
      headersOf(
        Pair(APK_VERSION_HEADER_NAME, "112233"),
        Pair(SECRET_KEY_HEADER_NAME, "")
      ),
      multipartFormOf(), {
        doReturn(true).`when`(mainInitializer).initEverything()
      }, { response ->
        assertEquals(HttpResponseStatus.BAD_REQUEST.code(), response.statusCode())
        assertEquals("Secret key is bad", response.body())
      })
  }

  @Test
  fun `test with only apk file`(vertx: Vertx, testContext: VertxTestContext) {
    uploadHandlerTestFunc(
      vertx,
      testContext,
      goodHeaders,
      multipartFormOf(
        Triple(APK_FILE_NAME, badApk, APK_MIME_TYPE)
      ), {
        doReturn(true).`when`(mainInitializer).initEverything()
      }, { response ->
        assertEquals(HttpResponseStatus.BAD_REQUEST.code(), response.statusCode())
        assertEquals("FileUploads count does not equal to 2, actual = 1", response.body())
      })
  }

  @Test
  fun `test with only commits file`(vertx: Vertx, testContext: VertxTestContext) {
    uploadHandlerTestFunc(
      vertx,
      testContext,
      goodHeaders,
      multipartFormOf(
        Triple(COMMITS_FILE_NAME, goodCommits, COMMITS_MIME_TYPE)
      ), {
        doReturn(true).`when`(mainInitializer).initEverything()
      }, { response ->
        assertEquals(HttpResponseStatus.BAD_REQUEST.code(), response.statusCode())
        assertEquals("FileUploads count does not equal to 2, actual = 1", response.body())
      })
  }

  @Test
  fun `test with bad multipart names`(vertx: Vertx, testContext: VertxTestContext) {
    uploadHandlerTestFunc(
      vertx,
      testContext,
      goodHeaders,
      multipartFormOf(
        Triple("test", badApk, APK_MIME_TYPE),
        Triple("test2", goodCommits, COMMITS_MIME_TYPE)
      ), {
        doReturn(true).`when`(mainInitializer).initEverything()
      }, { response ->
        assertEquals(HttpResponseStatus.BAD_REQUEST.code(), response.statusCode())
        assertEquals(
          "One of the multipart parameters is not present: apkFile: false, commitsFile: false",
          response.body()
        )
      })
  }

  @Test
  fun `test with good apk multipart name`(vertx: Vertx, testContext: VertxTestContext) {
    uploadHandlerTestFunc(
      vertx,
      testContext,
      goodHeaders,
      multipartFormOf(
        Triple(APK_FILE_NAME, goodApk, APK_MIME_TYPE),
        Triple("test2", goodCommits, COMMITS_MIME_TYPE)
      ), {
        doReturn(true).`when`(mainInitializer).initEverything()
      }, { response ->
        assertEquals(HttpResponseStatus.BAD_REQUEST.code(), response.statusCode())
        assertEquals(
          "One of the multipart parameters is not present: apkFile: true, commitsFile: false",
          response.body()
        )
      })
  }

  @Test
  fun `test with good commits multipart name`(vertx: Vertx, testContext: VertxTestContext) {
    uploadHandlerTestFunc(
      vertx,
      testContext,
      goodHeaders,
      multipartFormOf(
        Triple("test", goodApk, APK_MIME_TYPE),
        Triple(COMMITS_FILE_NAME, goodCommits, COMMITS_MIME_TYPE)
      ), {
        doReturn(true).`when`(mainInitializer).initEverything()
      }, { response ->
        assertEquals(HttpResponseStatus.BAD_REQUEST.code(), response.statusCode())
        assertEquals(
          "One of the multipart parameters is not present: apkFile: false, commitsFile: true",
          response.body()
        )
      })
  }

  @Test
  fun `test everything is ok`(vertx: Vertx, testContext: VertxTestContext) {
    uploadHandlerTestFunc(
      vertx,
      testContext,
      goodHeaders,
      multipartFormOf(
        Triple(APK_FILE_NAME, goodApk, APK_MIME_TYPE),
        Triple(COMMITS_FILE_NAME, goodCommits, COMMITS_MIME_TYPE)
      ), {
        doReturn(true).`when`(mainInitializer).initEverything()
      }, { response ->
        assertEquals(HttpResponseStatus.OK.code(), response.statusCode())

        transaction(database) {
          val commits = CommitTable.selectAll()
            .map { resultRow -> Commit.fromResultRow(resultRow) }

          assertEquals(5, commits.size)
          assertEquals(112233, commits.first().apkVersion)
          assertEquals("112233_8acb72611099915c81998776641606b1b47db583", commits.first().apkUuid)
          assertEquals("8acb72611099915c81998776641606b1b47db583", commits.first().commitHash)
          assertEquals("Merge pull request #25 from K1rakishou/test-my-multi-feature", commits.first().description)
        }

        val resultFiles = serverSettings.apksDir.listFiles()!!
        assertEquals(2, resultFiles.size)

        val apkFile = checkNotNull(resultFiles.firstOrNull { it.name.endsWith(".apk") })
        val commitsFile = checkNotNull(resultFiles.firstOrNull { it.name.endsWith("_commits.txt") })

        val apkFileName = checkNotNull(ApkFileName.fromString(apkFile.absolutePath))
        assertEquals(112233, apkFileName.apkVersion)
        assertEquals("8acb72611099915c81998776641606b1b47db583", apkFileName.commitHash)

        val commitFileName = checkNotNull(CommitFileName.fromString(commitsFile.absolutePath))
        assertEquals(112233, commitFileName.apkVersion)
        assertEquals("8acb72611099915c81998776641606b1b47db583", commitFileName.commitHash)

        val contents = commitsFile.readText()
        val parsedCommits = commitParser.parseCommits(112233, contents)
        assertEquals(5, parsedCommits.size)

        assertEquals("112233_8acb72611099915c81998776641606b1b47db583", parsedCommits.first().apkUuid)
        assertEquals("8acb72611099915c81998776641606b1b47db583", parsedCommits.first().commitHash)
        assertEquals("Merge pull request #25 from K1rakishou/test-my-multi-feature", parsedCommits.first().description)
      })
  }

}