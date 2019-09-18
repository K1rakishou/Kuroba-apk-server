package handler.result

sealed class ListApksHandlerResult : HandlerResult {
  object Success : ListApksHandlerResult()
  object NoApksUploaded : ListApksHandlerResult()
  object NoApksLeftAfterFiltering : ListApksHandlerResult()

  class GenericExceptionResult(
    val exception: Throwable
  ) : ListApksHandlerResult()
}