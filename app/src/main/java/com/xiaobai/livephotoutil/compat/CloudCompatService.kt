package com.xiaobai.livephotoutil.compat

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Properties
import kotlinx.coroutines.CancellationException

/**
 * 云端兼容服务：统一上传格式并按目标设备恢复。
 *
 * @property transcoder 底层转码器实例。
 */
class CloudCompatService(private val transcoder: LivePhotoTranscoder = LivePhotoTranscoder()) {
    /** 原始文件名清洗正则。 */
    private val safeNameRegex = Regex("[^a-zA-Z0-9._-]")
    /** 文件复制与摘要计算使用的缓冲区大小。 */
    private val ioBufferSize = 8192

    /**
     * 将任意协议资产归一化为云端中间格式。
     *
     * 输出包含 `image.bin`、`video.bin` 和 `manifest.properties`。
     *
     * @param asset 输入统一资产。
     * @param cloudDir 云端规范包输出目录。
     */
    fun normalizeForCloud(asset: LivePhotoAsset, cloudDir: File): CanonicalPackage {
        try {
            return DirectoryLockRegistry.withDirectoryLocks(listOf(cloudDir)) {
                val imageFile = File(cloudDir, "image.bin")
                val videoFile = File(cloudDir, "video.bin")
                val manifestFile = File(cloudDir, "manifest.properties")
                val rawDir = File(cloudDir, "raw")
                prepareCanonicalDirectory(cloudDir, imageFile, videoFile, manifestFile, rawDir)

                MediaIO.writeSlice(asset.image, imageFile)
                MediaIO.writeSlice(asset.video, videoFile)

                val properties = Properties()
                properties["vendor"] = asset.vendor.name
                properties["protocol"] = asset.protocol.name
                properties["contentId"] = asset.contentId
                properties["imageMime"] = asset.image.mimeType
                properties["videoMime"] = asset.video.mimeType
                properties["imageSize"] = imageFile.length().toString()
                properties["videoSize"] = videoFile.length().toString()
                properties["imageSha256"] = sha256(imageFile)
                properties["videoSha256"] = sha256(videoFile)
                val rawSources = collectRawSources(asset)
                properties["rawFileCount"] = rawSources.size.toString()
                rawSources.forEachIndexed { index, source ->
                    val storedName = "${index}_${sanitizeRawName(source.name)}"
                    val target = File(rawDir, storedName)
                    copyFile(source, target)
                    properties["raw.$index.originalName"] = source.name
                    properties["raw.$index.storedName"] = storedName
                    properties["raw.$index.size"] = source.length().toString()
                    properties["raw.$index.lastModified"] = source.lastModified().toString()
                    properties["raw.$index.sha256"] = sha256(target)
                }

                MediaIO.writeToFileAtomic(manifestFile) { out ->
                    properties.store(out, "LivePhoto canonical package")
                }

                CanonicalPackage(
                    dir = cloudDir,
                    imageFile = imageFile,
                    videoFile = videoFile,
                    manifestFile = manifestFile,
                    rawDir = rawDir
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Exception) {
            throw LivePhotoErrorMapper.map(throwable, LivePhotoErrorCode.INTERNAL_ERROR)
        }
    }

    /**
     * 从云端中间格式恢复为目标厂商可识别格式。
     *
     * @param canonicalDir 云端中间包目录。
     * @param targetVendor 目标设备厂商。
     * @param outputDir 本地恢复输出目录。
     * @param preferRawReplay 当目标厂商与源厂商一致时是否优先原样回放。
     */
    fun restoreForDevice(
        canonicalDir: File,
        targetVendor: DeviceVendor,
        outputDir: File,
        preferRawReplay: Boolean = true
    ): ConversionResult {
        if (targetVendor == DeviceVendor.UNKNOWN) {
            throw LivePhotoSdkException(
                code = LivePhotoErrorCode.INVALID_INPUT,
                message = "targetVendor cannot be UNKNOWN"
            )
        }
        try {
            return DirectoryLockRegistry.withDirectoryLocks(listOf(canonicalDir, outputDir)) {
                val manifest = File(canonicalDir, "manifest.properties")
                require(manifest.exists()) { "Missing canonical manifest: $manifest" }
                val props = Properties()
                FileInputStream(manifest).use { props.load(it) }

                if (preferRawReplay) {
                    val rawReplay = try {
                        restoreRawWhenVendorMatches(props, canonicalDir, targetVendor, outputDir)
                    } catch (_: Exception) {
                        // Raw 回放失败时自动回退到 canonical 转码，避免恢复流程整体失败。
                        null
                    }
                    if (rawReplay != null) return@withDirectoryLocks rawReplay
                }

                val contentId = props.getProperty("contentId", "livephoto")
                val imageMime = props.getProperty("imageMime", "image/jpeg")
                val videoMime = props.getProperty("videoMime", "video/mp4")
                val image = File(canonicalDir, "image.bin")
                val video = File(canonicalDir, "video.bin")
                require(image.exists() && video.exists()) { "Canonical media files are missing." }
                verifyCanonicalMediaIntegrity(props, image, video)

                val asset = LivePhotoAsset(
                    vendor = DeviceVendor.UNKNOWN,
                    protocol = LivePhotoProtocol.GENERIC_PAIR,
                    image = MediaSlice(image, 0L, image.length(), imageMime),
                    video = MediaSlice(video, 0L, video.length(), videoMime),
                    contentId = contentId,
                    notes = listOf("Restored from cloud canonical package.")
                )
                transcoder.transcode(asset, targetVendor, outputDir)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Exception) {
            throw LivePhotoErrorMapper.map(throwable, LivePhotoErrorCode.INTERNAL_ERROR)
        }
    }

    /**
     * 从云端包恢复原始文件（文件名 + 字节内容保持一致）。
     *
     * @param canonicalDir 云端中间包目录。
     * @param outputDir 本地恢复目录。
     */
    fun restoreOriginal(canonicalDir: File, outputDir: File): List<File> {
        try {
            return DirectoryLockRegistry.withDirectoryLocks(listOf(canonicalDir, outputDir)) {
                val manifest = File(canonicalDir, "manifest.properties")
                require(manifest.exists()) { "Missing canonical manifest: $manifest" }
                val props = Properties()
                FileInputStream(manifest).use { props.load(it) }
                restoreRawFiles(props, canonicalDir, outputDir)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Exception) {
            throw LivePhotoErrorMapper.map(throwable, LivePhotoErrorCode.INTERNAL_ERROR)
        }
    }

    /**
     * 当目标厂商与源厂商一致时，尝试返回原样回放结果。
     *
     * @param props 云端清单属性。
     * @param canonicalDir 云端中间包目录。
     * @param targetVendor 目标设备厂商。
     * @param outputDir 本地恢复输出目录。
     */
    private fun restoreRawWhenVendorMatches(
        props: Properties,
        canonicalDir: File,
        targetVendor: DeviceVendor,
        outputDir: File
    ): ConversionResult? {
        val sourceVendor = props.getProperty("vendor")?.let {
            runCatching { DeviceVendor.valueOf(it) }.getOrElse { DeviceVendor.UNKNOWN }
        } ?: DeviceVendor.UNKNOWN
        if (sourceVendor != targetVendor) return null
        val files = restoreRawFiles(props, canonicalDir, outputDir)
        if (files.isEmpty()) return null
        return ConversionResult(
            targetVendor = targetVendor,
            mode = ContainerMode.RAW_REPLAY,
            outputFiles = files
        )
    }

    /**
     * 按清单信息从 `raw` 目录恢复原始文件并校验完整性。
     *
     * @param props 云端清单属性。
     * @param canonicalDir 云端中间包目录。
     * @param outputDir 本地恢复目录。
     */
    private fun restoreRawFiles(props: Properties, canonicalDir: File, outputDir: File): List<File> {
        val rawCount = props.getProperty("rawFileCount")?.toIntOrNull() ?: 0
        require(rawCount > 0) { "Canonical package does not contain raw replay data." }
        val rawDir = File(canonicalDir, "raw")
        require(rawDir.exists() && rawDir.isDirectory) { "Canonical raw directory is missing." }
        require(!outputDir.exists() || outputDir.isDirectory) { "Output path is not a directory: $outputDir" }
        outputDir.mkdirs()

        val restored = mutableListOf<File>()
        val usedOutputPaths = HashSet<String>(rawCount)
        for (index in 0 until rawCount) {
            val storedName = requireProperty(props, "raw.$index.storedName")
            val originalName = props.getProperty("raw.$index.originalName", storedName)
            val expectedSize = props.getProperty("raw.$index.size")?.toLongOrNull()
            val expectedLastModified = props.getProperty("raw.$index.lastModified")?.toLongOrNull()
            val expectedSha256 = requireProperty(props, "raw.$index.sha256")
            val source = File(rawDir, storedName)
            require(source.exists() && source.isFile) { "Missing raw file: $source" }
            if (expectedSize != null) {
                require(source.length() == expectedSize) { "Raw file size mismatch: $source" }
            }
            require(sha256(source) == expectedSha256) { "Raw file checksum mismatch: $source" }

            val outputName = originalName.ifBlank { "raw_$index.bin" }
            val target = resolveOutputFile(outputDir, outputName)
            val targetKey = runCatching { target.canonicalPath }.getOrElse { target.absolutePath }
            require(usedOutputPaths.add(targetKey)) { "Duplicate raw output filename: $outputName" }
            copyFile(source, target)
            if (expectedLastModified != null) {
                target.setLastModified(expectedLastModified)
            }
            restored += target
        }
        return restored
    }

    /**
     * 准备云端规范包目录，清理旧输出避免残留脏数据。
     *
     * @param cloudDir 云端规范包目录。
     * @param imageFile 规范包图片文件路径。
     * @param videoFile 规范包视频文件路径。
     * @param manifestFile 规范包清单文件路径。
     * @param rawDir 规范包原始文件目录路径。
     */
    private fun prepareCanonicalDirectory(
        cloudDir: File,
        imageFile: File,
        videoFile: File,
        manifestFile: File,
        rawDir: File
    ) {
        require(!cloudDir.exists() || cloudDir.isDirectory) { "Canonical path is not a directory: $cloudDir" }
        cloudDir.mkdirs()
        deleteIfExists(imageFile)
        deleteIfExists(videoFile)
        deleteIfExists(manifestFile)
        deleteRecursively(rawDir)
        rawDir.mkdirs()
    }

    /**
     * 解析并校验原样回放输出文件，防止路径穿越。
     *
     * @param outputDir 回放输出目录。
     * @param outputName manifest 中声明的原始文件名。
     */
    private fun resolveOutputFile(outputDir: File, outputName: String): File {
        require(outputName.isNotBlank()) { "Raw output filename cannot be blank." }
        require(!outputName.contains('/')) { "Raw output filename cannot contain '/': $outputName" }
        require(!outputName.contains('\\')) { "Raw output filename cannot contain '\\': $outputName" }
        require(outputName != "." && outputName != "..") { "Raw output filename is invalid: $outputName" }
        val target = File(outputDir, outputName)
        val outputRoot = runCatching { outputDir.canonicalPath }.getOrElse { outputDir.absolutePath }
        val targetPath = runCatching { target.canonicalPath }.getOrElse { target.absolutePath }
        val rootPrefix = "$outputRoot${File.separator}"
        require(targetPath.startsWith(rootPrefix)) { "Raw output path escapes output directory: $outputName" }
        return target
    }

    /**
     * 读取必须存在的清单字段。
     *
     * @param props 清单属性集合。
     * @param key 清单字段键名。
     */
    private fun requireProperty(props: Properties, key: String): String {
        return props.getProperty(key) ?: error("Missing required manifest key: $key")
    }

    /**
     * 校验 canonical 主载荷（image/video）的大小与摘要完整性。
     *
     * 兼容旧格式：若清单中不存在对应字段则跳过该字段校验。
     *
     * @param props 清单属性集合。
     * @param image 规范包图片文件。
     * @param video 规范包视频文件。
     */
    private fun verifyCanonicalMediaIntegrity(props: Properties, image: File, video: File) {
        val expectedImageSize = props.getProperty("imageSize")?.toLongOrNull()
        if (expectedImageSize != null) {
            require(image.length() == expectedImageSize) { "Canonical image size mismatch: $image" }
        }
        val expectedVideoSize = props.getProperty("videoSize")?.toLongOrNull()
        if (expectedVideoSize != null) {
            require(video.length() == expectedVideoSize) { "Canonical video size mismatch: $video" }
        }
        val expectedImageSha = props.getProperty("imageSha256")
        if (!expectedImageSha.isNullOrBlank()) {
            require(sha256(image) == expectedImageSha) { "Canonical image checksum mismatch: $image" }
        }
        val expectedVideoSha = props.getProperty("videoSha256")
        if (!expectedVideoSha.isNullOrBlank()) {
            require(sha256(video) == expectedVideoSha) { "Canonical video checksum mismatch: $video" }
        }
    }

    /**
     * 收集资产涉及的原始文件并去重。
     *
     * @param asset 统一资产对象。
     */
    private fun collectRawSources(asset: LivePhotoAsset): List<File> {
        val unique = LinkedHashMap<String, File>()
        addIfValid(unique, asset.image.sourceFile)
        addIfValid(unique, asset.video.sourceFile)
        return unique.values.toList()
    }

    /**
     * 将有效文件加入去重映射。
     *
     * @param map 去重映射表。
     * @param file 待加入文件。
     */
    private fun addIfValid(map: LinkedHashMap<String, File>, file: File) {
        if (!file.exists() || !file.isFile) return
        val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        map[key] = file
    }

    /**
     * 清洗文件名，确保可安全写入云端 `raw` 目录。
     *
     * @param name 原始文件名。
     */
    private fun sanitizeRawName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "raw.bin"
        return trimmed.replace(safeNameRegex, "_")
    }

    /**
     * 删除单个已存在文件。
     *
     * @param file 待删除文件。
     */
    private fun deleteIfExists(file: File) {
        if (file.exists() && !file.delete()) {
            error("Failed to delete stale file: $file")
        }
    }

    /**
     * 递归删除目录或文件。
     *
     * @param path 待删除路径。
     */
    private fun deleteRecursively(path: File) {
        if (!path.exists()) return
        path.walkBottomUp().forEach { node ->
            if (!node.delete()) {
                error("Failed to delete path: $node")
            }
        }
    }

    /**
     * 复制单个文件。
     *
     * @param source 源文件。
     * @param target 目标文件。
     */
    private fun copyFile(source: File, target: File) {
        MediaIO.writeToFileAtomic(target) { output ->
            FileInputStream(source).use { input ->
                val buffer = ByteArray(ioBufferSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    /**
     * 计算文件 SHA-256 摘要。
     *
     * @param file 待计算文件。
     */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(ioBufferSize)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    /**
     * 将字节数组转为小写十六进制字符串。
     *
     * @param bytes 输入字节数组。
     */
    private fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hexDigits = "0123456789abcdef"
        var index = 0
        bytes.forEach { value ->
            val unsigned = value.toInt() and 0xFF
            chars[index++] = hexDigits[unsigned ushr 4]
            chars[index++] = hexDigits[unsigned and 0x0F]
        }
        return String(chars)
    }
}
