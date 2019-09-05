package handler.result

sealed class GetApkHandlerResult : HandlerResult {
  object Success : GetApkHandlerResult()
  object BadApkName : GetApkHandlerResult()
  object FileDoesNotExist : GetApkHandlerResult()

  class GenericExceptionResult(
    val exception: Throwable
  ) : GetApkHandlerResult()
}