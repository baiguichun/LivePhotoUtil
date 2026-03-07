package com.xiaobai.livephotoutil.compat

/**
 * 厂商协议配置。
 *
 * @property vendor 厂商标识。
 * @property mode 厂商期望的输出容器形态。
 * @property detectMarkers 探测该厂商 MotionPhoto 时使用的特征字符串。
 * @property xmpAttributes 转码输出时注入的厂商 XMP 属性。
 * @property priority 探测优先级，越大越先匹配。
 */
data class VendorProfile(
    val vendor: DeviceVendor,
    val mode: ContainerMode,
    val detectMarkers: List<String>,
    val xmpAttributes: Map<String, String>,
    val priority: Int
)

/** 维护全部厂商 profile，并提供查询入口。 */
object VendorProfiles {
    /** 全量厂商 profile 映射。 */
    private val allProfiles: Map<DeviceVendor, VendorProfile> = listOf(
        VendorProfile(
            vendor = DeviceVendor.APPLE,
            mode = ContainerMode.APPLE_PAIR,
            detectMarkers = emptyList(),
            xmpAttributes = emptyMap(),
            priority = 500
        ),
        VendorProfile(
            vendor = DeviceVendor.GOOGLE,
            mode = ContainerMode.MOTION_PHOTO_JPEG,
            detectMarkers = listOf(
                "GCamera:MotionPhoto=\"1\"",
                "GCamera:MicroVideo=\"1\"",
                "GCamera:MicroVideoOffset"
            ),
            xmpAttributes = mapOf(
                "GCamera:MotionPhoto" to "1",
                "GCamera:MicroVideo" to "1"
            ),
            priority = 380
        ),
        VendorProfile(
            vendor = DeviceVendor.HUAWEI,
            mode = ContainerMode.MOTION_PHOTO_JPEG,
            detectMarkers = listOf(
                "HwCamera:MotionPhoto=\"1\"",
                "HUAWEI:MotionPhoto=\"1\"",
                "HuaweiMotionPhoto"
            ),
            xmpAttributes = mapOf(
                "HwCamera:MotionPhoto" to "1",
                "HUAWEI:MotionPhoto" to "1"
            ),
            priority = 420
        ),
        VendorProfile(
            vendor = DeviceVendor.VIVO,
            mode = ContainerMode.MOTION_PHOTO_JPEG,
            detectMarkers = listOf(
                "vivo:MotionPhoto=\"1\"",
                "VIVO:MotionPhoto=\"1\"",
                "vivoLivePhoto"
            ),
            xmpAttributes = mapOf(
                "vivo:MotionPhoto" to "1",
                "VIVO:MotionPhoto" to "1"
            ),
            priority = 415
        ),
        VendorProfile(
            vendor = DeviceVendor.OPPO,
            mode = ContainerMode.MOTION_PHOTO_JPEG,
            detectMarkers = listOf(
                "OPPO:MotionPhoto=\"1\"",
                "Oplus:MotionPhoto=\"1\"",
                "oppoMotionPhoto"
            ),
            xmpAttributes = mapOf(
                "OPPO:MotionPhoto" to "1",
                "Oplus:MotionPhoto" to "1"
            ),
            priority = 410
        ),
        VendorProfile(
            vendor = DeviceVendor.XIAOMI,
            mode = ContainerMode.MOTION_PHOTO_JPEG,
            detectMarkers = listOf(
                "MiCamera:MotionPhoto=\"1\"",
                "Xiaomi:MotionPhoto=\"1\"",
                "MiMotionPhoto"
            ),
            xmpAttributes = mapOf(
                "MiCamera:MotionPhoto" to "1",
                "Xiaomi:MotionPhoto" to "1"
            ),
            priority = 405
        )
    ).associateBy { it.vendor }

    /**
     * 获取指定厂商 profile。
     *
     * @param vendor 厂商标识。
     */
    fun profileOf(vendor: DeviceVendor): VendorProfile {
        return allProfiles[vendor] ?: error("Unsupported vendor profile: $vendor")
    }

    /** 返回所有 Android 阵营 MotionPhoto profile。 */
    fun androidMotionProfiles(): List<VendorProfile> {
        return listOf(
            profileOf(DeviceVendor.GOOGLE),
            profileOf(DeviceVendor.HUAWEI),
            profileOf(DeviceVendor.VIVO),
            profileOf(DeviceVendor.OPPO),
            profileOf(DeviceVendor.XIAOMI)
        )
    }
}
