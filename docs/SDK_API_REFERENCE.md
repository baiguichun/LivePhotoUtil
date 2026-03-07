# LivePhotoCompat SDK API 参考

## 1. 协程入口（推荐）

类：`LivePhotoCoroutineSdk`

### 1.1 `detect`

```kotlin
suspend fun detect(candidates: List<File>): LivePhotoAsset?
```

- 用途：识别输入候选文件。
- 失败行为：未识别返回 `null`；参数非法会抛异常。

### 1.2 `detectOrThrow`

```kotlin
suspend fun detectOrThrow(candidates: List<File>): LivePhotoAsset
```

- 用途：识别输入候选文件。
- 失败行为：未识别抛异常。

### 1.3 `transcode`

```kotlin
suspend fun transcode(
    asset: LivePhotoAsset,
    targetVendor: DeviceVendor,
    outputDir: File
): ConversionResult
```

- 用途：把统一资产转为目标厂商格式。
- 约束：
  - 目标 Android MotionPhoto 时，`asset.video` 必须是 ISO BMFF（MP4/QuickTime）切片。
  - 目标 Apple 时，SDK 输出 `.mov`，并生成 sidecar 属性文件。

### 1.4 `detectAndTranscode`

```kotlin
suspend fun detectAndTranscode(
    candidates: List<File>,
    targetVendor: DeviceVendor,
    outputDir: File
): ConversionResult
```

- 用途：一步完成识别与转码。

### 1.5 `normalizeForCloud`

```kotlin
suspend fun normalizeForCloud(asset: LivePhotoAsset, cloudDir: File): CanonicalPackage
```

- 用途：写 canonical 包。

### 1.6 `detectAndNormalizeForCloud`

```kotlin
suspend fun detectAndNormalizeForCloud(
    candidates: List<File>,
    cloudDir: File
): CanonicalPackage
```

- 用途：一步完成识别与 canonical 归档。

### 1.7 `restoreForDevice`

```kotlin
suspend fun restoreForDevice(
    canonicalDir: File,
    targetVendor: DeviceVendor,
    outputDir: File,
    preferRawReplay: Boolean = true
): ConversionResult
```

- 用途：从 canonical 包恢复到目标厂商格式。
- 特性：同厂商 raw replay 失败时自动降级到 canonical 转码。

### 1.8 `restoreOriginal`

```kotlin
suspend fun restoreOriginal(canonicalDir: File, outputDir: File): List<File>
```

- 用途：直接恢复 canonical 包中的原始文件（无转码）。

## 2. 领域模型

### 2.1 `LivePhotoAsset`

- `vendor`：来源厂商。
- `protocol`：识别协议。
- `image`：封面切片。
- `video`：视频切片。
- `contentId`：内容标识。
- `notes`：处理备注。

### 2.2 `MediaSlice`

- `sourceFile`：源文件。
- `offset` / `length`：切片范围。
- `mimeType`：媒体类型。

### 2.3 `ConversionResult`

- `targetVendor`：目标厂商。
- `mode`：输出模式（`APPLE_PAIR` / `MOTION_PHOTO_JPEG` / `RAW_REPLAY`）。
- `outputFiles`：输出文件集合。

### 2.4 `CanonicalPackage`

- `dir`：canonical 目录。
- `imageFile` / `videoFile` / `manifestFile` / `rawDir`：核心路径。

## 3. 异常语义

SDK 统一抛出 `LivePhotoSdkException`（继承 `IllegalArgumentException`），核心字段：

- `code: LivePhotoErrorCode`
- `message: String`
- `cause: Throwable?`

主要错误码：

1. `INVALID_INPUT`
2. `DETECTION_FAILED`
3. `UNSUPPORTED_VENDOR`
4. `UNSUPPORTED_TRANSCODE_TARGET`
5. `CANONICAL_MANIFEST_MISSING`
6. `CANONICAL_PAYLOAD_MISSING`
7. `INTEGRITY_CHECK_FAILED`
8. `RAW_REPLAY_UNAVAILABLE`
9. `OUTPUT_PATH_INVALID`
10. `FILE_IO_ERROR`
11. `INTERNAL_ERROR`

协程取消语义：

- `LivePhotoCoroutineSdk` 会透传 `CancellationException`，不会包装为业务异常。
