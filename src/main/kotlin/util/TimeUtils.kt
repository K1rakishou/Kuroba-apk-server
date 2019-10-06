package util

import org.joda.time.DateTime
import org.joda.time.Instant

open class TimeUtils {

  open fun now(): DateTime {
    return DateTime.now()
  }

  open fun isItTimeToDeleteOldApks(now: Instant, nextRunTime: Instant): Boolean {
    return now.isAfter(nextRunTime)
  }

}