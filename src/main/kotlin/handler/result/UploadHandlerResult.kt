package handler.result

sealed class UploadHandlerResult : HandlerResult {
  object Success : UploadHandlerResult()
  object NoApkVersion : UploadHandlerResult()
  object ApkVersionMustBeNumeric : UploadHandlerResult()
  object SecretKeyIsBad : UploadHandlerResult()
  object FileAlreadyExists : UploadHandlerResult()
  object BadAmountOfRequestParts : UploadHandlerResult()
  object RequestPartIsNotPresent : UploadHandlerResult()
  object CouldNotReadApkFileHeader : UploadHandlerResult()
  object NotAnApkFile : UploadHandlerResult()
  object CommitsFileIsTooBig : UploadHandlerResult()
  object ApkFileIsTooBig : UploadHandlerResult()

  class GenericExceptionResult(
    val exception: Throwable
  ) : UploadHandlerResult()
}