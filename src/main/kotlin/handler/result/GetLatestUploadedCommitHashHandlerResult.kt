package handler.result

sealed class GetLatestUploadedCommitHashHandlerResult : HandlerResult {
  object Success : GetLatestUploadedCommitHashHandlerResult()

  class GenericExceptionResult(
    val exception: Throwable
  ) : GetLatestUploadedCommitHashHandlerResult()
}