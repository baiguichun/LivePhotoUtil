package com.xiaobai.livephotoutil.compat

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 对外协程化 SDK 入口。
 *
 * 该入口在内部统一切换到 IO 线程池执行文件操作，调用方只需在协程中调用即可。
 *
 * @property engine LivePhoto 识别引擎。
 * @property transcoder 格式转码器。
 * @property cloudService 云端归档与恢复服务。
 * @property ioDispatcher 文件操作使用的调度器。
 */
class LivePhotoCoroutineSdk(
    private val engine: LivePhotoCompatEngine = LivePhotoCompatEngine.defaultEngine(),
    private val transcoder: LivePhotoTranscoder = LivePhotoTranscoder(),
    private val cloudService: CloudCompatService = CloudCompatService(transcoder),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * 识别输入候选文件中的 LivePhoto 资产。
     *
     * @param candidates 输入候选文件列表。
     * @return 识别到的资产，未识别返回 `null`。
     */
    suspend fun detect(candidates: List<File>): LivePhotoAsset? = withContext(ioDispatcher) {
        engine.detect(candidates)
    }

    /**
     * 识别输入候选文件中的 LivePhoto 资产，失败时抛出异常。
     *
     * @param candidates 输入候选文件列表。
     * @return 识别到的资产。
     */
    suspend fun detectOrThrow(candidates: List<File>): LivePhotoAsset = withContext(ioDispatcher) {
        engine.detectOrThrow(candidates)
    }

    /**
     * 将统一资产转为目标厂商格式。
     *
     * @param asset 输入统一资产。
     * @param targetVendor 目标厂商。
     * @param outputDir 输出目录。
     */
    suspend fun transcode(
        asset: LivePhotoAsset,
        targetVendor: DeviceVendor,
        outputDir: File
    ): ConversionResult = withContext(ioDispatcher) {
        transcoder.transcode(asset, targetVendor, outputDir)
    }

    /**
     * 一步完成“识别 + 转码”。
     *
     * @param candidates 输入候选文件列表。
     * @param targetVendor 目标厂商。
     * @param outputDir 输出目录。
     */
    suspend fun detectAndTranscode(
        candidates: List<File>,
        targetVendor: DeviceVendor,
        outputDir: File
    ): ConversionResult = withContext(ioDispatcher) {
        val asset = engine.detectOrThrow(candidates)
        transcoder.transcode(asset, targetVendor, outputDir)
    }

    /**
     * 将统一资产归档为云端中间格式。
     *
     * @param asset 输入统一资产。
     * @param cloudDir 归档目录。
     */
    suspend fun normalizeForCloud(asset: LivePhotoAsset, cloudDir: File): CanonicalPackage = withContext(ioDispatcher) {
        cloudService.normalizeForCloud(asset, cloudDir)
    }

    /**
     * 一步完成“识别 + 云端中间格式归档”。
     *
     * @param candidates 输入候选文件列表。
     * @param cloudDir 归档目录。
     */
    suspend fun detectAndNormalizeForCloud(candidates: List<File>, cloudDir: File): CanonicalPackage =
        withContext(ioDispatcher) {
            val asset = engine.detectOrThrow(candidates)
            cloudService.normalizeForCloud(asset, cloudDir)
        }

    /**
     * 从云端中间格式恢复为目标设备可识别格式。
     *
     * @param canonicalDir 云端中间包目录。
     * @param targetVendor 目标厂商。
     * @param outputDir 输出目录。
     * @param preferRawReplay 当源厂商与目标厂商一致时是否优先原样回放。
     */
    suspend fun restoreForDevice(
        canonicalDir: File,
        targetVendor: DeviceVendor,
        outputDir: File,
        preferRawReplay: Boolean = true
    ): ConversionResult = withContext(ioDispatcher) {
        cloudService.restoreForDevice(canonicalDir, targetVendor, outputDir, preferRawReplay)
    }

    /**
     * 从云端中间格式恢复原始文件（原样回放）。
     *
     * @param canonicalDir 云端中间包目录。
     * @param outputDir 输出目录。
     */
    suspend fun restoreOriginal(canonicalDir: File, outputDir: File): List<File> = withContext(ioDispatcher) {
        cloudService.restoreOriginal(canonicalDir, outputDir)
    }
}
