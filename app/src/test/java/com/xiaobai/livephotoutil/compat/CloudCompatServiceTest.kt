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
    fun restoreForDevice_fallsBackToCanonicalTranscodeWhenRawReplayCorrupted() {
        val root = createTempDir("cloud-raw-fallback")
        try {
            val canonical = createAppleCanonical(root, "IMG_0201")
            mutateManifest(canonical.manifestFile) { props ->
                props["raw.0.sha256"] = "deadbeef"
            }

            val service = CloudCompatService()
            val result = service.restoreForDevice(
                canonicalDir = canonical.dir,
                targetVendor = DeviceVendor.APPLE,
                outputDir = File(root, "restore"),
                preferRawReplay = true
            )

            assertEquals(ContainerMode.APPLE_PAIR, result.mode)
            assertTrue(result.outputFiles.any { it.name.endsWith(".jpg", ignoreCase = true) })
            assertTrue(result.outputFiles.any { it.name.endsWith(".mov", ignoreCase = true) || it.name.endsWith(".mp4", ignoreCase = true) })
            assertTrue(result.outputFiles.any { it.name.endsWith(".livephoto.properties", ignoreCase = true) })
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun restoreForDevice_throwsWhenCanonicalPayloadChecksumMismatch() {
        val root = createTempDir("cloud-canonical-checksum")
        try {
            val canonical = createAppleCanonical(root, "IMG_0601")
            val imagePayload = File(canonical.dir, "image.bin")
            imagePayload.writeBytes(imagePayload.readBytes() + byteArrayOf(0x7F))

            try {
                CloudCompatService().restoreForDevice(
                    canonicalDir = canonical.dir,
                    targetVendor = DeviceVendor.HUAWEI,
                    outputDir = File(root, "restore"),
                    preferRawReplay = false
                )
                fail("Expected restoreForDevice to reject corrupted canonical payload.")
            } catch (expected: IllegalArgumentException) {
                val message = expected.message?.lowercase() ?: ""
                assertTrue(message.contains("mismatch"))
            }
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun normalizeForCloud_writesManifestSignatureWhenKeyConfigured() {
        val root = createTempDir("cloud-signature-write")
        try {
            val key = "livephoto-test-key".toByteArray(Charsets.UTF_8)
            val canonical = createAppleCanonical(root, "IMG_0701", key)
            val props = Properties()
            FileInputStream(canonical.manifestFile).use { props.load(it) }
            assertTrue(props.getProperty("manifestSignatureAlgo") == "HMAC-SHA256")
            assertTrue((props.getProperty("manifestSignature") ?: "").isNotBlank())
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun restoreForDevice_rejectsTamperedSignedManifest() {
        val root = createTempDir("cloud-signature-tamper")
        try {
            val key = "livephoto-test-key".toByteArray(Charsets.UTF_8)
            val canonical = createAppleCanonical(root, "IMG_0801", key)
            mutateManifest(canonical.manifestFile) { props ->
                props["contentId"] = "tampered_content"
            }
            try {
                CloudCompatService(manifestHmacKey = key).restoreForDevice(
                    canonicalDir = canonical.dir,
                    targetVendor = DeviceVendor.APPLE,
                    outputDir = File(root, "restore"),
                    preferRawReplay = false
                )
                fail("Expected restoreForDevice to reject tampered signed manifest.")
            } catch (expected: LivePhotoSdkException) {
                assertEquals(LivePhotoErrorCode.INTEGRITY_CHECK_FAILED, expected.code)
                assertTrue(expected.message?.contains("signature", ignoreCase = true) == true)
            }
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun restoreForDevice_rejectsSignedManifestWhenVerificationKeyMissing() {
        val root = createTempDir("cloud-signature-missing-key")
        try {
            val key = "livephoto-test-key".toByteArray(Charsets.UTF_8)
            val canonical = createAppleCanonical(root, "IMG_0901", key)
            try {
                CloudCompatService().restoreForDevice(
                    canonicalDir = canonical.dir,
                    targetVendor = DeviceVendor.APPLE,
                    outputDir = File(root, "restore"),
                    preferRawReplay = false
                )
                fail("Expected restoreForDevice to reject signed manifest without verification key.")
            } catch (expected: LivePhotoSdkException) {
                assertEquals(LivePhotoErrorCode.INTEGRITY_CHECK_FAILED, expected.code)
            }
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun normalizeForCloud_copiesManifestHmacKeyDefensively() {
        val root = createTempDir("cloud-signature-defensive-copy")
        try {
            val key = "livephoto-test-key".toByteArray(Charsets.UTF_8)
            val keySnapshot = key.copyOf()
            val service = CloudCompatService(manifestHmacKey = key)
            key.fill(0)

            val canonical = createAppleCanonicalWithService(root, "IMG_0951", service)
            val restored = CloudCompatService(manifestHmacKey = keySnapshot).restoreForDevice(
                canonicalDir = canonical.dir,
                targetVendor = DeviceVendor.APPLE,
                outputDir = File(root, "restore"),
                preferRawReplay = false
            )

            assertEquals(ContainerMode.APPLE_PAIR, restored.mode)
            assertTrue(restored.outputFiles.any { it.name.endsWith(".mov", ignoreCase = true) })
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun restoreForDevice_rejectsDowngradeWhenSignatureRemovedAndKeysConfigured() {
        val root = createTempDir("cloud-signature-downgrade")
        try {
            val key = "livephoto-test-key".toByteArray(Charsets.UTF_8)
            val canonical = createAppleCanonical(root, "IMG_0961", key)
            mutateManifest(canonical.manifestFile) { props ->
                props.remove("manifestSignatureAlgo")
                props.remove("manifestSignatureKeyId")
                props.remove("manifestSignature")
            }
            try {
                CloudCompatService(manifestHmacKey = key).restoreForDevice(
                    canonicalDir = canonical.dir,
                    targetVendor = DeviceVendor.APPLE,
                    outputDir = File(root, "restore"),
                    preferRawReplay = false
                )
                fail("Expected restoreForDevice to reject manifest without required signature.")
            } catch (expected: LivePhotoSdkException) {
                assertEquals(LivePhotoErrorCode.INTEGRITY_CHECK_FAILED, expected.code)
                assertTrue(expected.message?.contains("required", ignoreCase = true) == true)
            }
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun restoreForDevice_supportsKeyRingVerificationByKeyId() {
        val root = createTempDir("cloud-signature-keyring")
        try {
            val oldKey = "livephoto-old-key".toByteArray(Charsets.UTF_8)
            val newKey = "livephoto-new-key".toByteArray(Charsets.UTF_8)
            val signer = CloudCompatService(
                manifestHmacKey = oldKey,
                manifestHmacKeyId = "k-old"
            )
            val canonical = createAppleCanonicalWithService(root, "IMG_0971", signer)

            val restored = CloudCompatService(
                manifestHmacKeyRing = mapOf(
                    "k-old" to oldKey,
                    "k-new" to newKey
                )
            ).restoreForDevice(
                canonicalDir = canonical.dir,
                targetVendor = DeviceVendor.APPLE,
                outputDir = File(root, "restore"),
                preferRawReplay = false
            )

            assertEquals(ContainerMode.APPLE_PAIR, restored.mode)
            assertTrue(restored.outputFiles.any { it.name.endsWith(".mov", ignoreCase = true) })
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun restoreForDevice_allowsUnsignedManifestWhenPolicyOptional() {
        val root = createTempDir("cloud-signature-optional")
        try {
            val canonical = createAppleCanonical(root, "IMG_0981")
            val restored = CloudCompatService(
                manifestHmacKey = "livephoto-key".toByteArray(Charsets.UTF_8),
                signaturePolicy = ManifestSignaturePolicy.OPTIONAL
            ).restoreForDevice(
                canonicalDir = canonical.dir,
                targetVendor = DeviceVendor.APPLE,
                outputDir = File(root, "restore")
            )
            assertTrue(restored.outputFiles.isNotEmpty())
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
    private fun createAppleCanonical(
        root: File,
        contentId: String,
        manifestHmacKey: ByteArray? = null
    ): CanonicalPackage {
        val inputDir = File(root, "input-$contentId").apply { mkdirs() }
        val image = File(inputDir, "$contentId.JPG").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val video = File(inputDir, "$contentId.MOV").apply { writeBytes(isoBmffVideo("qt  ")) }
        val asset = LivePhotoAsset(
            vendor = DeviceVendor.APPLE,
            protocol = LivePhotoProtocol.APPLE_PAIR,
            image = MediaSlice(image, 0L, image.length(), "image/jpeg"),
            video = MediaSlice(video, 0L, video.length(), "video/quicktime"),
            contentId = contentId
        )
        return CloudCompatService(manifestHmacKey = manifestHmacKey)
            .normalizeForCloud(asset, File(root, "cloud-$contentId"))
    }

    /**
     * 使用指定服务实例创建 canonical 包。
     *
     * @param root 测试根目录。
     * @param contentId 内容标识。
     * @param service 已初始化的云端服务实例。
     */
    private fun createAppleCanonicalWithService(
        root: File,
        contentId: String,
        service: CloudCompatService
    ): CanonicalPackage {
        val inputDir = File(root, "input-$contentId").apply { mkdirs() }
        val image = File(inputDir, "$contentId.JPG").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val video = File(inputDir, "$contentId.MOV").apply { writeBytes(isoBmffVideo("qt  ")) }
        val asset = LivePhotoAsset(
            vendor = DeviceVendor.APPLE,
            protocol = LivePhotoProtocol.APPLE_PAIR,
            image = MediaSlice(image, 0L, image.length(), "image/jpeg"),
            video = MediaSlice(video, 0L, video.length(), "video/quicktime"),
            contentId = contentId
        )
        return service.normalizeForCloud(asset, File(root, "cloud-$contentId"))
    }

    /**
     * 生成最小可用的 ISO BMFF 视频样本字节。
     *
     * @param majorBrand `ftyp` 主品牌（4 字符）。
     */
    private fun isoBmffVideo(majorBrand: String): ByteArray {
        require(majorBrand.length == 4) { "majorBrand must be 4 chars." }
        val bytes = ByteArray(32)
        bytes[0] = 0x00
        bytes[1] = 0x00
        bytes[2] = 0x00
        bytes[3] = 0x18
        bytes[4] = 'f'.code.toByte()
        bytes[5] = 't'.code.toByte()
        bytes[6] = 'y'.code.toByte()
        bytes[7] = 'p'.code.toByte()
        bytes[8] = majorBrand[0].code.toByte()
        bytes[9] = majorBrand[1].code.toByte()
        bytes[10] = majorBrand[2].code.toByte()
        bytes[11] = majorBrand[3].code.toByte()
        bytes[12] = 0x00
        bytes[13] = 0x00
        bytes[14] = 0x00
        bytes[15] = 0x00
        bytes[16] = 'i'.code.toByte()
        bytes[17] = 's'.code.toByte()
        bytes[18] = 'o'.code.toByte()
        bytes[19] = 'm'.code.toByte()
        bytes[20] = 'm'.code.toByte()
        bytes[21] = 'p'.code.toByte()
        bytes[22] = '4'.code.toByte()
        bytes[23] = '2'.code.toByte()
        bytes[24] = 0x00
        bytes[25] = 0x00
        bytes[26] = 0x00
        bytes[27] = 0x08
        bytes[28] = 'm'.code.toByte()
        bytes[29] = 'd'.code.toByte()
        bytes[30] = 'a'.code.toByte()
        bytes[31] = 't'.code.toByte()
        return bytes
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
