# LivePhotoCompat SDK

跨厂商 LivePhoto 适配层 Android SDK（Kotlin）。

目标是把不同厂商动态照片统一抽象为标准资产，再按目标厂商输出可识别格式，支持云端 canonical 中间包跨设备恢复。

## 1. 能力概览

- 协议识别：Apple 双文件、Android MotionPhoto、Generic Pair 兜底。
- 格式互转：统一资产 -> Apple / Google / Huawei / vivo / OPPO / Xiaomi。
- 云端中间包：`normalizeForCloud` 与 `restoreForDevice`。
- 原样回放：同厂商优先 `RAW_REPLAY`，失败自动降级 canonical 转码。
- 安全保护：manifest 完整性校验（size + sha256）、路径穿越防护、目录级并发锁。
- 可选签名：支持 manifest HMAC-SHA256 签名与验签，支持 `keyId` 轮换与强制签名策略。
- API 稳定性：内置二进制兼容门禁 `checkBinaryCompatibility`。

## 2. 支持矩阵

### 输入协议

- `APPLE_PAIR`
- `MOTION_PHOTO`
- `GENERIC_PAIR`

### 目标厂商

- `APPLE`
- `GOOGLE`
- `HUAWEI`
- `VIVO`
- `OPPO`
- `XIAOMI`

## 3. 关键约束

- 默认情况下，跨厂商转码要求输入视频切片为 ISO BMFF（MP4/QuickTime）容器。
- 若输入不是 ISO BMFF，可通过 `VideoCompatibilityNormalizer` 接入业务侧转码能力后再继续封装。
- 默认 normalizer 会尝试修复最多 8MB 头部垃圾字节导致的 `ftyp` 偏移问题。
- 目标 Apple 输出固定为 `.mov` + sidecar 属性文件。
- 目标 Android MotionPhoto 输出为单 JPEG，尾部视频规范化为 MP4 兼容容器。
- Apple 的“系统相册级导入兼容”依赖业务侧额外媒体元数据流程；本 SDK 保证的是 App 接入链路可识别与互转。

## 4. 快速接入

推荐只使用协程入口 `LivePhotoCoroutineSdk`。

```kotlin
val sdk = LivePhotoCoroutineSdk()

val canonical = sdk.detectAndNormalizeForCloud(
    candidates = listOf(inputImage, inputVideo),
    cloudDir = cloudDir
)

val result = sdk.restoreForDevice(
    canonicalDir = canonical.dir,
    targetVendor = DeviceVendor.XIAOMI,
    outputDir = outDir,
    preferRawReplay = true
)
```

若你需要处理 WebM/3GP 等非 ISO BMFF 输入，可在 `LivePhotoTranscoder` 注入 `VideoCompatibilityNormalizer`：

```kotlin
val transcoder = LivePhotoTranscoder(
    videoNormalizer = object : VideoCompatibilityNormalizer {
        override fun normalize(
            source: MediaSlice,
            targetContainer: VideoContainerTarget,
            workingDir: File
        ): MediaSlice? {
            // 在这里接入 Media3/FFmpeg，把 source 转成目标容器后返回切片
            return null
        }
    }
)
```

## 5. 错误语义

- 统一异常类型：`LivePhotoSdkException`（继承 `IllegalArgumentException`）。
- 结构化错误码：`LivePhotoErrorCode`（如 `DETECTION_FAILED`、`INTEGRITY_CHECK_FAILED`、`UNSUPPORTED_TRANSCODE_TARGET`）。
- 协程取消语义：`CancellationException` 直接透传，不会被包装。

可选安全增强（manifest 签名）：

```kotlin
val cloudService = CloudCompatService(
    manifestHmacKey = "your-secret-key".toByteArray(Charsets.UTF_8),
    manifestHmacKeyId = "k-2026-q1",
    manifestHmacKeyRing = mapOf(
        "k-2025-q4" to "old-secret".toByteArray(Charsets.UTF_8),
        "k-2026-q1" to "your-secret-key".toByteArray(Charsets.UTF_8)
    ),
    signaturePolicy = ManifestSignaturePolicy.REQUIRE_WHEN_KEY_CONFIGURED
)
```

默认策略说明：

- 当配置了签名/验签密钥时，`signaturePolicy=REQUIRE_WHEN_KEY_CONFIGURED` 会拒绝无签名 manifest，防止“删签名降级”。

## 6. 构建与发布

发布坐标来自 `gradle.properties`：

- `sdk.group`
- `sdk.artifact`
- `sdk.version`

常用命令：

```bash
./gradlew test
./gradlew lintDebug
./gradlew check
./gradlew :app:checkBinaryCompatibility
./gradlew :app:publishReleasePublicationToMavenLocal
```

如 API 变更是有意且已按 SemVer 调整版本，执行：

```bash
./gradlew :app:updateApiBaseline
```

## 7. 生产文档索引

- [docs/SDK_PRODUCTION_GUIDE.md](docs/SDK_PRODUCTION_GUIDE.md)
- [docs/SDK_RELEASE_POLICY.md](docs/SDK_RELEASE_POLICY.md)
- [docs/SDK_ARCHITECTURE.md](docs/SDK_ARCHITECTURE.md)
- [docs/SDK_API_REFERENCE.md](docs/SDK_API_REFERENCE.md)
- [docs/SDK_PROD_CHECKLIST.md](docs/SDK_PROD_CHECKLIST.md)
- [docs/SDK_UML.puml](docs/SDK_UML.puml)
