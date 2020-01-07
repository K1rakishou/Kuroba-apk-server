package data.adapter

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormatterBuilder
import org.joda.time.format.ISODateTimeFormat

class DateTimeJsonAdapter : JsonAdapter<DateTime>() {

  override fun fromJson(reader: JsonReader): DateTime? {
    if (!reader.hasNext()) {
      return null
    }

    return DateTime.parse(reader.nextString(), formatter)
  }

  override fun toJson(writer: JsonWriter, value: DateTime?) {
    writer.value(formatter.print(value))
  }

  companion object {
    private val formatter = DateTimeFormatterBuilder()
      .append(ISODateTimeFormat.date())
      .appendLiteral('T')
      .append(ISODateTimeFormat.hourMinuteSecond())
      .appendTimeZoneOffset(null, true, 2, 2)
      .toFormatter()
  }
}