package com.xiaobai.livephotoutil.compat

import java.io.FileNotFoundException
import java.io.IOException

/** SDK 统一错误码。 */
enum class LivePhotoErrorCode {
    INVALID_INPUT,
    DETECTION_FAILED,
    UNSUPPORTED_VENDOR,
    UNSUPPORTED_TRANSCODE_TARGET,
    CANONICAL_MANIFEST_MISSING,
    CANONICAL_PAYLOAD_MISSING,
    INTEGRITY_CHECK_FAILED,
    RAW_REPLAY_UNAVAILABLE,
    OUTPUT_PATH_INVALID,
    FILE_IO_ERROR,
    INTERNAL_ERROR
}

/**
 * SDK 统一异常类型。
 *
 * 继承 `IllegalArgumentException`，以保持与既有调用方的异常捕获兼容。
 *
 * @property code 结构化错误码。
 */
class LivePhotoSdkException(
    val code: LivePhotoErrorCode,
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

/** SDK 异常映射器。 */
object LivePhotoErrorMapper {
    /**
     * 将任意异常映射为 [LivePhotoSdkException]。
     *
     * @param throwable 原始异常。
     * @param defaultCode 无法识别时的默认错误码。
     */
    fun map(throwable: Throwable, defaultCode: LivePhotoErrorCode): LivePhotoSdkException {
        if (throwable is LivePhotoSdkException) return throwable
        val message = throwable.message ?: throwable.javaClass.simpleName
        val lower = message.lowercase()
        val code = when {
            throwable is FileNotFoundException || throwable is IOException -> LivePhotoErrorCode.FILE_IO_ERROR
            lower.contains("cannot detect supported livephoto") -> LivePhotoErrorCode.DETECTION_FAILED
            lower.contains("unsupported vendor profile") -> LivePhotoErrorCode.UNSUPPORTED_VENDOR
            lower.contains("requires jpeg image")
                || lower.contains("iso bmff")
                || lower.contains("normalizer output")
                || lower.contains("no normalizer output") -> LivePhotoErrorCode.UNSUPPORTED_TRANSCODE_TARGET
            lower.contains("raw output path escapes") || lower.contains("cannot contain '/'") || lower.contains("cannot contain '\\'") ->
                LivePhotoErrorCode.OUTPUT_PATH_INVALID
            lower.contains("missing canonical manifest") -> LivePhotoErrorCode.CANONICAL_MANIFEST_MISSING
            lower.contains("canonical media files are missing") -> LivePhotoErrorCode.CANONICAL_PAYLOAD_MISSING
            lower.contains("checksum mismatch")
                || lower.contains("size mismatch")
                || lower.contains("manifest signature mismatch")
                || lower.contains("manifest signature is present")
                || lower.contains("unsupported manifest signature algorithm")
                || lower.contains("manifest signature is required")
                || lower.contains("verification key is ambiguous") -> LivePhotoErrorCode.INTEGRITY_CHECK_FAILED
            lower.contains("raw replay") || lower.contains("raw directory is missing") || lower.contains("raw file") ->
                LivePhotoErrorCode.RAW_REPLAY_UNAVAILABLE
            lower.contains("targetvendor cannot be unknown") || lower.contains("invalid file") || lower.contains("candidates cannot be empty") ->
                LivePhotoErrorCode.INVALID_INPUT
            else -> defaultCode
        }
        return LivePhotoSdkException(code = code, message = message, cause = throwable)
    }
}
