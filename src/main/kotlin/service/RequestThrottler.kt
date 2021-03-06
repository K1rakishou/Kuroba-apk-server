package service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import server.ServerSettings
import java.util.concurrent.atomic.AtomicBoolean

open class RequestThrottler(
  private val serverSettings: ServerSettings
) {
  private val logger = LoggerFactory.getLogger(RequestThrottler::class.java)

  private val mutex = Mutex()
  private val remoteVisitorsMap = HashMap<String, RemoteVisitor>(128)
  private var lastTimeRemoveOldVisitorsTaskRun: Long = System.currentTimeMillis()

  /**
   * Returns true if the user shouldn't be able to continue his request
   * */
  open suspend fun shouldBeThrottled(remoteVisitorAddress: String, isSlowRequest: Boolean): Boolean {
    val now = System.currentTimeMillis()

    val remoteVisitor = mutex.withLock {
      if (!remoteVisitorsMap.containsKey(remoteVisitorAddress)) {
        val newRemoteVisitor = RemoteVisitor.create(AtomicBoolean(false), now, now, isSlowRequest, 0L, 0)
        remoteVisitorsMap[remoteVisitorAddress] = newRemoteVisitor

        // First visit, nothing to check
        return false
      } else {
        val visitor = checkNotNull(remoteVisitorsMap[remoteVisitorAddress]?.copy()) {
          "remoteAddressMap changed while being inside a mutex, couldn't find visitor $remoteVisitorAddress"
        }

        if (!visitor.isCheckRunning.compareAndSet(false, true)) {
          // User is sending requests to us so fast that we don't even have time to check them all, so just skip such
          // requests
          return true
        }

        visitor.lastVisitTime = now
        visitor
      }
    }

    try {
      if (remoteVisitor.banDuration != 0L) {
        if (now - remoteVisitor.bannedAt <= remoteVisitor.banDuration) {
          // Still banned
          return true
        }

        // The ban has expired
        remoteVisitor.bannedAt = 0L
        remoteVisitor.banDuration = 0
        remoteVisitor.checkStartTime = now
      }

      var banned = false

      if (isSlowRequest) {
        ++remoteVisitor.slowRequestsCount
        if (remoteVisitor.slowRequestsCount > serverSettings.throttlerSettings.maxSlowRequestsPerCheck) {
          if (now - remoteVisitor.checkStartTime < serverSettings.throttlerSettings.throttlingCheckInterval) {
            logger.info("Banning $remoteVisitorAddress for slowRequests exceeding violation, " +
              "time = (${now - remoteVisitor.checkStartTime}ms, requestsCount = ${remoteVisitor.slowRequestsCount})")

            remoteVisitor.banDuration = serverSettings.throttlerSettings.slowRequestsExceededBanTime
            banned = true
          } else {
            remoteVisitor.checkStartTime = now
            remoteVisitor.slowRequestsCount = 0
          }
        }
      } else {
        ++remoteVisitor.fastRequestsCount
        if (remoteVisitor.fastRequestsCount > serverSettings.throttlerSettings.maxFastRequestsPerCheck) {
          if (now - remoteVisitor.checkStartTime < serverSettings.throttlerSettings.throttlingCheckInterval) {
            logger.info("Banning $remoteVisitorAddress for fastRequests exceeding violation, " +
              "time = (${now - remoteVisitor.checkStartTime}ms, requestsCount = ${remoteVisitor.fastRequestsCount})")

            remoteVisitor.banDuration = serverSettings.throttlerSettings.fastRequestsExceededBanTime
            banned = true
          } else {
            remoteVisitor.checkStartTime = now
            remoteVisitor.fastRequestsCount = 0
          }
        }
      }

      if (banned) {
        remoteVisitor.bannedAt = now
        remoteVisitor.slowRequestsCount = 0
        remoteVisitor.fastRequestsCount = 0
        remoteVisitor.checkStartTime = now

        return true
      }

    } finally {
      mutex.withLock {
        remoteVisitorsMap[remoteVisitorAddress] = remoteVisitor
        removeOldVisitorsTask(now)

        remoteVisitor.isCheckRunning.set(false)
      }
    }

    return false
  }

  suspend fun getLeftBanTime(remoteVisitorAddress: String): Long {
    return mutex.withLock {
      val remoteVisitor = remoteVisitorsMap[remoteVisitorAddress]
        ?: return@withLock 0

      if (remoteVisitor.bannedAt == 0L) {
        return@withLock 0
      }

      val time = (remoteVisitor.bannedAt + remoteVisitor.banDuration) - System.currentTimeMillis()
      if (time <= 0L) {
        return@withLock 0
      }

      return@withLock time
    }
  }

  // Must be called within a locked mutex
  private fun removeOldVisitorsTask(now: Long) {
    if (now - lastTimeRemoveOldVisitorsTaskRun > serverSettings.throttlerSettings.removeOldVisitorsInterval) {
      logger.info("removeOldVisitorsTask started")

      try {
        if (remoteVisitorsMap.size < AMOUNT_OF_UNIQUE_VISITORS_TO_START_REMOVING) {
          logger.info("Too few visitors (${remoteVisitorsMap.size}/${AMOUNT_OF_UNIQUE_VISITORS_TO_START_REMOVING}) " +
            "to start removing anything")
          return
        }

        // 33%
        val amountToCheck = ((remoteVisitorsMap.keys.size / 100f) * 33f).toInt()
        val visitorsToCheck = remoteVisitorsMap.keys.shuffled().take(amountToCheck)
        val visitorsToDelete = mutableSetOf<String>()

        visitorsToCheck.forEach { visitorKey ->
          val visitor = remoteVisitorsMap[visitorKey]
          if (visitor != null && (now - visitor.lastVisitTime > serverSettings.throttlerSettings.oldVisitorTime)) {
            visitorsToDelete += visitorKey
          }
        }

        for (visitorKey in visitorsToDelete) {
          remoteVisitorsMap.remove(visitorKey)
        }

        logger.info("removeOldVisitorsTask finished, removed ${visitorsToDelete.size} old visitors")
      } catch (error: Throwable) {
        logger.error("removeOldVisitorsTask error", error)
      } finally {
        lastTimeRemoveOldVisitorsTaskRun = now
      }
    }
  }

  fun testGetRemoteVisitorsMap(): HashMap<String, RemoteVisitor> {
    return remoteVisitorsMap
  }

  data class RemoteVisitor constructor(
    var isCheckRunning: AtomicBoolean,
    var lastVisitTime: Long,
    var checkStartTime: Long,
    var slowRequestsCount: Int,
    var fastRequestsCount: Int,
    var bannedAt: Long,
    var banDuration: Long
  ) {

    companion object {
      fun create(
        isCheckRunning: AtomicBoolean,
        lastVisitTime: Long,
        checkStartTime: Long,
        isSlowRequest: Boolean,
        bannedAt: Long,
        banDuration: Long
      ): RemoteVisitor {
        val (slowRequests, fastRequests) = if (isSlowRequest) {
          1 to 0
        } else {
          0 to 1
        }

        return RemoteVisitor(
          isCheckRunning,
          lastVisitTime,
          checkStartTime,
          slowRequests,
          fastRequests,
          bannedAt,
          banDuration
        )
      }
    }

  }

  companion object {
    private const val AMOUNT_OF_UNIQUE_VISITORS_TO_START_REMOVING = 2048
  }
}