package com.xiaobai.livephotoutil.compat

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException

/** LivePhoto 跨厂商格式转码器。 */
class LivePhotoTranscoder {
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
            MediaIO.copySliceAsQuickTimeCompatible(asset.video, out)
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
        val imageBytes = MediaIO.readSlice(asset.image)
        val cleanedImage = stripMotionMetadata(imageBytes)
        val baseName = sanitizeName(asset.contentId)
        val outFile = File(outputDir, "${baseName}_${profile.vendor.name.lowercase()}_motion.jpg")

        val jpegWithPlaceholder = injectXmp(cleanedImage, buildXmp(profile, microVideoOffset = 0L))
        val mp4Offset = jpegWithPlaceholder.size.toLong()
        val finalJpeg = injectXmp(cleanedImage, buildXmp(profile, microVideoOffset = mp4Offset))

        MediaIO.writeToFileAtomic(outFile) { out ->
            out.write(finalJpeg)
            MediaIO.copySliceAsMp4Compatible(asset.video, out)
        }
        return ConversionResult(
            targetVendor = profile.vendor,
            mode = ContainerMode.MOTION_PHOTO_JPEG,
            outputFiles = listOf(outFile)
        )
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
     * 向 JPEG 注入 APP1 XMP 段。
     *
     * 若 JPEG 以 EOI 结束，则插入到 EOI 前，避免破坏 JPEG 结构。
     *
     * @param jpegBytes 输入 JPEG 字节数组。
     * @param xmpXml 待注入 XMP 文本。
     */
    private fun injectXmp(jpegBytes: ByteArray, xmpXml: String): ByteArray {
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.US_ASCII)
        val xmp = xmpXml.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(header.size + xmp.size)
        System.arraycopy(header, 0, payload, 0, header.size)
        System.arraycopy(xmp, 0, payload, header.size, xmp.size)

        val segmentLength = payload.size + 2
        require(segmentLength <= 0xFFFF) { "XMP payload too large for single JPEG APP1 segment." }
        val segment = ByteArray(payload.size + 4)
        segment[0] = 0xFF.toByte()
        segment[1] = 0xE1.toByte()
        segment[2] = ((segmentLength shr 8) and 0xFF).toByte()
        segment[3] = (segmentLength and 0xFF).toByte()
        System.arraycopy(payload, 0, segment, 4, payload.size)

        return if (jpegBytes.size >= 2
            && jpegBytes[jpegBytes.size - 2] == 0xFF.toByte()
            && jpegBytes[jpegBytes.size - 1] == 0xD9.toByte()
        ) {
            ByteArrayOutputStream().use { out ->
                out.write(jpegBytes, 0, jpegBytes.size - 2)
                out.write(segment)
                out.write(0xFF)
                out.write(0xD9)
                out.toByteArray()
            }
        } else {
            ByteArrayOutputStream().use { out ->
                out.write(jpegBytes)
                out.write(segment)
                out.toByteArray()
            }
        }
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
     * 移除已有 MotionPhoto 元数据段。
     *
     * 用于避免“旧厂商标记 + 新厂商标记”共存导致的识别串判。
     *
     * @param jpeg 输入 JPEG 字节数组。
     */
    private fun stripMotionMetadata(jpeg: ByteArray): ByteArray {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
            return jpeg
        }
        val out = ByteArrayOutputStream()
        out.write(0xFF)
        out.write(0xD8)
        var pos = 2
        while (pos + 3 < jpeg.size) {
            if (jpeg[pos] != 0xFF.toByte()) {
                out.write(jpeg, pos, jpeg.size - pos)
                break
            }
            var markerPos = pos + 1
            while (markerPos < jpeg.size && jpeg[markerPos] == 0xFF.toByte()) markerPos++
            if (markerPos >= jpeg.size) break
            val marker = jpeg[markerPos].toInt() and 0xFF
            if (marker == 0xD9) {
                out.write(0xFF)
                out.write(0xD9)
                break
            }
            if (marker == 0xDA) {
                out.write(jpeg, pos, jpeg.size - pos)
                break
            }
            if (markerPos + 2 >= jpeg.size) {
                out.write(jpeg, pos, jpeg.size - pos)
                break
            }
            val len = ((jpeg[markerPos + 1].toInt() and 0xFF) shl 8) or (jpeg[markerPos + 2].toInt() and 0xFF)
            val segmentStart = pos
            val segmentTotal = len + 2
            val segmentEnd = segmentStart + segmentTotal
            if (len < 2 || segmentEnd > jpeg.size) {
                out.write(jpeg, pos, jpeg.size - pos)
                break
            }
            val drop = (marker == 0xE1 || marker == 0xFE) && containsMotionKeywords(
                jpeg,
                markerPos + 3,
                len - 2
            )
            if (!drop) {
                out.write(jpeg, segmentStart, segmentTotal)
            }
            pos = segmentEnd
        }
        return out.toByteArray()
    }

    /**
     * 判断某个元数据片段中是否包含 MotionPhoto 相关关键词。
     *
     * @param buffer 原始字节缓冲区。
     * @param start 片段起始位置。
     * @param length 片段长度。
     */
    private fun containsMotionKeywords(buffer: ByteArray, start: Int, length: Int): Boolean {
        if (length <= 0 || start < 0 || start + length > buffer.size) return false
        val text = String(buffer, start, length, StandardCharsets.ISO_8859_1)
        val keys = listOf(
            "MotionPhoto",
            "MicroVideo",
            "GCamera:",
            "HwCamera:",
            "HUAWEI:",
            "vivo:",
            "VIVO:",
            "OPPO:",
            "Oplus:",
            "MiCamera:",
            "Xiaomi:"
        )
        return keys.any { key -> text.contains(key) }
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
}
