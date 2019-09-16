package handler

import ServerVerticle
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.MultiMap
import io.vertx.core.Vertx
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.koin.core.context.startKoin
import java.io.File


@ExtendWith(VertxExtension::class)
class UploadHandlerTest {
  @BeforeEach
  fun deployVerticle(vertx: Vertx, testContext: VertxTestContext) {
    startKoin {
      modules(TestModule(vertx, "http://127.0.0.1:8080", File("src/test/resources"), "test_key").createTestModule())
    }

    vertx.deployVerticle(ServerVerticle(), testContext.completing())
  }

  @Test
  fun test(vertx: Vertx, testContext: VertxTestContext) {
    vertx.deployVerticle(ServerVerticle(), testContext.succeeding { id ->
      val client = WebClient.create(vertx)

      val body = MultiMap.caseInsensitiveMultiMap()
      body.add("123", "test")

      client
        .post(8080, "localhost", "/upload")
        .`as`(BodyCodec.string())
        .sendForm(body, testContext.succeeding { response ->
          testContext.verify {
            assert(response.statusCode() == HttpResponseStatus.BAD_REQUEST.code())
            assert(response.body() == "No apk version provided")

            testContext.completeNow()
          }
        })
    })
  }

}