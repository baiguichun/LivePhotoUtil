# LivePhotoCompat SDK 生产接入文档

## 1. SDK 能力概览

- 输入识别：自动识别 LivePhoto 资源（Apple 双文件 / Android MotionPhoto / 通用图片+视频配对）。
- 格式转换：把统一资产转为目标厂商可识别格式。
- 云端中间格式：把资源归档为统一 canonical 包，跨设备再恢复。
- 原样回放：同厂商场景可优先直接回放原始文件（文件名、字节、修改时间保持一致）。
- 协程接口：提供 `suspend` API，内部自动切换 `Dispatchers.IO`。

## 2. 支持的协议与厂商

### 协议

- `APPLE_PAIR`：图片 + 视频双文件（如 `.JPG + .MOV`）。
- `MOTION_PHOTO`：单 JPEG 内嵌 MP4（MotionPhoto）。
- `GENERIC_PAIR`：通用图片+视频配对兜底。

### 厂商

- Apple
- Google
- Huawei
- vivo
- OPPO
- Xiaomi

## 3. 互转能力

- 输入 -> 统一资产：`LivePhotoCompatEngine.detect(...)`
- 统一资产 -> 目标厂商：
  - 目标 Apple：输出双文件（图片 + `.mov/.mp4`）+ sidecar。
  - 目标 Android 厂商（Google/Huawei/vivo/OPPO/Xiaomi）：输出单 JPEG MotionPhoto。
- 云端 canonical -> 目标设备：
  - 同厂商且 `preferRawReplay=true`：优先 `RAW_REPLAY`。
  - 其他场景：使用中间格式 `image.bin + video.bin` 转码恢复。

## 4. 云端中间格式规范

canonical 目录结构：

```text
<canonicalDir>/
  image.bin
  video.bin
  manifest.properties
  raw/
    0_xxx
    1_xxx
```

`manifest.properties` 关键字段：

- `vendor` / `protocol` / `contentId`
- `imageMime` / `videoMime`
- `rawFileCount`
- `raw.{i}.originalName`
- `raw.{i}.storedName`
- `raw.{i}.size`
- `raw.{i}.lastModified`
- `raw.{i}.sha256`

说明：

- `raw/` 用于原样回放。
- `image.bin/video.bin` 用于跨协议恢复。
- 同一 `cloudDir` 重复归档时会清理旧文件，避免脏数据残留。

## 5. 协程接入方式

推荐只暴露 `LivePhotoCoroutineSdk` 给业务层：

```kotlin
val sdk = LivePhotoCoroutineSdk()

val canonical = sdk.detectAndNormalizeForCloud(
    candidates = listOf(imageFile, videoFile),
    cloudDir = cloudDir
)

val result = sdk.restoreForDevice(
    canonicalDir = canonical.dir,
    targetVendor = DeviceVendor.APPLE,
    outputDir = outDir,
    preferRawReplay = true
)
```

## 6. 线程安全与并发模型

- SDK 是文件级实现（`java.io.File`），已内置目录级锁（`DirectoryLockRegistry`）。
- 对同一目录的写入会串行化，避免并发覆盖。
- 写文件使用原子替换（临时文件写完再替换），避免半写状态。
- 建议并发时使用不同输出目录以提升吞吐。

## 7. 100% 原样回放条件

满足以下条件时可实现原样回放：

- 已执行 `normalizeForCloud` 且 canonical 包保留 `raw/` 与 `manifest`。
- `restoreForDevice(..., preferRawReplay=true)` 且目标厂商与源厂商一致，或直接调用 `restoreOriginal(...)`。
- 云端未丢失/篡改 `raw` 文件与摘要字段（SHA-256 校验通过）。

## 8. 上传接口设计建议

推荐两种方式：

- 方式 A：客户端把 canonical 目录打包为 zip 后单文件上传。
- 方式 B：`manifest.properties` + 多文件分片（`image.bin/video.bin/raw/*`）分开上传。

建议最小接口：

- `POST /livephoto/packages`：创建上传会话，返回 `packageId`。
- `PUT /livephoto/packages/{packageId}/manifest`：上传 manifest。
- `PUT /livephoto/packages/{packageId}/files/{name}`：上传二进制文件。
- `POST /livephoto/packages/{packageId}/complete`：服务端校验并封包完成。
- `GET /livephoto/packages/{packageId}`：下载 canonical 包。

服务端校验建议：

- 校验 `rawFileCount` 与 `raw.*` 完整性。
- 校验 `sha256` 与文件大小。
- 拒绝路径穿越文件名（SDK 本地恢复也已防护）。

## 9. 生产使用建议

- `minSdk` 已为 `24`。
- 大文件场景优先使用协程 API，避免主线程阻塞。
- 对外暴露错误时建议映射成业务错误码（如：探测失败、清单损坏、校验失败、目标厂商不支持）。
- 发布前至少执行：`./gradlew test lintDebug`。
