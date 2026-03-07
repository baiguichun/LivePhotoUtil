# LivePhotoCompat SDK

跨厂商 LivePhoto 适配层 Android SDK（Kotlin）。

目标是把不同厂商动态照片统一抽象为标准资产，再按目标厂商输出可识别格式，支持云端 canonical 中间包跨设备恢复。

## 1. 能力概览

- 协议识别：Apple 双文件、Android MotionPhoto、Generic Pair 兜底。
- 格式互转：统一资产 -> Apple / Google / Huawei / vivo / OPPO / Xiaomi。
- 云端中间包：`normalizeForCloud` 与 `restoreForDevice`。
- 原样回放：同厂商优先 `RAW_REPLAY`，失败自动降级 canonical 转码。
- 安全保护：manifest 完整性校验（size + sha256）、路径穿越防护、目录级并发锁。
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

- 跨厂商转码要求输入视频切片为 ISO BMFF（MP4/QuickTime）容器。
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

## 5. 错误语义

- 统一异常类型：`LivePhotoSdkException`（继承 `IllegalArgumentException`）。
- 结构化错误码：`LivePhotoErrorCode`（如 `DETECTION_FAILED`、`INTEGRITY_CHECK_FAILED`、`UNSUPPORTED_TRANSCODE_TARGET`）。
- 协程取消语义：`CancellationException` 直接透传，不会被包装。

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
