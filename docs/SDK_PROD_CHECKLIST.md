# LivePhotoCompat SDK 生产清单

## 1. 上线前质量门禁

必须通过：

1. `./gradlew test`
2. `./gradlew lintDebug`
3. `./gradlew check`
4. 关键路径人工回归：
   - Apple -> Xiaomi
   - Huawei -> Apple
   - 同厂商 raw replay
   - canonical 被篡改后的失败行为
5. 回归样本要求：
   - 参与跨厂商转码的视频样本必须是 ISO BMFF（MP4/QuickTime）。
   - 至少覆盖一组 QuickTime 输入转 Android MotionPhoto 的样本。

## 2. 云端存储规范

建议服务端对 canonical 包做二次校验：

1. `manifest.properties` 必填字段完整。
2. `image.bin` / `video.bin` 的 `size` 与 `sha256` 匹配。
3. `raw.*` 的 `size` / `sha256` 匹配。
4. 文件名不允许路径穿越字符。

## 3. 运行监控建议

建议埋点：

1. 识别成功率（按厂商分桶）。
2. 转码成功率（按输入协议 + 目标厂商分桶）。
3. raw replay 命中率与降级率。
4. canonical 校验失败率（size mismatch / sha mismatch）。

## 4. 兼容性演进建议

新增厂商或规则变更时：

1. 添加 `VendorProfile`。
2. 添加探测/转码回归测试样例。
3. 更新 `SDK_PRODUCTION_GUIDE.md` 与发布说明。
4. bump 版本号（SemVer）。
