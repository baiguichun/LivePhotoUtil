# LivePhotoCompat SDK 发布策略

## 1. 版本号规则

使用语义化版本（SemVer）：`MAJOR.MINOR.PATCH`

- `MAJOR`：出现不兼容 API 变更。
- `MINOR`：增加向后兼容功能。
- `PATCH`：修复向后兼容问题。

## 2. 发布坐标来源

Gradle 属性（`gradle.properties`）：

- `sdk.group`
- `sdk.artifact`
- `sdk.version`

## 3. 发布命令

发布到本地 Maven：

```bash
./gradlew :app:publishReleasePublicationToMavenLocal
```

## 4. 发布门禁

每次发布前必须满足：

1. `./gradlew test` 通过。
2. 公开 API 无未声明破坏式变更。
3. 发布说明包含：
   - 新增能力
   - 修复问题
   - 兼容性影响

推荐发布前固定执行：

1. `./gradlew test lintDebug check`
2. `./gradlew :app:checkBinaryCompatibility`
3. 如 API 变更是有意且版本号已按 SemVer 调整，执行 `./gradlew :app:updateApiBaseline`
4. `./gradlew :app:publishReleasePublicationToMavenLocal`
