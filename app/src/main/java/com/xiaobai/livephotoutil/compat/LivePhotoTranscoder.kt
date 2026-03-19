package com.xiaobai.livephotoutil.compat

import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException

/**
 * LivePhoto 跨厂商格式转码器。
 *
 * @property videoNormalizer 非 ISO BMFF 输入的视频归一化扩展点。
 */
class LivePhotoTranscoder(
    private val videoNormalizer: VideoCompatibilityNormalizer = DefaultVideoCompatibilityNormalizer
) {
    /** 输出基础名清洗正则。 */
    private val safeBaseNameRegex = Regex("[^a-zA-Z0-9_-]")

    /**
     * 将统一资产转码为目标厂商格式。
     *
     * @param asset 输入统一资产。
     * @param targetVendor 目标厂商。
     * @param outputDir 输出目录。
     */
    fun transcode(asset: LivePhotoAsset, targetVendor: DeviceVendor, outputDir: File): ConversionResult {
        if (targetVendor == DeviceVendor.UNKNOWN) {
            throw LivePhotoSdkException(
                code = LivePhotoErrorCode.INVALID_INPUT,
                message = "targetVendor cannot be UNKNOWN"
            )
        }
        try {
            return DirectoryLockRegistry.withDirectoryLocks(listOf(outputDir)) {
                outputDir.mkdirs()
                val profile = VendorProfiles.profileOf(targetVendor)
                when (profile.mode) {
                    ContainerMode.APPLE_PAIR -> toApplePair(asset, outputDir)
                    ContainerMode.MOTION_PHOTO_JPEG -> toVendorMotionPhoto(asset, profile, outputDir)
                    ContainerMode.RAW_REPLAY -> throw LivePhotoSdkException(
                        code = LivePhotoErrorCode.UNSUPPORTED_TRANSCODE_TARGET,
                        message = "RAW_REPLAY is not a transcode target mode."
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Exception) {
            throw LivePhotoErrorMapper.map(throwable, LivePhotoErrorCode.INTERNAL_ERROR)
        }
    }

    /**
     * 输出为 Apple 双文件格式，并附带 sidecar 信息。
     *
     * @param asset 输入统一资产。
     * @param outputDir 输出目录。
     */
    private fun toApplePair(asset: LivePhotoAsset, outputDir: File): ConversionResult {
        val baseName = sanitizeName(asset.contentId)
        val imageExt = imageExt(asset.image.mimeType)
        val imageOut = File(outputDir, "$baseName$imageExt")
        val videoOut = File(outputDir, "$baseName.mov")
        MediaIO.writeSlice(asset.image, imageOut)
        MediaIO.writeToFileAtomic(videoOut) { out ->
            copyVideoForContainer(asset.video, VideoContainerTarget.QUICKTIME, outputDir, out)
        }
        val assetIdentifier = buildAppleAssetIdentifier(baseName)
        val sidecar = File(outputDir, "$baseName.livephoto.properties")
        val sidecarText = listOf(
            "vendor=APPLE",
            "protocol=APPLE_PAIR",
            "contentId=$baseName",
            "imageFile=${imageOut.name}",
            "videoFile=${videoOut.name}",
            "videoContainer=quicktime",
            "sourceVendor=${asset.vendor.name}",
            "sourceProtocol=${asset.protocol.name}",
            "assetIdentifier=$assetIdentifier",
            "stillImageTimeUs=0"
        ).joinToString("\n")
        MediaIO.writeBytesAtomic(sidecar, sidecarText.toByteArray(StandardCharsets.UTF_8))
        return ConversionResult(
            targetVendor = DeviceVendor.APPLE,
            mode = ContainerMode.APPLE_PAIR,
            outputFiles = listOf(imageOut, videoOut, sidecar)
        )
    }

    /**
     * 输出为目标厂商 MotionPhoto 单文件 JPEG 格式。
     *
     * @param asset 输入统一资产。
     * @param profile 目标厂商 profile。
     * @param outputDir 输出目录。
     */
    private fun toVendorMotionPhoto(asset: LivePhotoAsset, profile: VendorProfile, outputDir: File): ConversionResult {
        if (asset.image.mimeType != "image/jpeg") {
            throw LivePhotoSdkException(
                code = LivePhotoErrorCode.UNSUPPORTED_TRANSCODE_TARGET,
                message = "Target ${profile.vendor} motion photo requires JPEG image, got ${asset.image.mimeType}"
            )
        }
        val baseName = sanitizeName(asset.contentId)
        val outFile = File(outputDir, "${baseName}_${profile.vendor.name.lowercase()}_motion.jpg")

        val placeholderXmp = buildXmp(profile, microVideoOffset = 0L)
        val mp4Offset = MediaIO.estimateJpegWithoutMotionMetadataAndInjectedXmpSize(asset.image, placeholderXmp)
        val finalXmp = buildXmp(profile, microVideoOffset = mp4Offset)

        MediaIO.writeToFileAtomic(outFile) { out ->
            MediaIO.copyJpegWithoutMotionMetadataAndInjectXmp(asset.image, finalXmp, out)
            copyVideoForContainer(asset.video, VideoContainerTarget.MP4, outputDir, out)
        }
        return ConversionResult(
            targetVendor = profile.vendor,
            mode = ContainerMode.MOTION_PHOTO_JPEG,
            outputFiles = listOf(outFile)
        )
    }

    /**
     * 复制视频到目标容器，必要时通过 [videoNormalizer] 先归一化再继续。
     *
     * @param sourceVideo 输入视频切片。
     * @param targetContainer 目标容器类型。
     * @param outputDir 输出目录（用于创建临时工作目录）。
     * @param out 目标输出流。
     */
    private fun copyVideoForContainer(
        sourceVideo: MediaSlice,
        targetContainer: VideoContainerTarget,
        outputDir: File,
        out: OutputStream
    ) {
        if (MediaIO.isIsoBmffSlice(sourceVideo)) {
            copyIsoVideoForContainer(sourceVideo, targetContainer, out)
            return
        }

        val normalizerWorkDir = File(outputDir, ".video-normalizer-${System.nanoTime()}").apply { mkdirs() }
        try {
            val normalized = try {
                videoNormalizer.normalize(sourceVideo, targetContainer, normalizerWorkDir)
            } catch (throwable: Throwable) {
                throw LivePhotoSdkException(
                    code = LivePhotoErrorCode.UNSUPPORTED_TRANSCODE_TARGET,
                    message = "Video normalizer failed for $targetContainer: ${throwable.message}",
                    cause = throwable
                )
            }

            if (normalized == null) {
                throw LivePhotoSdkException(
                    code = LivePhotoErrorCode.UNSUPPORTED_TRANSCODE_TARGET,
                    message = "Input video is not ISO BMFF and no normalizer output is available for $targetContainer."
                )
            }
            if (!MediaIO.isIsoBmffSlice(normalized)) {
                throw LivePhotoSdkException(
                    code = LivePhotoErrorCode.UNSUPPORTED_TRANSCODE_TARGET,
                    message = "Video normalizer output is not compatible with $targetContainer."
                )
            }
            copyIsoVideoForContainer(normalized, targetContainer, out)
        } finally {
            deleteRecursively(normalizerWorkDir)
        }
    }

    /**
     * 复制 ISO BMFF 视频到目标容器。
     *
     * @param video 输入视频切片。
     * @param targetContainer 目标容器类型。
     * @param out 输出流。
     */
    private fun copyIsoVideoForContainer(
        video: MediaSlice,
        targetContainer: VideoContainerTarget,
        out: OutputStream
    ) {
        when (targetContainer) {
            VideoContainerTarget.MP4 -> MediaIO.copySliceAsMp4Compatible(video, out)
            VideoContainerTarget.QUICKTIME -> MediaIO.copySliceAsQuickTimeCompatible(video, out)
        }
    }

    /**
     * 生成 Apple 输出 sidecar 使用的资产标识。
     *
     * @param normalizedContentId 已清洗的内容标识。
     */
    private fun buildAppleAssetIdentifier(normalizedContentId: String): String {
        return "LP-$normalizedContentId"
    }

    /**
     * 构造目标厂商 MotionPhoto 所需的 XMP 元数据。
     *
     * @param profile 目标厂商 profile。
     * @param microVideoOffset JPEG 尾部视频偏移。
     */
    private fun buildXmp(profile: VendorProfile, microVideoOffset: Long): String {
        val attrs = mutableMapOf<String, String>()
        attrs["GCamera:MotionPhoto"] = "1"
        attrs["GCamera:MicroVideo"] = "1"
        attrs["GCamera:MicroVideoOffset"] = microVideoOffset.toString().padStart(10, '0')
        profile.xmpAttributes.forEach { (k, v) -> attrs[k] = v }
        val attrText = attrs.entries.joinToString(" ") { (k, v) -> "$k=\"$v\"" }
        return """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                  xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                  xmlns:HwCamera="http://ns.huawei.com/photos/1.0/camera/"
                  xmlns:HUAWEI="http://ns.huawei.com/photos/1.0/vendor/"
                  xmlns:vivo="http://ns.vivo.com/photos/1.0/camera/"
                  xmlns:VIVO="http://ns.vivo.com/photos/1.0/vendor/"
                  xmlns:OPPO="http://ns.oppo.com/photos/1.0/camera/"
                  xmlns:Oplus="http://ns.oplus.com/photos/1.0/camera/"
                  xmlns:MiCamera="http://ns.xiaomi.com/photos/1.0/camera/"
                  xmlns:Xiaomi="http://ns.xiaomi.com/photos/1.0/vendor/"
                  $attrText />
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
    }

    /**
     * 归一化输出文件基础名，避免非法字符。
     *
     * @param name 原始基础名。
     */
    private fun sanitizeName(name: String): String {
        if (name.isBlank()) return "livephoto"
        val sanitized = name.replace(safeBaseNameRegex, "")
        return if (sanitized.isBlank()) "livephoto" else sanitized
    }

    /**
     * 根据图片 MIME 选择输出扩展名。
     *
     * @param mime 图片 MIME 类型。
     */
    private fun imageExt(mime: String): String {
        return when (mime) {
            "image/heic" -> ".heic"
            "image/png" -> ".png"
            else -> ".jpg"
        }
    }

    /**
     * 递归删除临时目录。
     *
     * @param dir 待删除目录。
     */
    private fun deleteRecursively(dir: File) {
        if (!dir.exists()) return
        dir.walkBottomUp().forEach { file -> file.delete() }
    }
}
