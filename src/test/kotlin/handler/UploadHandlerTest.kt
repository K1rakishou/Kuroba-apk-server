package handler

import ServerVerticle
import ServerVerticle.Companion.APK_VERSION_HEADER_NAME
import ServerVerticle.Companion.SECRET_KEY_HEADER_NAME
import com.nhaarman.mockitokotlin2.anyOrNull
import com.nhaarman.mockitokotlin2.doReturn
import data.Apk
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
import org.joda.time.DateTime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito
import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors


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
    func: suspend (HttpResponse<String>) -> Unit
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
                runBlocking { func(asyncResult.result()) }
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
  fun `test commit repository cannot insert new commits`(vertx: Vertx, testContext: VertxTestContext) {
    uploadHandlerTestFunc(
      vertx,
      testContext,
      goodHeaders,
      multipartFormOf(
        Triple(APK_FILE_NAME, goodApk, APK_MIME_TYPE),
        Triple(COMMITS_FILE_NAME, goodCommits, COMMITS_MIME_TYPE)
      ), {
        doReturn(true).`when`(mainInitializer).initEverything()
        doReturn(Result.failure<List<Commit>>(IOException("BAM"))).`when`(commitsRepository)
          .insertCommits(anyLong(), anyString())
      }, { response ->
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), response.statusCode())
        assertEquals("Couldn't store commits", response.body())

        assertEquals(0, commitsRepository.getCommitsCount().getOrNull()!!)
        assertEquals(0, apksRepository.getTotalApksCount().getOrNull()!!)

        val resultFiles = serverSettings.apksDir.listFiles()!!
        assertEquals(0, resultFiles.size)

        Mockito.verify(commitsRepository, Mockito.times(1)).insertCommits(anyLong(), anyString())
        Mockito.verify(apksRepository, Mockito.times(0)).insertApks(anyOrNull())
        Mockito.verify(commitPersister, Mockito.times(0)).store(anyLong(), anyList())
        Mockito.verify(apkPersister, Mockito.times(0)).store(anyOrNull(), anyLong(), anyList(), anyOrNull())

        Mockito.verify(commitsRepository, Mockito.times(0)).removeCommits(anyList())
        Mockito.verify(apksRepository, Mockito.times(0)).removeApk(anyOrNull())
        Mockito.verify(commitPersister, Mockito.times(0)).remove(anyLong(), anyList())
        Mockito.verify(apkPersister, Mockito.times(0)).remove(anyLong(), anyList())
      })
  }

  @Test
  fun `test apk repository cannot insert new apk`(vertx: Vertx, testContext: VertxTestContext) {
    uploadHandlerTestFunc(
      vertx,
      testContext,
      goodHeaders,
      multipartFormOf(
        Triple(APK_FILE_NAME, goodApk, APK_MIME_TYPE),
        Triple(COMMITS_FILE_NAME, goodCommits, COMMITS_MIME_TYPE)
      ), {
        doReturn(true).`when`(mainInitializer).initEverything()
        doReturn(Result.failure<Unit>(IOException("BAM"))).`when`(apksRepository).insertApks(anyList())
      }, { response ->
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), response.statusCode())
        assertEquals("Couldn't store commits", response.body())

        assertEquals(0, commitsRepository.getCommitsCount().getOrNull()!!)
        assertEquals(0, apksRepository.getTotalApksCount().getOrNull()!!)

        val resultFiles = serverSettings.apksDir.listFiles()!!
        assertEquals(0, resultFiles.size)

        Mockito.verify(commitsRepository, Mockito.times(1)).insertCommits(anyLong(), anyString())
        Mockito.verify(apksRepository, Mockito.times(1)).insertApks(anyOrNull())
        Mockito.verify(commitPersister, Mockito.times(0)).store(anyLong(), anyList())
        Mockito.verify(apkPersister, Mockito.times(0)).store(anyOrNull(), anyLong(), anyList(), anyOrNull())

        Mockito.verify(commitsRepository, Mockito.times(1)).removeCommits(anyList())
        Mockito.verify(apksRepository, Mockito.times(1)).removeApk(anyOrNull())
        Mockito.verify(commitPersister, Mockito.times(1)).remove(anyLong(), anyList())
        Mockito.verify(apkPersister, Mockito.times(1)).remove(anyLong(), anyList())
      })
  }

  @Test
  fun `test commit persister cannot store new commits`(vertx: Vertx, testContext: VertxTestContext) {
    uploadHandlerTestFunc(
      vertx,
      testContext,
      goodHeaders,
      multipartFormOf(
        Triple(APK_FILE_NAME, goodApk, APK_MIME_TYPE),
        Triple(COMMITS_FILE_NAME, goodCommits, COMMITS_MIME_TYPE)
      ), {
        doReturn(true).`when`(mainInitializer).initEverything()
        doReturn(Result.failure<Unit>(IOException("BAM"))).`when`(commitPersister).store(anyLong(), anyList())
      }, { response ->
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), response.statusCode())
        assertEquals("Couldn't store commits", response.body())

        assertEquals(0, commitsRepository.getCommitsCount().getOrNull()!!)
        assertEquals(0, apksRepository.getTotalApksCount().getOrNull()!!)

        val resultFiles = serverSettings.apksDir.listFiles()!!
        assertEquals(0, resultFiles.size)

        Mockito.verify(commitsRepository, Mockito.times(1)).insertCommits(anyLong(), anyString())
        Mockito.verify(apksRepository, Mockito.times(1)).insertApks(anyOrNull())
        Mockito.verify(commitPersister, Mockito.times(1)).store(anyLong(), anyList())
        Mockito.verify(apkPersister, Mockito.times(0)).store(anyOrNull(), anyLong(), anyList(), anyOrNull())

        Mockito.verify(commitsRepository, Mockito.times(1)).removeCommits(anyList())
        Mockito.verify(apksRepository, Mockito.times(1)).removeApk(anyOrNull())
        Mockito.verify(commitPersister, Mockito.times(1)).remove(anyLong(), anyList())
        Mockito.verify(apkPersister, Mockito.times(1)).remove(anyLong(), anyList())
      })
  }

  @Test
  fun `test apk persister cannot store new apk`(vertx: Vertx, testContext: VertxTestContext) {
    uploadHandlerTestFunc(
      vertx,
      testContext,
      goodHeaders,
      multipartFormOf(
        Triple(APK_FILE_NAME, goodApk, APK_MIME_TYPE),
        Triple(COMMITS_FILE_NAME, goodCommits, COMMITS_MIME_TYPE)
      ), {
        doReturn(true).`when`(mainInitializer).initEverything()
        doReturn(Result.failure<Unit>(IOException("BAM"))).`when`(apkPersister)
          .store(anyOrNull(), anyLong(), anyList(), anyOrNull())
      }, { response ->
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), response.statusCode())
        assertEquals("Couldn't store commits", response.body())

        assertEquals(0, commitsRepository.getCommitsCount().getOrNull()!!)
        assertEquals(0, apksRepository.getTotalApksCount().getOrNull()!!)

        val resultFiles = serverSettings.apksDir.listFiles()!!
        assertEquals(0, resultFiles.size)

        Mockito.verify(commitsRepository, Mockito.times(1)).insertCommits(anyLong(), anyString())
        Mockito.verify(apksRepository, Mockito.times(1)).insertApks(anyOrNull())
        Mockito.verify(commitPersister, Mockito.times(1)).store(anyLong(), anyList())
        Mockito.verify(apkPersister, Mockito.times(1)).store(anyOrNull(), anyLong(), anyList(), anyOrNull())

        Mockito.verify(commitsRepository, Mockito.times(1)).removeCommits(anyList())
        Mockito.verify(apksRepository, Mockito.times(1)).removeApk(anyOrNull())
        Mockito.verify(commitPersister, Mockito.times(1)).remove(anyLong(), anyList())
        Mockito.verify(apkPersister, Mockito.times(1)).remove(anyLong(), anyList())
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
        doReturn(DateTime(1570287894192)).`when`(timeUtils).now()
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
          assertEquals(
            Commit.COMMIT_DATE_TIME_PRINTER.parseDateTime("2019-09-19T14:52:49+00:00"),
            commits.first().committedAt
          )
          assertEquals(1, commits.count { it.head })

          val apks = ApkTable.selectAll()
            .map { resultRow -> Apk.fromResultRow(resultRow) }

          assertEquals(1, apks.size)

          assertEquals("112233_8acb72611099915c81998776641606b1b47db583", apks.first().apkUuid)
          assertEquals(
            "F:\\projects\\java\\current\\kuroba-server\\src\\test\\resources\\test_dump\\" +
              "112233_8acb72611099915c81998776641606b1b47db583_1570287894192", apks.first().apkFullPath
          )
          assertEquals(1570287894192, apks.first().uploadedOn.millis)
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

        val body = response.body()
        assertEquals("Uploaded apk with version code 112233", body)
      })
  }

  @Test
  fun `test many uploaded apks multithreaded`(vertx: Vertx, testContext: VertxTestContext) {
    val count = 500

    startKoin {
      modules(getModule(vertx, database))
    }

    val dir = File(serverSettings.apksDir, "generated")
    if (!dir.exists()) {
      assertTrue(dir.mkdirs())
    }

    fun generateApksWithCommits(): List<Pair<MultipartForm, MultiMap>> {
      val result = mutableListOf<Pair<MultipartForm, MultiMap>>()

      for (i in 0 until count) {
        val apkFile = File(dir, "${i}.apk")
        apkFile.writeText(String(byteArrayOf(0x50, 0x4B)) + "${i}")

        val commitFile = File(dir, "${i}_commits.txt")
        val commitText = String.format(
          "%d; %s; %s",
          1000000 + i,
          Commit.COMMIT_DATE_TIME_PRINTER.print(1571111111111),
          "description ${i}"
        )
        commitFile.writeText(commitText)

        val headers = headersOf(
          Pair(APK_VERSION_HEADER_NAME, i.toString()),
          Pair(SECRET_KEY_HEADER_NAME, "test_key")
        )

        val files = multipartFormOf(
          Triple(APK_FILE_NAME, apkFile, APK_MIME_TYPE),
          Triple(COMMITS_FILE_NAME, commitFile, COMMITS_MIME_TYPE)
        )

        result += Pair(files, headers)
      }

      return result
    }

    suspend fun sendRequest(
      vertx: Vertx,
      data: List<Pair<MultipartForm, MultiMap>>,
      mocks: suspend () -> Unit,
      uploadedTestFunc: suspend (HttpResponse<String>) -> Unit,
      allUploadedTestFunc: suspend () -> Unit
    ) {
      mocks()

      val countDownLatch = CountDownLatch(data.size)
      val executor = Executors.newFixedThreadPool(100)

      vertx.deployVerticle(ServerVerticle())
      val client = WebClient.create(vertx)

      val futures = data.mapIndexed { index, (mf, mm) ->
        return@mapIndexed executor.submit {
          println("[${Thread.currentThread().name}] Sending request $index")

          client
            .post(8080, "localhost", "/upload")
            .putHeaders(mm)
            .`as`(BodyCodec.string())
            .sendMultipartForm(mf) { asyncResult ->
              runBlocking {
                try {
                  uploadedTestFunc(asyncResult.result())
                } catch (error: Throwable) {
                  testContext.failNow(error)
                } finally {
                  countDownLatch.countDown()
                }
              }
            }
        }
      }

      try {
        futures.forEach { it.get() }
        countDownLatch.await()
      } catch (error: Throwable) {
        testContext.failNow(error)
      }

      allUploadedTestFunc()
      vertx.close()
    }

    runBlocking {
      val filesWithHeader = generateApksWithCommits()

      sendRequest(
        vertx,
        filesWithHeader, {
          doReturn(true).`when`(mainInitializer).initEverything()
        }, { response ->
          assertEquals(HttpResponseStatus.OK.code(), response.statusCode())
        }, {
          assertEquals(count, commitsRepository.getCommitsCount().getOrNull()!!)
          assertEquals(count, apksRepository.getTotalApksCount().getOrNull()!!)

          val allFiles = dir.listFiles()!!
          assertEquals(count * 2, allFiles.size)

          val apks = allFiles.filter { file -> file.name.endsWith(".apk") }
          assertEquals(count, apks.size)

          val commits = allFiles.filter { file -> file.name.endsWith("_commits.txt") }
          assertEquals(count, commits.size)

          serverSettings.apksDir.listFiles()!!.forEach { file ->
            if (file.isFile) {
              assertTrue(file.delete())
            } else if (file.isDirectory) {
              assertTrue(file.deleteRecursively())
            }
          }
        }
      )
    }

    stopKoin()
    testContext.completeNow()
  }

}