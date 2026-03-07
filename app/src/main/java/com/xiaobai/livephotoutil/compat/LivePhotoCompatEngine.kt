package com.xiaobai.livephotoutil.compat

import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * LivePhoto 协议探测引擎，按优先级依次执行适配器。
 *
 * @property adapters 参与探测的适配器列表。
 */
class LivePhotoCompatEngine(private val adapters: List<LivePhotoAdapter>) {
    /**
     * 从候选文件中探测 LivePhoto 资产。
     *
     * @param candidates 待探测候选文件。
     * @return 探测成功返回资产，否则返回 `null`。
     */
    fun detect(candidates: List<File>): LivePhotoAsset? {
        try {
            validateInput(candidates)
            val input = ProbeInput(candidates)
            for (adapter in adapters) {
                val result = adapter.probe(input)
                if (result != null) return result
            }
            return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Exception) {
            throw LivePhotoErrorMapper.map(throwable, LivePhotoErrorCode.INTERNAL_ERROR)
        }
    }

    /**
     * 与 [detect] 类似，但在未识别到协议时抛出异常。
     *
     * @param candidates 待探测候选文件。
     */
    fun detectOrThrow(candidates: List<File>): LivePhotoAsset {
        val detected = detect(candidates)
        if (detected != null) return detected
        throw LivePhotoSdkException(
            code = LivePhotoErrorCode.DETECTION_FAILED,
            message = "Cannot detect supported LivePhoto format from input files."
        )
    }

    /**
     * 校验输入必须为非空且全部存在的普通文件。
     *
     * @param candidates 待校验候选文件。
     */
    private fun validateInput(candidates: List<File>) {
        require(candidates.isNotEmpty()) { "candidates cannot be empty" }
        candidates.forEach {
            require(it.exists() && it.isFile) { "Invalid file: $it" }
        }
    }

    companion object {
        /** 默认引擎实例（惰性初始化后复用）。 */
        private val DEFAULT_ENGINE: LivePhotoCompatEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val all = mutableListOf<LivePhotoAdapter>()
            all += ApplePairAdapter()
            VendorProfiles.androidMotionProfiles().forEach { all += VendorMotionPhotoAdapter(it) }
            all += GenericPairAdapter()
            LivePhotoCompatEngine(all.sortedByDescending { it.priority })
        }

        /** 创建内置默认引擎（Apple + 各 Android 厂商 + 通用兜底）。 */
        fun defaultEngine(): LivePhotoCompatEngine {
            return DEFAULT_ENGINE
        }
    }
}
