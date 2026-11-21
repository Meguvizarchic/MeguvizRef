# 构建与验证

## 工具链

- JDK 17；Gradle Wrapper 固定构建工具版本。
- JVM 模式不要求 Android SDK。
- Android 模式使用 SDK `platforms;android-37.0` 与 `build-tools;37.0.0`。CI 还固定了 SDK 命令行工具版本。

## 常用命令

在项目根目录执行：

```sh
# 核心合同、独立 Java/Kotlin 消费者、公开 API 和旧客户端兼容性
bash gradlew verifyHost --build-cache --no-daemon

# 增加 Android 库 lint 与 R8 消费应用构建
bash gradlew -Pmeguviz.profile=android verifyHost verifyAndroid --build-cache --no-daemon

# 验证后生成三个模块的 Maven 制品、源码包及校验和
bash gradlew -Pmeguviz.profile=android packageRelease --build-cache --no-daemon

# 主机 JMH 微基准（默认不纳入构建，不发布，不属于 verifyHost）
bash gradlew -Pmeguviz.benchmarks=true :benchmark-jvm:jmh -Pjmh.include=InvokeBenchmark "-Pjmh.args=-f 2 -wi 3 -i 5 -w 1s -r 1s -prof gc"

# jcstress 并发测试（同样默认不纳入构建；单一 JVM 配置约 1.5 分钟）
bash gradlew -Pmeguviz.jcstress=true :stress-jvm:jcstress "-Pjcstress.args=-m quick -jvmArgs -Xmx1g -sc false"

# 并发计数/关闭协议与无锁视图的变异检查（每个错误变体都应失败）
python3 tools/mutation_check.py
```

> **适用范围与验证说明**：JMH 基准与 jcstress 并发测试结果仅代表宿主 JDK 17 运行环境，不直接等同于 Android ART 或真机表现。

Windows 下请用 `gradlew.bat` 替换 `bash gradlew`；PowerShell 中含点号的 `-P` 参数需整体加引号，例如 `'-Pmeguviz.profile=android'`。

`build/local-maven/` 保存本次构建的本地 Maven 仓库；`build/release/` 保存打包输出。合同测试结果位于各模块的 `build/test-results/`。独立夹具说明见 [test-fixtures](../test-fixtures/README.md)。

## CI 与发布

- `main` 分支和拉取请求的构建相关变更触发 CI；纯文档变更不触发完整构建，同一分支的新运行会自动取消过期任务。
- 发布版本标签应与 `gradle.properties` 中的 `meguviz.version` 一致。发布后应保持各来源坐标版本唯一，已发布版本不能被不同内容覆盖。JitPack 使用标签源码独立构建库模块。

## 提交范围

只提交源码、构建配置、文档与可公开的测试夹具。凭据、本机配置、签名材料、设备标识及本地运行记录应保留在仓库之外，提交前务必核查暂存区。

[返回文档目录](README.md)
