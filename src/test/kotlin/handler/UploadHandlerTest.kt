package handler

import com.nhaarman.mockitokotlin2.anyOrNull
import com.nhaarman.mockitokotlin2.doReturn
import data.Apk
import data.ApkFileName
import data.ApkFileName.Companion.APK_EXTENSION
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import server.ServerVerticle
import server.ServerVerticle.Companion.APK_VERSION_HEADER_NAME
import server.ServerVerticle.Companion.SECRET_KEY_HEADER_NAME
import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors


@ExperimentalCoroutinesApi
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

  private fun cleanupFiles() {
    serverSettings.apksDir.listFiles()!!.forEach { file ->
      if (file.isFile) {
        assertTrue(file.delete())
      } else if (file.isDirectory) {
        assertTrue(file.deleteRecursively())
      }
    }
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
      modules(getModule(vertx, database, dispatcherProvider))
      runBlocking { mocks() }
    }

    cleanupFiles()

    vertx.deployVerticle(ServerVerticle(dispatcherProvider), testContext.succeeding { id ->
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
              cleanupFiles()
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
      doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
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
        doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
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
        doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
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
        doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
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
        doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
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
        doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
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
        doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
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
        doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
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
        doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
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

        Mockito.verify(deleteApkFullyService, Mockito.times(0)).deleteApks(anyList())
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
        doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
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

        Mockito.verify(deleteApkFullyService, Mockito.times(1)).deleteApks(anyList())
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
        doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
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

        Mockito.verify(deleteApkFullyService, Mockito.times(1)).deleteApks(anyList())
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
        doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
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

        Mockito.verify(deleteApkFullyService, Mockito.times(1)).deleteApks(anyList())
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
        doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
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
              "112233_8acb72611099915c81998776641606b1b47db583_1570287894192.apk", apks.first().apkFullPath
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
      modules(getModule(vertx, database, dispatcherProvider))
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
      val executor = Executors.newFixedThreadPool(data.size)

      vertx.deployVerticle(ServerVerticle(dispatcherProvider))
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
          doReturn(Unit).`when`(oldApkRemoverService).onNewApkUploaded()
          doReturn(false).`when`(requestThrottler).shouldBeThrottled(anyString(), anyBoolean())
        }, { response ->
          assertEquals(HttpResponseStatus.OK.code(), response.statusCode())
        }, {
          val commits = commitsRepository.testGetAll().getOrNull()!!
          val apks = apksRepository.testGetAll().getOrNull()!!

          assertEquals(count, commits.size)
          assertEquals(count, apks.size)

          apks.forEach { apk -> assertTrue(apk.apkFullPath.endsWith(APK_EXTENSION)) }

          val allFiles = serverSettings.apksDir.listFiles()!!
          assertEquals((count * 2) + 1, allFiles.size) // Don't forget the "generated" directory

          val apkFiles = allFiles.filter { file -> file.name.endsWith(".apk") }
          assertEquals(count, apkFiles.size)

          val commitFiles = allFiles.filter { file -> file.name.endsWith("_commits.txt") }
          assertEquals(count, commitFiles.size)
        }
      )
    }

    stopKoin()
    testContext.completeNow()
  }

  @Test
  fun `test old apk remover`(vertx: Vertx, testContext: VertxTestContext) {
    val count = 20

    startKoin {
      modules(getModule(vertx, database, dispatcherProvider))
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

      val executor = Executors.newFixedThreadPool(1)

      vertx.deployVerticle(ServerVerticle(dispatcherProvider))
      val client = WebClient.create(vertx)

      data.mapIndexed { index, (mf, mm) ->
        val countDownLatch = CountDownLatch(1)

        executor.execute {
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
                  stopKoin()
                  testContext.failNow(error)
                } finally {
                  countDownLatch.countDown()
                }
              }
            }
        }

        countDownLatch.await()
      }

      try {
        allUploadedTestFunc()
      } catch (error: Throwable) {
        stopKoin()
        testContext.failNow(error)
      }

      vertx.close()
    }

    runBlocking {
      val filesWithHeader = generateApksWithCommits()

      sendRequest(
        vertx,
        filesWithHeader, {
          doReturn(true).`when`(mainInitializer).initEverything()
          doReturn(count / 2).`when`(serverSettings).maxApkFiles
          doReturn(true).`when`(timeUtils).isItTimeToDeleteOldApks(anyOrNull(), anyOrNull())
          doReturn(false).`when`(requestThrottler).shouldBeThrottled(anyString(), anyBoolean())

          doReturn(
            DateTime(1570376694000),
            DateTime(1570376694001),
            DateTime(1570376694002),
            DateTime(1570376694003),
            DateTime(1570376694004),
            DateTime(1570376694005),
            DateTime(1570376694006),
            DateTime(1570376694007),
            DateTime(1570376694008),
            DateTime(1570376694009),
            DateTime(1570376694010),
            DateTime(1570376694011),
            DateTime(1570376694012),
            DateTime(1570376694013),
            DateTime(1570376694014),
            DateTime(1570376694015),
            DateTime(1570376694016),
            DateTime(1570376694017),
            DateTime(1570376694018),
            DateTime(1570376694019)
          ).`when`(timeUtils).now()

        }, { response ->
          assertEquals(HttpResponseStatus.OK.code(), response.statusCode())
        }, {
          delay(25)

          val commits = commitsRepository.testGetAll().getOrNull()!!
          val apks = apksRepository.testGetAll().getOrNull()!!

          assertEquals(count / 2, commits.size)
          assertEquals(count / 2, apks.size)
          assertEquals(count / 2, commitsRepository.getCommitsCount().getOrNull()!!)
          assertEquals(count / 2, apksRepository.getTotalApksCount().getOrNull()!!)

          apks.forEach { apk -> assertTrue(apk.apkFullPath.endsWith(APK_EXTENSION)) }

          val apkVersionByCommits = commits.map { it.apkVersion }
          val apkVersionByApks = apks.map { it.apkVersion }
          val expected = arrayOf<Long>(10, 11, 12, 13, 14, 15, 16, 17, 18, 19)

          expected.forEachIndexed { index, apkVersion ->
            assertEquals(apkVersion, apkVersionByCommits[index])
          }

          expected.forEachIndexed { index, apkVersion ->
            assertEquals(apkVersion, apkVersionByApks[index])
          }

          apkVersionByCommits.forEachIndexed { index, apkVersion ->
            assertEquals(apkVersion, apkVersionByApks[index])
          }

          val allFiles = serverSettings.apksDir.listFiles()!!
          assertEquals(count + 1, allFiles.size) // Don't forget the "generated" directory

          val apksFiles = allFiles.filter { file -> file.name.endsWith(".apk") }
          assertEquals(count / 2, apksFiles.size)

          val commitsFiles = allFiles.filter { file -> file.name.endsWith("_commits.txt") }
          assertEquals(count / 2, commitsFiles.size)

          val apkFileNames = apksFiles.map { it.name }
          val commitFileNames = commitsFiles.map { it.name }

          val expectedApkNames = arrayOf(
            "10_1000010_1570376694010.apk",
            "11_1000011_1570376694011.apk",
            "12_1000012_1570376694012.apk",
            "13_1000013_1570376694013.apk",
            "14_1000014_1570376694014.apk",
            "15_1000015_1570376694015.apk",
            "16_1000016_1570376694016.apk",
            "17_1000017_1570376694017.apk",
            "18_1000018_1570376694018.apk",
            "19_1000019_1570376694019.apk"
          )

          val expectedCommitNames = arrayOf(
            "10_1000010_commits.txt",
            "11_1000011_commits.txt",
            "12_1000012_commits.txt",
            "13_1000013_commits.txt",
            "14_1000014_commits.txt",
            "15_1000015_commits.txt",
            "16_1000016_commits.txt",
            "17_1000017_commits.txt",
            "18_1000018_commits.txt",
            "19_1000019_commits.txt"
          )

          expectedApkNames.forEachIndexed { index, apkName ->
            assertEquals(apkName, apkFileNames[index])
          }

          expectedCommitNames.forEachIndexed { index, apkName ->
            assertEquals(apkName, commitFileNames[index])
          }
        }
      )
    }

    stopKoin()
    testContext.completeNow()
  }
}