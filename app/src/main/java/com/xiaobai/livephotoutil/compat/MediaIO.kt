package com.xiaobai.livephotoutil.compat

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Locale

/** 底层媒体读写与格式探测工具。 */
object MediaIO {
    /** 文件 IO 默认缓冲区大小。 */
    private const val IO_BUFFER_SIZE = 8192

    /**
     * 基于文件头魔数与扩展名猜测 MIME 类型。
     *
     * 该方法用于快速分流适配器，不保证等同于完整编解码器判断。
     *
     * @param file 输入文件。
     */
    fun detectMime(file: File): String {
        val head = readHead(file, 64)
        if (isJpeg(head)) return "image/jpeg"
        if (isPng(head)) return "image/png"
        if (isIsoBmff(head)) {
            val brand = String(head.copyOfRange(8, 12), StandardCharsets.US_ASCII)
            if (brand == "qt  ") return "video/quicktime"
            if (brand == "heic" || brand == "heif" || brand == "mif1" || brand == "msf1") return "image/heic"
            return "video/mp4"
        }
        val name = file.name.lowercase(Locale.ROOT)
        return when {
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".heic") || name.endsWith(".heif") -> "image/heic"
            name.endsWith(".mov") -> "video/quicktime"
            name.endsWith(".mp4") -> "video/mp4"
            else -> "application/octet-stream"
        }
    }

    /**
     * 判断 MIME 是否为图片类型。
     *
     * @param mime 待判断的 MIME 字符串。
     */
    fun isImage(mime: String): Boolean = mime.startsWith("image/")
    /**
     * 判断 MIME 是否为视频类型。
     *
     * @param mime 待判断的 MIME 字符串。
     */
    fun isVideo(mime: String): Boolean = mime.startsWith("video/")

    /**
     * 将整个文件包装为一个 [MediaSlice]。
     *
     * @param file 输入文件。
     */
    fun wholeFileSlice(file: File): MediaSlice {
        return MediaSlice(file, 0L, file.length(), detectMime(file))
    }

    /**
     * 把切片内容完整读入内存。
     *
     * @param slice 待读取的媒体切片。
     */
    fun readSlice(slice: MediaSlice): ByteArray {
        validateSliceBounds(slice)
        require(slice.length <= Int.MAX_VALUE.toLong()) { "Slice too large to fit into memory byte array." }
        val data = ByteArray(slice.length.toInt())
        RandomAccessFile(slice.sourceFile, "r").use { raf ->
            raf.seek(slice.offset)
            var offset = 0
            while (offset < data.size) {
                val read = raf.read(data, offset, data.size - offset)
                if (read < 0) error("Unexpected EOF while reading media slice")
                offset += read
            }
        }
        return data
    }

    /**
     * 将切片内容写入给定输出流。
     *
     * @param slice 待读取的媒体切片。
     * @param out 目标输出流。
     */
    fun copySlice(slice: MediaSlice, out: OutputStream) {
        validateSliceBounds(slice)
        RandomAccessFile(slice.sourceFile, "r").use { raf ->
            raf.seek(slice.offset)
            var remaining = slice.length
            val buffer = ByteArray(IO_BUFFER_SIZE)
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read < 0) error("Unexpected EOF while reading media slice")
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    /**
     * 将切片内容写入目标文件。
     *
     * @param slice 待读取的媒体切片。
     * @param target 输出文件路径。
     */
    fun writeSlice(slice: MediaSlice, target: File) {
        writeToFileAtomic(target) { out ->
            copySlice(slice, out)
        }
    }

    /**
     * 以原子替换方式写文件。
     *
     * 先写临时文件，写完后再替换目标文件，避免目标文件处于半写状态。
     *
     * @param target 目标文件。
     * @param writer 写入动作。
     */
    fun writeToFileAtomic(target: File, writer: (OutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val temp = File(
            target.parentFile ?: error("Target parent directory is missing."),
            "${target.name}.tmp.${System.identityHashCode(Thread.currentThread())}.${System.nanoTime()}"
        )
        FileOutputStream(temp).use { out ->
            writer(out)
            out.fd.sync()
        }
        replaceWithTemp(target, temp)
    }

    /**
     * 以原子替换方式写入字节数组。
     *
     * @param target 目标文件。
     * @param bytes 待写入字节数组。
     */
    fun writeBytesAtomic(target: File, bytes: ByteArray) {
        writeToFileAtomic(target) { out -> out.write(bytes) }
    }

    /**
     * 在 JPEG 单文件中查找内嵌 MP4 片段的偏移。
     *
     * 通过扫描 `ftyp` box 起点推断视频段开始位置。
     *
     * @param file 待扫描的 JPEG 文件。
     */
    fun findEmbeddedMp4Offset(file: File): Long {
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            if (size < 24) return -1L
            val buf = ByteArray(8192)
            val overlap = 7
            val carry = ByteArray(overlap)
            var carryLen = 0
            var pos = 0L
            while (pos < size) {
                val readLen = minOf(buf.size.toLong(), size - pos).toInt()
                val read = raf.read(buf, 0, readLen)
                if (read <= 0) break
                val merged = ByteArray(carryLen + read)
                System.arraycopy(carry, 0, merged, 0, carryLen)
                System.arraycopy(buf, 0, merged, carryLen, read)
                val mergedStart = pos - carryLen
                for (i in 4..(merged.size - 8)) {
                    if (merged[i] == 'f'.code.toByte()
                        && merged[i + 1] == 't'.code.toByte()
                        && merged[i + 2] == 'y'.code.toByte()
                        && merged[i + 3] == 'p'.code.toByte()
                    ) {
                        val start = mergedStart + i - 4
                        if (start > 0 && size - start >= 16) return start
                    }
                }
                carryLen = minOf(overlap, merged.size)
                System.arraycopy(merged, merged.size - carryLen, carry, 0, carryLen)
                pos += read
            }
            return -1L
        }
    }

    /**
     * 读取文件前缀字节，最多 [maxBytes]。
     *
     * @param file 输入文件。
     * @param maxBytes 最多读取的字节数。
     */
    fun readPrefix(file: File, maxBytes: Int): ByteArray {
        val bytes = ByteArray(maxBytes)
        val totalRead = FileInputStream(file).use { input ->
            var offset = 0
            while (offset < maxBytes) {
                val read = input.read(bytes, offset, maxBytes - offset)
                if (read <= 0) break
                offset += read
            }
            offset
        }
        if (totalRead <= 0) return ByteArray(0)
        if (totalRead == maxBytes) return bytes
        return bytes.copyOf(totalRead)
    }

    /**
     * 读取文件头部用于魔数判断。
     *
     * @param file 输入文件。
     * @param maxBytes 读取长度。
     */
    private fun readHead(file: File, maxBytes: Int): ByteArray = readPrefix(file, maxBytes)

    /**
     * 校验切片边界是否处于源文件有效范围内。
     *
     * @param slice 待校验切片。
     */
    private fun validateSliceBounds(slice: MediaSlice) {
        require(slice.sourceFile.exists() && slice.sourceFile.isFile) { "Slice source file is invalid: ${slice.sourceFile}" }
        val fileSize = slice.sourceFile.length()
        require(slice.offset <= fileSize) { "Slice offset out of file range: ${slice.sourceFile}" }
        require(slice.length <= fileSize - slice.offset) { "Slice range exceeds file size: ${slice.sourceFile}" }
    }

    /**
     * 使用临时文件替换目标文件。
     *
     * @param target 最终目标文件。
     * @param temp 已写入完成的临时文件。
     */
    private fun replaceWithTemp(target: File, temp: File) {
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Failed to delete existing target file: $target")
        }
        if (!temp.renameTo(target)) {
            FileInputStream(temp).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(IO_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            temp.delete()
        }
    }

    /**
     * 判断字节前缀是否为 JPEG。
     *
     * @param head 文件头字节数组。
     */
    private fun isJpeg(head: ByteArray): Boolean {
        return head.size >= 3
            && head[0].toInt() and 0xFF == 0xFF
            && head[1].toInt() and 0xFF == 0xD8
            && head[2].toInt() and 0xFF == 0xFF
    }

    /**
     * 判断字节前缀是否为 PNG。
     *
     * @param head 文件头字节数组。
     */
    private fun isPng(head: ByteArray): Boolean {
        return head.size >= 8
            && head[0].toInt() and 0xFF == 0x89
            && head[1] == 'P'.code.toByte()
            && head[2] == 'N'.code.toByte()
            && head[3] == 'G'.code.toByte()
            && head[4].toInt() and 0xFF == 0x0D
            && head[5].toInt() and 0xFF == 0x0A
            && head[6].toInt() and 0xFF == 0x1A
            && head[7].toInt() and 0xFF == 0x0A
    }

    /**
     * 判断字节前缀是否为 ISO BMFF（如 MP4/HEIC）。
     *
     * @param head 文件头字节数组。
     */
    private fun isIsoBmff(head: ByteArray): Boolean {
        return head.size >= 12
            && head[4] == 'f'.code.toByte()
            && head[5] == 't'.code.toByte()
            && head[6] == 'y'.code.toByte()
            && head[7] == 'p'.code.toByte()
    }
}

/** 文件名匹配启发式工具，用于图片/视频配对。 */
object NameHeuristics {
    /** 归一化时需要剔除的噪声词。 */
    private val NOISY_TOKENS = listOf("livephoto", "motionphoto", "motion", "photo", "image", "video", "img", "cover")
    /** 归一化时用于清理非字母数字字符的正则。 */
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]")

    /**
     * 获取文件去扩展名的 stem。
     *
     * @param file 输入文件。
     */
    fun stem(file: File): String {
        val name = file.name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /**
     * 归一化 stem（小写、去噪词、去符号）用于更稳定的配对比较。
     *
     * @param file 输入文件。
     */
    fun normalizedStem(file: File): String {
        var value = stem(file).lowercase(Locale.ROOT)
        NOISY_TOKENS.forEach { token -> value = value.replace(token, "") }
        return value.replace(NON_ALNUM_REGEX, "")
    }

    /**
     * 计算图片文件和视频文件的配对分值，分值越高越可信。
     *
     * @param image 图片路径。
     * @param video 视频路径。
     */
    fun pairScore(image: File, video: File): Int {
        val imageStem = stem(image).lowercase(Locale.ROOT)
        val videoStem = stem(video).lowercase(Locale.ROOT)
        if (imageStem == videoStem) return 4
        val imageNorm = normalizedStem(image)
        val videoNorm = normalizedStem(video)
        if (imageNorm.isNotBlank() && imageNorm == videoNorm) return 3
        if (imageStem.startsWith(videoStem) || videoStem.startsWith(imageStem)) return 2
        val lcp = longestCommonPrefix(imageStem, videoStem)
        return if (lcp >= 6) 1 else 0
    }

    /**
     * 计算两个字符串的最长公共前缀长度。
     *
     * @param a 字符串 A。
     * @param b 字符串 B。
     */
    private fun longestCommonPrefix(a: String, b: String): Int {
        val length = minOf(a.length, b.length)
        var index = 0
        while (index < length && a[index] == b[index]) index++
        return index
    }
}
