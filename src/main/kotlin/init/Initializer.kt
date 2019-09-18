package init

interface Initializer {
  suspend fun init(): Result<Unit>
}