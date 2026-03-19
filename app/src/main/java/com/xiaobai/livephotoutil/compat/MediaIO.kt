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
    /** ISO BMFF `ftyp` box 最小合法长度。 */
    private const val MIN_FTYP_BOX_SIZE = 16L
    /** 查找 `ftyp` 时允许扫描的最大头部范围（字节）。 */
    private const val MAX_FTYP_SEARCH_BYTES = 1024L * 1024L
    /** ISO BMFF `ftyp` box 类型标识。 */
    private val FTYP_TYPE = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
    /** 标准 MP4 目标主品牌（major brand）。 */
    private val BRAND_ISOM = byteArrayOf('i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte())
    /** 常见 MP4 兼容品牌。 */
    private val BRAND_MP42 = byteArrayOf('m'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte())
    /** QuickTime 主品牌（major brand）。 */
    private val BRAND_QT = byteArrayOf('q'.code.toByte(), 't'.code.toByte(), ' '.code.toByte(), ' '.code.toByte())

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
     * 估算“移除旧 Motion 元数据并注入 XMP”后的 JPEG 字节长度。
     *
     * @param slice 输入 JPEG 切片。
     * @param xmpXml 待注入的 XMP 文本。
     */
    fun estimateJpegWithoutMotionMetadataAndInjectedXmpSize(slice: MediaSlice, xmpXml: String): Long {
        val counter = CountingOutputStream()
        copyJpegWithoutMotionMetadataAndInjectXmp(slice, xmpXml, counter)
        return counter.count
    }

    /**
     * 以流式方式输出“移除旧 Motion 元数据并注入 XMP”后的 JPEG。
     *
     * 该方法不会把整张图片读入内存，适合大图生产场景。
     *
     * @param slice 输入 JPEG 切片。
     * @param xmpXml 待注入的 XMP 文本。
     * @param out 输出流。
     */
    fun copyJpegWithoutMotionMetadataAndInjectXmp(slice: MediaSlice, xmpXml: String, out: OutputStream): Long {
        validateSliceBounds(slice)
        val xmpSegment = buildXmpApp1Segment(xmpXml)
        RandomAccessFile(slice.sourceFile, "r").use { raf ->
            raf.seek(slice.offset)
            var remaining = slice.length
            require(remaining >= 2L) { "JPEG slice is too small: ${slice.sourceFile}" }
            val soi0 = raf.read()
            val soi1 = raf.read()
            require(soi0 == 0xFF && soi1 == 0xD8) { "JPEG slice does not start with SOI marker: ${slice.sourceFile}" }
            remaining -= 2L

            var written = 0L
            out.write(soi0)
            out.write(soi1)
            out.write(xmpSegment)
            written += 2L + xmpSegment.size

            while (remaining > 0L) {
                val first = raf.read()
                require(first >= 0) { "Unexpected EOF while parsing JPEG stream." }
                remaining -= 1L
                if (first != 0xFF) {
                    out.write(first)
                    written += 1L
                    written += copyRange(raf, raf.filePointer, remaining, out)
                    return written
                }

                if (remaining <= 0L) {
                    out.write(0xFF)
                    written += 1L
                    return written
                }

                var marker = raf.read()
                require(marker >= 0) { "Unexpected EOF while reading JPEG marker." }
                remaining -= 1L
                while (marker == 0xFF && remaining > 0L) {
                    marker = raf.read()
                    require(marker >= 0) { "Unexpected EOF while reading JPEG marker fill bytes." }
                    remaining -= 1L
                }
                if (marker == 0x00) {
                    out.write(0xFF)
                    out.write(0x00)
                    written += 2L
                    continue
                }

                if (marker == 0xD9) {
                    out.write(0xFF)
                    out.write(0xD9)
                    written += 2L
                    return written
                }

                if (marker in 0xD0..0xD7 || marker == 0x01) {
                    out.write(0xFF)
                    out.write(marker)
                    written += 2L
                    continue
                }

                require(remaining >= 2L) { "Invalid JPEG segment length header: ${slice.sourceFile}" }
                val lenHi = raf.read()
                val lenLo = raf.read()
                require(lenHi >= 0 && lenLo >= 0) { "Unexpected EOF while reading JPEG segment length." }
                remaining -= 2L
                val segmentLength = (lenHi shl 8) or lenLo
                require(segmentLength >= 2) { "Invalid JPEG segment length: $segmentLength" }
                val payloadLength = segmentLength - 2
                require(payloadLength.toLong() <= remaining) { "JPEG segment exceeds remaining slice bytes." }
                val payload = ByteArray(payloadLength)
                var payloadOffset = 0
                while (payloadOffset < payloadLength) {
                    val read = raf.read(payload, payloadOffset, payloadLength - payloadOffset)
                    require(read > 0) { "Unexpected EOF while reading JPEG segment payload." }
                    payloadOffset += read
                }
                remaining -= payloadLength.toLong()

                if (marker == 0xDA) {
                    out.write(0xFF)
                    out.write(marker)
                    out.write(lenHi)
                    out.write(lenLo)
                    out.write(payload)
                    written += (4 + payloadLength).toLong()
                    if (remaining > 0L) {
                        written += copyRange(raf, raf.filePointer, remaining, out)
                    }
                    return written
                }

                val drop = (marker == 0xE1 || marker == 0xFE) && containsMotionKeywords(payload)
                if (!drop) {
                    out.write(0xFF)
                    out.write(marker)
                    out.write(lenHi)
                    out.write(lenLo)
                    out.write(payload)
                    written += (4 + payloadLength).toLong()
                }
            }
            return written
        }
    }

    /**
     * 以 MP4 兼容容器形式写出视频切片。
     *
     * 若输入为 ISO BMFF/QuickTime，则通过重写 `ftyp` 主品牌与兼容品牌输出 MP4 兼容流；
     * 若输入不是 ISO BMFF，则抛出异常，避免输出伪 MotionPhoto。
     *
     * @param slice 输入视频切片。
     * @param out 输出流。
     */
    fun copySliceAsMp4Compatible(slice: MediaSlice, out: OutputStream) {
        copySliceWithTargetBrand(
            slice = slice,
            out = out,
            targetMajorBrand = BRAND_ISOM,
            requiredCompatibleBrands = listOf(BRAND_ISOM, BRAND_MP42)
        )
    }

    /**
     * 以 QuickTime 兼容容器形式写出视频切片。
     *
     * 若输入为 ISO BMFF，则重写 `ftyp` 主品牌为 QuickTime；
     * 若输入不是 ISO BMFF，则抛出异常。
     *
     * @param slice 输入视频切片。
     * @param out 输出流。
     */
    fun copySliceAsQuickTimeCompatible(slice: MediaSlice, out: OutputStream) {
        copySliceWithTargetBrand(
            slice = slice,
            out = out,
            targetMajorBrand = BRAND_QT,
            requiredCompatibleBrands = listOf(BRAND_QT)
        )
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
     * 判断给定偏移处是否存在 ISO BMFF `ftyp` 头。
     *
     * @param file 目标文件。
     * @param offset 待校验偏移（指向 box 起始位置）。
     */
    fun hasIsoBmffFtypAt(file: File, offset: Long): Boolean {
        if (offset < 0L) return false
        RandomAccessFile(file, "r").use { raf ->
            if (offset + 8 > raf.length()) return false
            raf.seek(offset + 4)
            val boxType = ByteArray(4)
            val read = raf.read(boxType, 0, 4)
            if (read != 4) return false
            return boxType[0] == 'f'.code.toByte()
                && boxType[1] == 't'.code.toByte()
                && boxType[2] == 'y'.code.toByte()
                && boxType[3] == 'p'.code.toByte()
        }
    }

    /**
     * 判断切片是否可视为 ISO BMFF 容器（允许 `ftyp` 前存在合法前置 box）。
     *
     * @param slice 待判断的视频切片。
     */
    internal fun isIsoBmffSlice(slice: MediaSlice): Boolean {
        validateSliceBounds(slice)
        return RandomAccessFile(slice.sourceFile, "r").use { raf ->
            runCatching { locateFtypBox(raf, slice.offset, slice.length) }.isSuccess
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
     * 将切片按目标品牌重写 `ftyp` 后复制到输出流。
     *
     * @param slice 输入视频切片。
     * @param out 输出流。
     * @param targetMajorBrand 目标 `major brand`（4 字节）。
     * @param requiredCompatibleBrands 需要写入前几个兼容品牌槽位的品牌列表。
     */
    private fun copySliceWithTargetBrand(
        slice: MediaSlice,
        out: OutputStream,
        targetMajorBrand: ByteArray,
        requiredCompatibleBrands: List<ByteArray>
    ) {
        validateSliceBounds(slice)
        RandomAccessFile(slice.sourceFile, "r").use { raf ->
            val ftypPosition = locateFtypBox(raf, slice.offset, slice.length)
            val ftypOffset = slice.offset + ftypPosition.relativeOffset
            val ftypSize = ftypPosition.size
            require(ftypSize >= MIN_FTYP_BOX_SIZE) { "Invalid ftyp size in video slice: $ftypSize" }
            require(ftypSize <= slice.length) { "ftyp size exceeds slice length: ${slice.sourceFile}" }
            require(ftypSize <= Int.MAX_VALUE.toLong()) { "ftyp box is too large to normalize: $ftypSize" }

            if (ftypPosition.relativeOffset > 0L) {
                copyRange(raf, slice.offset, ftypPosition.relativeOffset, out)
            }

            val ftypBox = ByteArray(ftypSize.toInt())
            raf.seek(ftypOffset)
            var readOffset = 0
            while (readOffset < ftypBox.size) {
                val read = raf.read(ftypBox, readOffset, ftypBox.size - readOffset)
                require(read > 0) { "Unexpected EOF while reading ftyp box: ${slice.sourceFile}" }
                readOffset += read
            }
            patchFtypBrands(ftypBox, targetMajorBrand, requiredCompatibleBrands)
            out.write(ftypBox)

            val remainingOffset = ftypOffset + ftypSize
            val remainingLength = slice.offset + slice.length - remainingOffset
            if (remainingLength > 0L) {
                copyRange(raf, remainingOffset, remainingLength, out)
            }
        }
    }

    /**
     * `ftyp` box 在切片中的位置信息。
     *
     * @property relativeOffset 相对于切片起点的偏移。
     * @property size `ftyp` box 大小（字节）。
     */
    private data class FtypPosition(
        val relativeOffset: Long,
        val size: Long
    )

    /**
     * 在切片头部扫描并定位 `ftyp` box。
     *
     * 兼容前置 box（例如 `free`）场景，不要求 `ftyp` 必须位于偏移 0。
     *
     * @param raf 源文件随机读取对象。
     * @param sliceOffset 切片起始偏移。
     * @param sliceLength 切片总长度。
     */
    private fun locateFtypBox(raf: RandomAccessFile, sliceOffset: Long, sliceLength: Long): FtypPosition {
        val searchLimit = minOf(sliceLength, MAX_FTYP_SEARCH_BYTES)
        var cursor = 0L
        val header = ByteArray(8)
        while (cursor + 8 <= searchLimit) {
            raf.seek(sliceOffset + cursor)
            val readHeader = raf.read(header)
            if (readHeader != header.size) break

            val isFtyp = header[4] == FTYP_TYPE[0]
                && header[5] == FTYP_TYPE[1]
                && header[6] == FTYP_TYPE[2]
                && header[7] == FTYP_TYPE[3]

            var boxSize = readUInt32(header, 0)
            if (boxSize == 1L) {
                if (cursor + 16 > sliceLength) break
                val largeSize = ByteArray(8)
                val readLarge = raf.read(largeSize)
                if (readLarge != largeSize.size) break
                boxSize = readUInt64(largeSize, 0)
            } else if (boxSize == 0L) {
                boxSize = sliceLength - cursor
            }

            if (boxSize < 8L) break
            if (cursor + boxSize > sliceLength) break
            if (isFtyp) return FtypPosition(relativeOffset = cursor, size = boxSize)
            cursor += boxSize
        }
        throw IllegalArgumentException(
            "Cannot locate ISO BMFF ftyp box near stream head for cross-vendor transcode: $sliceOffset/$sliceLength"
        )
    }

    /**
     * 重写 `ftyp` box 的主品牌与兼容品牌槽位。
     *
     * @param ftypBox 完整 `ftyp` box 字节。
     * @param targetMajorBrand 目标 `major brand`（4 字节）。
     * @param requiredCompatibleBrands 需要写入的兼容品牌列表。
     */
    private fun patchFtypBrands(
        ftypBox: ByteArray,
        targetMajorBrand: ByteArray,
        requiredCompatibleBrands: List<ByteArray>
    ) {
        require(targetMajorBrand.size == 4) { "major brand must be 4 bytes." }
        require(ftypBox.size >= MIN_FTYP_BOX_SIZE.toInt()) { "Invalid ftyp box length: ${ftypBox.size}" }
        System.arraycopy(targetMajorBrand, 0, ftypBox, 8, 4)
        val compatibleCount = (ftypBox.size - 16) / 4
        requiredCompatibleBrands.forEachIndexed { index, brand ->
            if (index >= compatibleCount) return@forEachIndexed
            require(brand.size == 4) { "compatible brand must be 4 bytes." }
            System.arraycopy(brand, 0, ftypBox, 16 + index * 4, 4)
        }
    }

    /**
     * 读取 32 位无符号大端整数。
     *
     * @param bytes 输入字节数组。
     * @param offset 读取起始偏移。
     */
    private fun readUInt32(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }

    /**
     * 读取 64 位无符号大端整数（限制为 `Long` 可表示范围）。
     *
     * @param bytes 输入字节数组。
     * @param offset 读取起始偏移。
     */
    private fun readUInt64(bytes: ByteArray, offset: Int): Long {
        require((bytes[offset].toInt() and 0x80) == 0) { "Unsigned 64-bit value exceeds Long range." }
        return ((bytes[offset].toLong() and 0xFFL) shl 56) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 48) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 40) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 32) or
            ((bytes[offset + 4].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 5].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 6].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 7].toLong() and 0xFFL)
    }

    /**
     * 复制源文件指定范围到输出流。
     *
     * @param raf 源文件随机读取对象。
     * @param offset 复制起始偏移。
     * @param length 复制长度。
     * @param out 输出流。
     */
    private fun copyRange(raf: RandomAccessFile, offset: Long, length: Long, out: OutputStream): Long {
        raf.seek(offset)
        var remaining = length
        var copied = 0L
        val buffer = ByteArray(IO_BUFFER_SIZE)
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = raf.read(buffer, 0, toRead)
            require(read > 0) { "Unexpected EOF while copying media range." }
            out.write(buffer, 0, read)
            remaining -= read
            copied += read.toLong()
        }
        return copied
    }

    /**
     * 构造 JPEG APP1 XMP 段字节。
     *
     * @param xmpXml XMP 文本。
     */
    private fun buildXmpApp1Segment(xmpXml: String): ByteArray {
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
        return segment
    }

    /**
     * 判断 JPEG 段载荷中是否含 MotionPhoto 相关关键词。
     *
     * @param payload 段载荷字节。
     */
    private fun containsMotionKeywords(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val text = String(payload, StandardCharsets.ISO_8859_1)
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
     * 只计数字节数的输出流。
     */
    private class CountingOutputStream : OutputStream() {
        /** 已写入字节数。 */
        var count: Long = 0L
            private set

        /**
         * 统计单字节写入。
         *
         * @param b 写入字节。
         */
        override fun write(b: Int) {
            count += 1L
        }

        /**
         * 统计数组写入。
         *
         * @param b 字节数组。
         * @param off 起始偏移。
         * @param len 写入长度。
         */
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > 0) count += len.toLong()
        }
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
