package service

import dispatchers.TestDispatcherProvider
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import server.ServerSettings
import server.ThrottlerSettings
import java.io.File

class RequestThrottlerTest {
  private val testDispatchers = TestDispatcherProvider()

  private fun createThrottlerSettings(
    maxFastRequestsPerCheck: Int = 100,
    maxSlowRequestsPerCheck: Int = 100,
    slowRequestsExceededBanTime: Int = 10,
    fastRequestsExceededBanTime: Int = 5
  ): ServerSettings {
    return ServerSettings(
      throttlerSettings = ThrottlerSettings(
        maxFastRequestsPerCheck,
        maxSlowRequestsPerCheck,
        slowRequestsExceededBanTime,
        fastRequestsExceededBanTime
      ),
      baseUrl = "test",
      apksDir = File("123"),
      secretKey = "123"
    )
  }

  @Test
  fun `test call throttler in multiple threads check that we were not throttled the same amount of times as amount of visits`() {
    val requestThrottler = RequestThrottler(testDispatchers, createThrottlerSettings())

    runBlocking {
      val results = (0 until 100).map {
        async(Dispatchers.IO) { requestThrottler.shouldBeThrottled("1", false) }
      }.awaitAll()

      val amountOfTimesWeWereNotThrottled = results.count { throttled -> !throttled }
      val amountOfVisits = requestThrottler.testGetRemoteVisitorsMap()["1"]!!.fastRequestsCount

      assertEquals(amountOfTimesWeWereNotThrottled, amountOfVisits)
    }
  }

  @Test
  fun `test the same but for multiple users`() {
    val requestThrottler = RequestThrottler(testDispatchers, createThrottlerSettings())

    runBlocking {
      val resultMap = mutableMapOf<String, List<Deferred<Boolean>>>()

      (0 until 20).forEach { userId ->
        val listOfDeferred = (0 until 100).map {
          async(Dispatchers.IO) { requestThrottler.shouldBeThrottled(userId.toString(), false) }
        }

        resultMap[userId.toString()] = listOfDeferred
      }

      resultMap.forEach { (userId, deferredList) ->
        val results = deferredList.awaitAll()

        val amountOfTimesWeWereNotThrottled = results.count { throttled -> !throttled }
        val amountOfVisits = requestThrottler.testGetRemoteVisitorsMap()[userId]!!.fastRequestsCount

        assertEquals(amountOfTimesWeWereNotThrottled, amountOfVisits)
      }
    }
  }

  @Test
  fun `test different request type should not interfere with each other`() {
    val requestThrottler = RequestThrottler(testDispatchers, createThrottlerSettings())

    runBlocking {
      val fastResults = (0 until 20).map {
        withContext(Dispatchers.IO) {
          requestThrottler.shouldBeThrottled("1", false)
        }
      }

      val slowResults = (0 until 4).map {
        withContext(Dispatchers.IO) {
          requestThrottler.shouldBeThrottled("1", true)
        }
      }

      val amountOfTimesWeWereThrottledF = fastResults.count { throttled -> throttled }
      val amountOfTimesWeWereThrottledS = slowResults.count { throttled -> throttled }

      assertEquals(0, amountOfTimesWeWereThrottledF)
      assertEquals(0, amountOfTimesWeWereThrottledS)

      assertEquals(20, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.fastRequestsCount)
      assertEquals(4, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.slowRequestsCount)
      assertEquals(0L, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.bannedAt)
      assertEquals(0, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.banDuration)
    }
  }

  @Test
  fun `test fast requests ban should be lift after some time`() {
    val requestThrottler = RequestThrottler(testDispatchers,
      createThrottlerSettings(maxFastRequestsPerCheck = 10, fastRequestsExceededBanTime = 15))

    runBlocking {
      val fastResults = (0 until 11).map {
        withContext(Dispatchers.IO) {
          requestThrottler.shouldBeThrottled("1", false)
        }
      }

      assertEquals(1, fastResults.count { throttled -> throttled })

      val stillBanned = withContext(Dispatchers.IO) {
        requestThrottler.shouldBeThrottled("1", true)
      }
      assertTrue(stillBanned)

      delay(16)

      val afterWaitBanLiftResult = withContext(Dispatchers.IO) {
        requestThrottler.shouldBeThrottled("1", false)
      }

      assertFalse(afterWaitBanLiftResult)

      assertEquals(1, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.fastRequestsCount)
      assertEquals(0, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.slowRequestsCount)
      assertEquals(0L, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.bannedAt)
      assertEquals(0, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.banDuration)
    }
  }

  @Test
  fun `test slow requests ban should be lift after some time`() {
    val requestThrottler = RequestThrottler(testDispatchers,
      createThrottlerSettings(maxSlowRequestsPerCheck = 10, slowRequestsExceededBanTime = 15))

    runBlocking {
      val fastResults = (0 until 11).map {
        withContext(Dispatchers.IO) {
          requestThrottler.shouldBeThrottled("1", true)
        }
      }

      assertEquals(1, fastResults.count { throttled -> throttled })

      val stillBanned = withContext(Dispatchers.IO) {
        requestThrottler.shouldBeThrottled("1", true)
      }
      assertTrue(stillBanned)

      delay(16)

      val afterWaitBanLiftResult = withContext(Dispatchers.IO) {
        requestThrottler.shouldBeThrottled("1", true)
      }

      assertFalse(afterWaitBanLiftResult)

      assertEquals(0, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.fastRequestsCount)
      assertEquals(1, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.slowRequestsCount)
      assertEquals(0L, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.bannedAt)
      assertEquals(0, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.banDuration)
    }
  }

}