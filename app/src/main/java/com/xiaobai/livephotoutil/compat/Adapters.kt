package com.xiaobai.livephotoutil.compat

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs

/**
 * 图片与视频的最佳配对结果。
 *
 * @property image 匹配到的图片文件。
 * @property video 匹配到的视频文件。
 * @property score 配对分值。
 */
private data class PairCandidate(
    val image: File,
    val video: File,
    val score: Int
)

/**
 * 配对搜索结果。
 *
 * @property best 分值最高的配对结果。
 * @property secondBestScore 第二高分值（若不存在则为 `null`）。
 */
private data class PairSearchResult(
    val best: PairCandidate,
    val secondBestScore: Int?
)

/**
 * MotionPhoto 探测时提取到的元数据。
 *
 * @property xmpPacket 提取出的 XMP 包文本。
 * @property expectedVideoOffsets 根据 XMP 解析出的候选视频起始偏移集合。
 */
private data class MotionPhotoProbeMetadata(
    val xmpPacket: String,
    val expectedVideoOffsets: Set<Long>
)

/**
 * 在候选图片与视频中选出分值最高的一组配对。
 *
 * @param images 图片候选列表。
 * @param videos 视频候选列表。
 * @param extraScore 额外加分规则。
 */
private fun findBestPair(
    images: List<File>,
    videos: List<File>,
    extraScore: (File, File) -> Int
): PairSearchResult? {
    if (images.isEmpty() || videos.isEmpty()) return null
    val stemCache = HashMap<File, String>(images.size + videos.size)
    val normCache = HashMap<File, String>(images.size + videos.size)
    fun stem(file: File): String {
        return stemCache[file] ?: NameHeuristics.stem(file).lowercase(Locale.ROOT).also { stemCache[file] = it }
    }
    fun norm(file: File): String {
        return normCache[file] ?: NameHeuristics.normalizedStem(file).also { normCache[file] = it }
    }

    var best: PairCandidate? = null
    var secondBestScore: Int? = null
    images.forEach { image ->
        videos.forEach { video ->
            var score = pairScoreFast(stem(image), norm(image), stem(video), norm(video))
            score += extraScore(image, video)
            val current = best
            if (current == null || score > current.score) {
                secondBestScore = current?.score ?: secondBestScore
                best = PairCandidate(image = image, video = video, score = score)
            } else {
                if (secondBestScore == null || score > secondBestScore!!) {
                    secondBestScore = score
                }
            }
        }
    }
    val winner = best ?: return null
    return PairSearchResult(best = winner, secondBestScore = secondBestScore)
}

/**
 * 使用预计算的 stem/normalized 值计算配对分值。
 *
 * @param imageStem 图片 stem（小写）。
 * @param imageNorm 图片归一化 stem。
 * @param videoStem 视频 stem（小写）。
 * @param videoNorm 视频归一化 stem。
 */
private fun pairScoreFast(imageStem: String, imageNorm: String, videoStem: String, videoNorm: String): Int {
    if (imageStem == videoStem) return 4
    if (imageNorm.isNotBlank() && imageNorm == videoNorm) return 3
    if (imageStem.startsWith(videoStem) || videoStem.startsWith(imageStem)) return 2
    val lcp = longestCommonPrefixFast(imageStem, videoStem)
    return if (lcp >= 6) 1 else 0
}

/**
 * 计算两个字符串的最长公共前缀长度。
 *
 * @param a 字符串 A。
 * @param b 字符串 B。
 */
private fun longestCommonPrefixFast(a: String, b: String): Int {
    val length = minOf(a.length, b.length)
    var index = 0
    while (index < length && a[index] == b[index]) index++
    return index
}

/**
 * 从文件前缀中提取 MotionPhoto 探测元数据。
 *
 * @param file 目标文件。
 * @param prefix 文件前缀字节。
 */
private fun extractMotionPhotoMetadata(file: File, prefix: ByteArray): MotionPhotoProbeMetadata? {
    val xmp = extractXmpPacket(prefix) ?: return null
    val offsets = parseMicroVideoOffsets(xmp, file.length())
    if (offsets.isEmpty()) return null
    return MotionPhotoProbeMetadata(
        xmpPacket = xmp,
        expectedVideoOffsets = offsets
    )
}

/**
 * 从前缀文本中提取 XMP 包。
 *
 * @param prefix 文件前缀字节数组。
 */
private fun extractXmpPacket(prefix: ByteArray): String? {
    val text = String(prefix, StandardCharsets.ISO_8859_1)
    val start = listOf("<x:xmpmeta", "<xmpmeta")
        .map { marker -> text.indexOf(marker) }
        .filter { index -> index >= 0 }
        .minOrNull() ?: return null
    val endWithLength = listOf("</x:xmpmeta>" to 12, "</xmpmeta>" to 10)
        .map { (endTag, length) -> text.indexOf(endTag, start).let { idx -> idx to length } }
        .firstOrNull { (idx, _) -> idx >= 0 } ?: return null
    val endExclusive = endWithLength.first + endWithLength.second
    if (endExclusive <= start) return null
    return text.substring(start, endExclusive)
}

/**
 * 解析 XMP 中的 MicroVideoOffset，并转换为可能的视频起始偏移。
 *
 * 同时兼容“从文件头计数”和“从文件尾反向计数”两种写法。
 *
 * @param xmp XMP 包文本。
 * @param fileSize 文件大小。
 */
private fun parseMicroVideoOffsets(xmp: String, fileSize: Long): Set<Long> {
    val result = LinkedHashSet<Long>()
    val regex = Regex("""[A-Za-z0-9:_-]*MicroVideoOffset\s*=\s*["'](\d+)["']""")
    regex.findAll(xmp).forEach { match ->
        val raw = match.groupValues[1].toLongOrNull() ?: return@forEach
        if (raw in 1 until fileSize) {
            result += raw
        }
        val fromTail = fileSize - raw
        if (fromTail in 1 until fileSize) {
            result += fromTail
        }
    }
    return result
}

/**
 * 判断元数据是否满足厂商 profile 要求。
 *
 * @param metadata MotionPhoto 元数据。
 * @param profile 厂商 profile。
 */
private fun matchesVendorMotionMetadata(metadata: MotionPhotoProbeMetadata, profile: VendorProfile): Boolean {
    val markerMatched = profile.detectMarkers.any { marker -> metadata.xmpPacket.contains(marker) }
    if (!markerMatched) return false
    if (profile.xmpAttributes.isEmpty()) return true
    return profile.xmpAttributes.any { (name, value) ->
        hasExactXmpAttribute(metadata.xmpPacket, name, value)
    }
}

/**
 * 判断 XMP 中是否存在名称和值都精确匹配的属性。
 *
 * @param xmp XMP 包文本。
 * @param name 属性名。
 * @param value 属性值。
 */
private fun hasExactXmpAttribute(xmp: String, name: String, value: String): Boolean {
    val pattern = Regex("""\b${Regex.escape(name)}\s*=\s*(['"])${Regex.escape(value)}\1""")
    return pattern.containsMatchIn(xmp)
}

/**
 * 判断探测到的视频偏移是否与 XMP 预期一致。
 *
 * @param expectedOffsets XMP 解析出的候选偏移集合。
 * @param actualOffset 实际探测偏移。
 */
private fun isOffsetConsistent(expectedOffsets: Set<Long>, actualOffset: Long): Boolean {
    if (expectedOffsets.isEmpty()) return false
    return expectedOffsets.any { expected ->
        abs(expected - actualOffset) <= 16L
    }
}

/**
 * Generic 兜底配对的额外加分规则。
 *
 * @param image 图片文件。
 * @param video 视频文件。
 */
private fun genericPairExtraScore(image: File, video: File): Int {
    var score = 0
    val imageParent = runCatching { image.parentFile?.canonicalPath }.getOrElse { image.parentFile?.absolutePath }
    val videoParent = runCatching { video.parentFile?.canonicalPath }.getOrElse { video.parentFile?.absolutePath }
    if (imageParent != null && imageParent == videoParent) {
        score += 1
    }
    if (abs(image.lastModified() - video.lastModified()) <= 5 * 60 * 1000L) {
        score += 1
    }
    return score
}

/** LivePhoto 协议探测适配器。 */
interface LivePhotoAdapter {
    /** 适配器唯一标识。 */
    val id: String
    /** 探测优先级，越大越先执行。 */
    val priority: Int
    /**
     * 尝试从输入文件中识别 LivePhoto 资源。
     *
     * @param input 待探测输入上下文。
     * @return 识别成功返回 [LivePhotoAsset]，否则返回 `null`。
     */
    fun probe(input: ProbeInput): LivePhotoAsset?
}

/** Apple 双文件（图片+视频）协议探测器。 */
class ApplePairAdapter : LivePhotoAdapter {
    /** 适配器标识。 */
    override val id: String = "apple-pair"
    /** 适配器优先级。 */
    override val priority: Int = VendorProfiles.profileOf(DeviceVendor.APPLE).priority

    /**
     * 基于 MIME 和文件名相似度寻找最可信的图片/视频配对。
     *
     * @param input 待探测输入上下文。
     */
    override fun probe(input: ProbeInput): LivePhotoAsset? {
        val images = mutableListOf<File>()
        val videos = mutableListOf<File>()
        input.candidates.forEach { file ->
            val mime = input.mimeOf(file)
            when {
                MediaIO.isImage(mime) -> images.add(file)
                MediaIO.isVideo(mime) -> videos.add(file)
            }
        }
        val pairResult = findBestPair(images, videos) { _, video ->
            if (input.mimeOf(video) == "video/quicktime") 1 else 0
        } ?: return null
        if (pairResult.best.score < 2) return null
        val id = NameHeuristics.normalizedStem(pairResult.best.image)
            .ifBlank { NameHeuristics.stem(pairResult.best.image) }
        return LivePhotoAsset(
            vendor = DeviceVendor.APPLE,
            protocol = LivePhotoProtocol.APPLE_PAIR,
            image = MediaIO.wholeFileSlice(pairResult.best.image),
            video = MediaIO.wholeFileSlice(pairResult.best.video),
            contentId = id,
            notes = listOf("Apple pair adapter selected.")
        )
    }
}

/**
 * 某一厂商 MotionPhoto（单 JPEG 内嵌 MP4）协议探测器。
 *
 * @property profile 目标厂商 profile。
 */
class VendorMotionPhotoAdapter(private val profile: VendorProfile) : LivePhotoAdapter {
    /** 适配器标识。 */
    override val id: String = "motion-${profile.vendor.name.lowercase()}"
    /** 适配器优先级。 */
    override val priority: Int = profile.priority

    /**
     * 根据厂商 marker + 内嵌 MP4 偏移识别 MotionPhoto。
     *
     * @param input 待探测输入上下文。
     */
    override fun probe(input: ProbeInput): LivePhotoAsset? {
        input.candidates.forEach { file ->
            if (input.mimeOf(file) != "image/jpeg") return@forEach
            val prefix = input.prefixOf(file, 256 * 1024)
            if (prefix.isEmpty()) return@forEach
            val metadata = extractMotionPhotoMetadata(file, prefix) ?: return@forEach
            if (!matchesVendorMotionMetadata(metadata, profile)) return@forEach
            val offset = MediaIO.findEmbeddedMp4Offset(file)
            if (offset <= 0L) return@forEach
            if (!isOffsetConsistent(metadata.expectedVideoOffsets, offset)) return@forEach
            if (!MediaIO.hasIsoBmffFtypAt(file, offset)) return@forEach
            val size = file.length()
            if (size - offset < 12L) return@forEach
            val id = NameHeuristics.normalizedStem(file).ifBlank { NameHeuristics.stem(file) }
            return LivePhotoAsset(
                vendor = profile.vendor,
                protocol = LivePhotoProtocol.MOTION_PHOTO,
                image = MediaSlice(file, 0L, offset, "image/jpeg"),
                video = MediaSlice(file, offset, size - offset, "video/mp4"),
                contentId = id,
                notes = listOf("${profile.vendor} motion photo adapter selected.")
            )
        }
        return null
    }
}

/** 通用兜底探测器：只要能组成图片+视频配对就可识别。 */
class GenericPairAdapter : LivePhotoAdapter {
    /** Generic 兜底识别的最低配对分值，防止低置信度误配。 */
    private val minGenericPairScore = 3

    /** 适配器标识。 */
    override val id: String = "generic-pair"
    /** 适配器优先级。 */
    override val priority: Int = 100

    /**
     * 在未命中厂商特定规则时提供最大兼容的兜底识别。
     *
     * @param input 待探测输入上下文。
     */
    override fun probe(input: ProbeInput): LivePhotoAsset? {
        val images = mutableListOf<File>()
        val videos = mutableListOf<File>()
        input.candidates.forEach { file ->
            val mime = input.mimeOf(file)
            when {
                MediaIO.isImage(mime) -> images.add(file)
                MediaIO.isVideo(mime) -> videos.add(file)
            }
        }
        val pairResult = findBestPair(images, videos) { image, video ->
            genericPairExtraScore(image, video)
        } ?: return null
        if (pairResult.best.score < minGenericPairScore) return null
        if (pairResult.secondBestScore != null && pairResult.best.score == pairResult.secondBestScore) return null
        return LivePhotoAsset(
            vendor = DeviceVendor.UNKNOWN,
            protocol = LivePhotoProtocol.GENERIC_PAIR,
            image = MediaIO.wholeFileSlice(pairResult.best.image),
            video = MediaIO.wholeFileSlice(pairResult.best.video),
            contentId = NameHeuristics.normalizedStem(pairResult.best.image).ifBlank { NameHeuristics.stem(pairResult.best.image) },
            notes = listOf("Generic pair fallback selected.")
        )
    }
}
