package com.xiaobai.livephotoutil.compat

import java.io.File

/** 支持的设备厂商类型。 */
enum class DeviceVendor {
    APPLE,
    GOOGLE,
    HUAWEI,
    VIVO,
    OPPO,
    XIAOMI,
    UNKNOWN
}

/** 统一抽象后的 LivePhoto 协议类型。 */
enum class LivePhotoProtocol {
    APPLE_PAIR,
    MOTION_PHOTO,
    GENERIC_PAIR
}

/** 输出容器形态。 */
enum class ContainerMode {
    APPLE_PAIR,
    MOTION_PHOTO_JPEG,
    RAW_REPLAY
}

/**
 * 媒体切片描述。
 *
 * 可表示整文件，也可表示单文件中的局部片段（例如 MotionPhoto 中 JPEG/MP4 的分段）。
 *
 * @property sourceFile 切片来源文件路径。
 * @property offset 切片起始偏移（字节）。
 * @property length 切片长度（字节）。
 * @property mimeType 切片媒体 MIME 类型。
 */
data class MediaSlice(
    val sourceFile: File,
    val offset: Long,
    val length: Long,
    val mimeType: String
) {
    /** 校验切片边界参数。 */
    init {
        require(offset >= 0L) { "offset must be >= 0" }
        require(length > 0L) { "length must be > 0" }
    }
}

/**
 * 标准化后的动态照片资源模型。
 *
 * @property vendor 识别出的来源厂商。
 * @property protocol 识别出的协议类型。
 * @property image 封面图切片。
 * @property video 动态视频切片。
 * @property contentId 资源内容标识，用于跨格式关联。
 * @property notes 处理过程附加说明。
 */
data class LivePhotoAsset(
    val vendor: DeviceVendor,
    val protocol: LivePhotoProtocol,
    val image: MediaSlice,
    val video: MediaSlice,
    val contentId: String,
    val notes: List<String> = emptyList()
)

/**
 * 探测输入上下文。
 *
 * @property candidates 候选输入文件列表。
 */
class ProbeInput(val candidates: List<File>) {
    /** MIME 探测缓存，避免重复读取同一文件头。 */
    private val mimeCache = HashMap<File, String>(candidates.size)
    /** 文件前缀缓存，优先保存已读取的最大前缀。 */
    private val prefixCache = HashMap<File, ByteArray>(candidates.size)

    /**
     * 获取文件 MIME（带缓存）。
     *
     * @param file 待探测文件。
     */
    fun mimeOf(file: File): String {
        return mimeCache[file] ?: MediaIO.detectMime(file).also { mimeCache[file] = it }
    }

    /**
     * 读取文件前缀（带缓存）。
     *
     * 若缓存中已有更长前缀，则直接截取返回，避免重复 IO。
     *
     * @param file 待读取文件。
     * @param maxBytes 最大读取字节数。
     */
    fun prefixOf(file: File, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "maxBytes must be > 0" }
        val cached = prefixCache[file]
        if (cached != null && cached.size >= maxBytes) {
            return if (cached.size == maxBytes) cached else cached.copyOf(maxBytes)
        }
        val loaded = MediaIO.readPrefix(file, maxBytes)
        if (loaded.size > (cached?.size ?: 0)) {
            prefixCache[file] = loaded
        }
        return loaded
    }
}

/**
 * 格式转换结果。
 *
 * @property targetVendor 目标输出厂商。
 * @property mode 输出容器模式。
 * @property outputFiles 输出文件列表。
 */
data class ConversionResult(
    val targetVendor: DeviceVendor,
    val mode: ContainerMode,
    val outputFiles: List<File>
)

/**
 * 云端中间规范包位置集合。
 *
 * @property dir 规范包目录。
 * @property imageFile 规范包中的图片文件。
 * @property videoFile 规范包中的视频文件。
 * @property manifestFile 规范包中的清单文件。
 * @property rawDir 原始文件目录（用于无损原样回放）。
 */
data class CanonicalPackage(
    val dir: File,
    val imageFile: File,
    val videoFile: File,
    val manifestFile: File,
    val rawDir: File
)
