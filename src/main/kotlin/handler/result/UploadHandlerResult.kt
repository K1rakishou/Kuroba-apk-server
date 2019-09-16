package handler.result

sealed class UploadHandlerResult : HandlerResult {
  object Success : UploadHandlerResult()
  object NoApkVersion : UploadHandlerResult()
  object ApkVersionMustBeNumeric : UploadHandlerResult()
  object SecretKeyIsBad : UploadHandlerResult()
  object BadAmountOfRequestParts : UploadHandlerResult()
  object RequestPartIsNotPresent : UploadHandlerResult()
  object CouldNotReadApkFileHeader : UploadHandlerResult()
  object NotAnApkFile : UploadHandlerResult()

  class GenericExceptionResult(
    val exception: Throwable
  ) : UploadHandlerResult()
}