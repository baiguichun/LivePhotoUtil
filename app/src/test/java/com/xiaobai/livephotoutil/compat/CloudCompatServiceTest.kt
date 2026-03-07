package com.xiaobai.livephotoutil.compat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.Properties

/** [CloudCompatService] 云端归档与恢复测试。 */
class CloudCompatServiceTest {
    @Test
    fun restoreOriginal_replaysRawFilesWithOriginalBytesAndNames() {
        val root = createTempDir("cloud-raw-replay")
        try {
            val inputDir = File(root, "input").apply { mkdirs() }
            val image = File(inputDir, "IMG_0001.JPG").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
            val video = File(inputDir, "IMG_0001.MOV").apply { writeBytes(byteArrayOf(9, 8, 7, 6, 5, 4)) }
            val asset = LivePhotoAsset(
                vendor = DeviceVendor.APPLE,
                protocol = LivePhotoProtocol.APPLE_PAIR,
                image = MediaSlice(image, 0L, image.length(), "image/jpeg"),
                video = MediaSlice(video, 0L, video.length(), "video/quicktime"),
                contentId = "IMG_0001"
            )

            val canonical = CloudCompatService().normalizeForCloud(asset, File(root, "cloud"))
            val restored = CloudCompatService().restoreOriginal(canonical.dir, File(root, "restore"))

            assertEquals(2, restored.size)
            val restoredImage = restored.first { it.name == "IMG_0001.JPG" }
            val restoredVideo = restored.first { it.name == "IMG_0001.MOV" }
            assertArrayEquals(image.readBytes(), restoredImage.readBytes())
            assertArrayEquals(video.readBytes(), restoredVideo.readBytes())
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun restoreForDevice_usesRawReplayWhenVendorMatches() {
        val root = createTempDir("cloud-raw-device")
        try {
            val inputDir = File(root, "input").apply { mkdirs() }
            val image = File(inputDir, "IMG_0101.JPG").apply { writeBytes(byteArrayOf(11, 12, 13)) }
            val video = File(inputDir, "IMG_0101.MOV").apply { writeBytes(byteArrayOf(21, 22, 23, 24)) }
            val asset = LivePhotoAsset(
                vendor = DeviceVendor.APPLE,
                protocol = LivePhotoProtocol.APPLE_PAIR,
                image = MediaSlice(image, 0L, image.length(), "image/jpeg"),
                video = MediaSlice(video, 0L, video.length(), "video/quicktime"),
                contentId = "IMG_0101"
            )

            val service = CloudCompatService()
            val canonical = service.normalizeForCloud(asset, File(root, "cloud"))
            val result = service.restoreForDevice(
                canonicalDir = canonical.dir,
                targetVendor = DeviceVendor.APPLE,
                outputDir = File(root, "restore")
            )

            assertEquals(ContainerMode.RAW_REPLAY, result.mode)
            assertTrue(result.outputFiles.any { it.name == "IMG_0101.JPG" })
            assertTrue(result.outputFiles.any { it.name == "IMG_0101.MOV" })
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun normalizeForCloud_overwritesStaleCanonicalFiles() {
        val root = createTempDir("cloud-clean-stale")
        try {
            val inputDir = File(root, "input").apply { mkdirs() }
            val image = File(inputDir, "IMG_0301.JPG").apply { writeBytes(byteArrayOf(1, 3, 5, 7)) }
            val video = File(inputDir, "IMG_0301.MOV").apply { writeBytes(byteArrayOf(2, 4, 6, 8)) }
            val asset = LivePhotoAsset(
                vendor = DeviceVendor.APPLE,
                protocol = LivePhotoProtocol.APPLE_PAIR,
                image = MediaSlice(image, 0L, image.length(), "image/jpeg"),
                video = MediaSlice(video, 0L, video.length(), "video/quicktime"),
                contentId = "IMG_0301"
            )
            val cloudDir = File(root, "cloud").apply { mkdirs() }
            val staleRaw = File(cloudDir, "raw/legacy.bin").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(9, 9, 9))
            }
            File(cloudDir, "image.bin").writeBytes(byteArrayOf(0))
            File(cloudDir, "video.bin").writeBytes(byteArrayOf(0))
            File(cloudDir, "manifest.properties").writeText("old=true")

            val canonical = CloudCompatService().normalizeForCloud(asset, cloudDir)

            assertFalse(staleRaw.exists())
            assertEquals(image.readBytes().size.toLong(), canonical.imageFile.length())
            assertEquals(video.readBytes().size.toLong(), canonical.videoFile.length())
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun restoreOriginal_rejectsPathTraversalFileNameFromManifest() {
        val root = createTempDir("cloud-raw-traversal")
        try {
            val canonical = createAppleCanonical(root, "IMG_0401")
            mutateManifest(canonical.manifestFile) { props ->
                props["raw.0.originalName"] = "../escape.bin"
            }

            try {
                CloudCompatService().restoreOriginal(canonical.dir, File(root, "restore"))
                fail("Expected restoreOriginal to reject path traversal filename.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("cannot contain '/'") == true)
            }
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun restoreOriginal_rejectsDuplicateOutputNames() {
        val root = createTempDir("cloud-raw-duplicate")
        try {
            val canonical = createAppleCanonical(root, "IMG_0501")
            mutateManifest(canonical.manifestFile) { props ->
                val original0 = props.getProperty("raw.0.originalName")
                props["raw.1.originalName"] = original0
            }

            try {
                CloudCompatService().restoreOriginal(canonical.dir, File(root, "restore"))
                fail("Expected restoreOriginal to reject duplicate output names.")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message?.contains("Duplicate raw output filename") == true)
            }
        } finally {
            deleteRecursively(root)
        }
    }

    /**
     * 创建一个 Apple 双文件输入并归档成 canonical 包。
     *
     * @param root 测试根目录。
     * @param contentId 内容标识。
     */
    private fun createAppleCanonical(root: File, contentId: String): CanonicalPackage {
        val inputDir = File(root, "input-$contentId").apply { mkdirs() }
        val image = File(inputDir, "$contentId.JPG").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val video = File(inputDir, "$contentId.MOV").apply { writeBytes(byteArrayOf(5, 6, 7, 8, 9)) }
        val asset = LivePhotoAsset(
            vendor = DeviceVendor.APPLE,
            protocol = LivePhotoProtocol.APPLE_PAIR,
            image = MediaSlice(image, 0L, image.length(), "image/jpeg"),
            video = MediaSlice(video, 0L, video.length(), "video/quicktime"),
            contentId = contentId
        )
        return CloudCompatService().normalizeForCloud(asset, File(root, "cloud-$contentId"))
    }

    /**
     * 修改 canonical manifest 内容并原子覆盖。
     *
     * @param manifest manifest 文件路径。
     * @param mutator 对属性集合的修改动作。
     */
    private fun mutateManifest(manifest: File, mutator: (Properties) -> Unit) {
        val props = Properties()
        FileInputStream(manifest).use { props.load(it) }
        mutator(props)
        MediaIO.writeToFileAtomic(manifest) { out ->
            props.store(out, "mutated for test")
        }
    }

    /**
     * 创建测试临时目录。
     *
     * @param prefix 目录名前缀。
     */
    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    /**
     * 递归删除目录及其内容。
     *
     * @param path 待删除目录。
     */
    private fun deleteRecursively(path: File) {
        if (!path.exists()) return
        path.walkBottomUp().forEach { it.delete() }
    }
}
