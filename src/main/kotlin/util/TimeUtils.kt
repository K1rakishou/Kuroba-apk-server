package util

import org.joda.time.DateTime

open class TimeUtils {

  open fun now(): DateTime {
    return DateTime.now()
  }

}