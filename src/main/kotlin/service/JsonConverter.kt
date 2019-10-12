package service

import com.squareup.moshi.Moshi

class JsonConverter(
  val moshi: Moshi
) {

  inline fun <reified T> toJson(model: T): String {
    val json = moshi.adapter<T>(T::class.java).toJson(model)
    check(json.isNotEmpty()) { "Couldn't convert model with type ${T::class.java.name} (result json is empty)" }

    return json
  }

  inline fun <reified T : Any> fromJson(json: String): T {
    require(json.isNotEmpty()) { "Input json is empty" }

    return checkNotNull(moshi.adapter<T>(T::class.java).fromJson(json)) {
      "Couldn't convert json to model with type ${T::class.java}"
    }
  }

}