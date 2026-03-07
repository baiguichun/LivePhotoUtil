# LivePhotoCompat SDK 架构说明

## 1. 目标

本 SDK 解决三类问题：

1. 多厂商 LivePhoto 输入识别。
2. 统一资产模型上的跨厂商格式转换。
3. 云端 canonical 中间格式归档与恢复。

## 2. 模块结构

核心代码位于 `app/src/main/java/com/xiaobai/livephotoutil/compat`：

- `Model.kt`：领域模型（`LivePhotoAsset`、`MediaSlice`、`ConversionResult`）。
- `VendorProfiles.kt`：厂商协议 profile 配置。
- `MediaIO.kt`：底层文件读写、MIME 与媒体片段处理。
- `Adapters.kt`：协议探测适配器（Apple、各 Android 厂商、Generic 兜底）。
- `LivePhotoCompatEngine.kt`：探测引擎（按优先级执行适配器）。
- `LivePhotoTranscoder.kt`：跨厂商转码。
- `CloudCompatService.kt`：canonical 归档与恢复。
- `DirectoryLockRegistry.kt`：目录级并发锁。
- `LivePhotoCoroutineSdk.kt`：协程化外观 API。

## 3. 关键流程

### 3.1 探测流程

1. 输入文件校验。
2. 按优先级执行适配器：
   - Apple pair
   - Vendor MotionPhoto（Huawei/vivo/OPPO/Xiaomi/Google）
   - Generic pair fallback
3. 返回统一 `LivePhotoAsset`。

### 3.2 MotionPhoto 严格校验

Vendor MotionPhoto 探测要求同时满足：

1. JPEG 容器。
2. 可提取 XMP 包。
3. 命中厂商 marker/属性。
4. XMP 解析到 `MicroVideoOffset`。
5. `MicroVideoOffset` 与实际 `ftyp` 偏移一致（容忍极小偏差）。
6. 偏移位置确认为 ISO BMFF `ftyp`。

### 3.3 Generic 兜底策略

Generic fallback 使用“配对分 + 额外上下文分”的双层判断：

- 文件名 stem/normalized stem 匹配分。
- 同目录与修改时间接近的额外分。
- 最低阈值过滤。
- 最高分并列时拒绝识别（防歧义误配）。

### 3.4 云端 canonical 流程

归档：

- 写入 `image.bin`、`video.bin`。
- 保存 `manifest.properties`（含 `image/video` 尺寸与 SHA-256）。
- 保存 `raw/` 原始文件（含大小、mtime、SHA-256）。

恢复：

1. 同厂商且允许 raw replay：优先尝试原样回放。
2. raw replay 失败自动降级为 canonical 转码。
3. canonical 转码前校验 `image/video` 完整性（size + sha256）。

## 4. 并发与一致性

- 写操作采用目录级锁。
- 文件写入采用临时文件 + 原子替换策略，避免半写态。
- 锁注册表具备引用计数回收，避免长期运行下锁表无限增长。

## 5. 可扩展性

新增厂商一般只需：

1. 在 `VendorProfiles.kt` 增加 profile。
2. 保证目标厂商的 XMP 属性注入策略。
3. 增加对应探测与转码回归测试。

## 6. 已知边界

- 非 JPEG 封面不能直接转 MotionPhoto（需先转 JPEG）。
- 当前是文件级 SDK，不包含播放器 UI 与媒体解码器。
