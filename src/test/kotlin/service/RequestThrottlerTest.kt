package service

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import server.ServerSettings
import server.ThrottlerSettings
import java.io.File

class RequestThrottlerTest {
  private fun createThrottlerSettings(
    maxFastRequestsPerCheck: Int = 100,
    maxSlowRequestsPerCheck: Int = 100,
    throttlingCheckInterval: Int = 20,
    slowRequestsExceededBanTime: Int = 10,
    fastRequestsExceededBanTime: Int = 5
  ): ServerSettings {
    return ServerSettings(
      throttlerSettings = ThrottlerSettings(
        maxFastRequestsPerCheck,
        maxSlowRequestsPerCheck,
        throttlingCheckInterval.toLong(),
        slowRequestsExceededBanTime.toLong(),
        fastRequestsExceededBanTime.toLong()
      ),
      baseUrl = "test",
      apksDir = File("123"),
      reportsDir = File("456"),
      secretKey = "123",
      sslCertDir = File("F:\\projects\\java\\current\\kuroba-server\\src\\main\\resources\\dev-cert")
    )
  }

  @Test
  fun `test call throttler in multiple threads check that we were not throttled the same amount of times as amount of visits`() {
    val requestThrottler = RequestThrottler(createThrottlerSettings())

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
    val requestThrottler = RequestThrottler(createThrottlerSettings())

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
    val requestThrottler = RequestThrottler(createThrottlerSettings())

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
    val requestThrottler = RequestThrottler(
      createThrottlerSettings(maxFastRequestsPerCheck = 10, fastRequestsExceededBanTime = 15, throttlingCheckInterval = 10)
    )

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
    val requestThrottler = RequestThrottler(
      createThrottlerSettings(maxSlowRequestsPerCheck = 10, slowRequestsExceededBanTime = 15)
    )

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

  @Test
  fun `should be banned when spamming lots of fast requests over time`() {
    val requestThrottler = RequestThrottler(
      createThrottlerSettings(maxFastRequestsPerCheck = 10, throttlingCheckInterval = 10, fastRequestsExceededBanTime = 5)
    )

    runBlocking {
      repeat(10) {
        assertFalse(requestThrottler.shouldBeThrottled("1", false))
        delay(1)
      }

      delay(6)

      repeat(10) {
        assertFalse(requestThrottler.shouldBeThrottled("1", false))
        delay(1)
      }

      delay(6)

      repeat(10) {
        assertFalse(requestThrottler.shouldBeThrottled("1", false))
        delay(1)
      }
    }
  }

  @Test
  fun `should not be banned when fast requests are spread out over time, requests and check time should be reset`() {
    val requestThrottler = RequestThrottler(
      createThrottlerSettings(maxFastRequestsPerCheck = 20, throttlingCheckInterval = 22)
    )

    runBlocking {
      repeat(20) {
        assertFalse(requestThrottler.shouldBeThrottled("1", false))
        delay(1)
      }

      delay(5)
      assertFalse(requestThrottler.shouldBeThrottled("1", false))

      assertEquals(0, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.fastRequestsCount)
      assertEquals(0, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.bannedAt)
      assertEquals(0, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.banDuration)
    }
  }

  @Test
  fun `should be banned when spamming lots of slow requests over time`() {
    val requestThrottler = RequestThrottler(
      createThrottlerSettings(maxSlowRequestsPerCheck = 10, throttlingCheckInterval = 10, slowRequestsExceededBanTime = 5)
    )

    runBlocking {
      repeat(10) {
        assertFalse(requestThrottler.shouldBeThrottled("1", true))
        delay(1)
      }

      delay(6)

      repeat(10) {
        assertFalse(requestThrottler.shouldBeThrottled("1", true))
        delay(1)
      }

      delay(6)

      repeat(10) {
        assertFalse(requestThrottler.shouldBeThrottled("1", true))
        delay(1)
      }
    }
  }

  @Test
  fun `should not be banned when slow requests are spread out over time, requests and check time should be reset`() {
    val requestThrottler = RequestThrottler(
      createThrottlerSettings(maxSlowRequestsPerCheck = 20, throttlingCheckInterval = 22)
    )

    runBlocking {
      repeat(20) {
        assertFalse(requestThrottler.shouldBeThrottled("1", true))
        delay(1)
      }

      delay(5)
      assertFalse(requestThrottler.shouldBeThrottled("1", true))

      assertEquals(0, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.slowRequestsCount)
      assertEquals(0, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.bannedAt)
      assertEquals(0, requestThrottler.testGetRemoteVisitorsMap()["1"]!!.banDuration)
    }
  }
}