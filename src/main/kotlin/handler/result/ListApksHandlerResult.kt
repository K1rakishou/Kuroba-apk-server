package handler.result

sealed class ListApksHandlerResult : HandlerResult {
  object Success : ListApksHandlerResult()
  object NoApksUploaded : ListApksHandlerResult()

  class GenericExceptionResult(
    val exception: Throwable
  ) : ListApksHandlerResult()
}