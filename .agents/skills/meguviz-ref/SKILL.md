---
name: meguviz-ref
description: 指导在 Java 17 与 Android API 27+ 工程中接入与迁移 MeguvizRef（io.github.jichuo1.meguviz:meguviz-ref-*:1.2.0），替代脆弱的 Class.forName、getDeclaredMethod 静态缓存与跨加载器反射封装。遇到“JVM 反射解析”“CodeScope”“QuerySpec”“MethodPlan”“FieldPlan”“ConstructorPlan”“RawResolution”“代码代次刷新”“R8 反射保留”时触发。若需拦截方法执行 Hook，转交 meguviz-hook；若需扫描未加载 DEX/APK，转交 dexmeguviz（或 hook-dex-bridge）。
---

# MeguvizRef 接入与迁移

用受加载器作用域管控的反射解析、无接收者调用计划与结构化诊断回执，替代进程级静态反射缓存与脆弱的 `Class.forName` / `setAccessible` 样板代码。

## 第 1 阶段：盘点工程现状

改代码前先只读核对工程基线并输出简短评估：

1. **核对工具链与版本基线**：
   - 检查 `gradle/libs.versions.toml`、`build.gradle.kts` / `pom.xml`，确认字节码目标为 Java 17。
   - 若涉及 Android 模块，确认 `minSdk >= 27`、`compileSdk >= 37`，且未混用 JitPack 与 Maven Central 坐标。
2. **检索存量裸反射调用点**：
   - 全仓搜索 `Class.forName`、`getDeclaredMethod`、`getMethod`、`getDeclaredField`、`getDeclaredConstructor`、`setAccessible(true)`、静态 `Map<..., Method>` 或 `ReflectionUtils` 缓存。
   - 标出每个调用点对应的目标类、静态/实例性质、是否涉及重载或父类继承。
3. **梳理加载器与热更边界**：
   - 区分目标成员属于应用主 `ClassLoader`、动态插件加载器还是系统框架。
   - 标注触发代码热更新、插件卸载或 Android hidden API 豁免变更的时机。
4. **检查混淆规则**：
   - 查看 `proguard-rules.pro` / `consumer-rules.pro`，记录哪些目标成员以及诊断探测入口需要消费方显式 `-keep`。

盘点输出只需给出：待迁移调用点清单、目标 `CodeScope` 生命周期范围、所需 `AccessPolicy` 与 R8 保留成员列表；确认后再改代码。

## 第 2 阶段：选择模块与接入依赖

`io.github.jichuo1.meguviz:meguviz-ref-*:1.2.0` 已发布至 Maven Central，可通过 `meguviz-bom:1.2.0` 统一管理版本：

| 适用场景 | 制品坐标（groupId: `io.github.jichuo1.meguviz`） | 依赖边界与要求 |
| --- | --- | --- |
| 查询、作用域与调用计划（核心必选） | `meguviz-ref-core:1.2.0` | Java 17 字节码，零第三方生产依赖 |
| 结构化回执 JSON 与磁盘持久化恢复 | `meguviz-ref-diagnostics:1.2.0` | 传递依赖 `ref-core`，仅持久化/独立复验时引入 |
| Android 平台环境路径与 ABI 快照 | `meguviz-ref-android:1.2.0` | 需 `minSdk 27`、`compileSdk 37+`，不持有 `Context` |

```kotlin
dependencies {
    implementation(platform("io.github.jichuo1.meguviz:meguviz-bom:1.2.0"))
    implementation("io.github.jichuo1.meguviz:meguviz-ref-core")
}
```

- **依赖最小化**：仅做内存反射解析时不要引入 `ref-diagnostics` 或 `ref-android`；若仅需从已发布版本获取且不启用 BOM，可直接写明 `:1.2.0`。
- **兄弟库协作边界**：需要安装方法拦截 Hook 时，使用 `meguviz-hook` 的 `hook-ref-bridge` 复用 `CodeScope`；需要离线扫描未加载 DEX 字节定位混淆方法时，转交 `dexmeguviz`（或 `hook-dex-bridge`），不要在 `MeguvizRef` 中自行遍历字节码。

## 第 3 阶段：执行最小迁移与生命周期对齐

### 最小接入与常见调用骨架

```java
// 1. 作用域内绑定与复用调用计划（按需选择 AccessPolicy）
try (CodeScope scope = CodeScope.open(targetLoader, AccessPolicy.PRESERVE_JAVA_ACCESS)) {
    MethodPlan sub = scope.bindMethod(QuerySpec.method(String.class, "substring", int.class));
    Object slice = sub.call("Meguviz", 4); // "viz"

    // 2. 结构条件查找父类或重载成员
    QuerySpec inherited = QuerySpec.builder(SubTarget.class, MemberKind.METHOD)
            .name("compute")
            .hierarchy(Hierarchy.SUPERCLASSES)
            .arguments(CompatibilityPolicy.JAVA_INVOCATION, 42)
            .selection(Selection.UNIQUE)
            .build();
    MethodPlan compute = scope.bindMethod(inherited);
    Object result = compute.call(targetInstance, 42);

    // 3. 批量导出原始 Member 给外部桥接（不占 4096 binding 槽位）
    RawResolution raw = scope.resolveRaw(QuerySpec.exact(
            MemberDescriptor.method("com.example.HookTarget", "onEvent", "V", "Ljava/lang/String;")));
    if (raw.status() == ResolutionStatus.ALL_MATCHES || raw.status() == ResolutionStatus.RESOLVED) {
        for (Member m : raw.members()) { /* 由调用方接管后续生命周期 */ }
    }
}
```

### 生命周期与异常铁律

1. **禁止无意触发类初始化**：`MeguvizRef` 默认通过 `Class.forName(name, false, loader)` 加载目标，定位阶段不执行目标类 `<clinit>`。
2. **禁止跨 `CodeScope` 或跨代次保留计划**：代码热更或加载器切换时调用 `scope.advanceCodeEpoch("reload")` 或关闭重建 `CodeScope`；过期计划再调用会抛出 `StalePlanException`。长驻场景应通过 `scope.isCurrent(loader, expectedIdentity, expectedEpoch)` 在单次加锁内复核一致性。
3. **非公开访问显式受限**：`AccessPolicy.TRY_SUPPRESS_JAVA_CHECKS` 仅调用公开 `setAccessible(true)` 并以 `IllegalAccessException` 保留原始原因，不承诺绕过 Java 模块封装或 Android hidden API。
4. **严禁把不完整结果当成“未找到”**：仅在 `status() == NOT_FOUND_IN_VISIBLE_UNIVERSE` 且 `coverage().complete()` 为 `true` 时视为当前可见域无匹配；`UNAVAILABLE`、`PARTIAL` 与 `CANCELLED` 均须走降级分支。
5. **原始导出脱离作用域控制**：`exportRaw` 与 `resolveRaw` 返回的 `Member` 脱离 `CodeScope` 管控，关闭作用域不会撤销它们，且可能延长类加载器存活时间。
6. **单飞等待中断判定与 R8 保留**：`ResolutionReceipt.interruptionOrigin()`（1.2.0）仅在本次调用作为单飞等待者于 `Future.get` 被中断时返回 `SINGLE_FLIGHT_WAITER`，其余一律为 `UNKNOWN`。消费方若通过反射探测该方法，必须在 R8 规则中保留 `ResolutionReceipt.interruptionOrigin()` 与 `ResolutionReceipt$InterruptionOrigin` 枚举，以及业务自身要发现的目标成员。

> **按需深入阅读**：完整制品表、全量 API 签名与枚举细节见 [API 与模块速查](references/api-and-modules.md)；裸反射迁移对照、持久化恢复与状态排障见 [迁移对照与排障](references/migration-patterns.md)。

## 第 4 阶段：验证与交付清单

1. **消费方验证**：
   - 运行业务单测，验证正常调用、多匹配 `AMBIGUOUS`、访问受拒 `ACCESS_DENIED`、`scope.close()` 或 `advanceCodeEpoch` 后的 `StalePlanException` 回退分支。
   - 若启用 R8 混淆，打包 Release APK/JAR 并实际运行目标反射路径，确认业务成员与可选 `interruptionOrigin()` 探测未被裁剪。
2. **本库源码自检命令**（在仓库根目录执行，Windows 用 `gradlew.bat` 替换 `./gradlew`）：
   - JVM 合同、独立 Java/Kotlin 消费者与旧编译客户端 ABI 检查：
     `./gradlew verifyHost --build-cache --no-daemon`
   - 含 Android 库 lint 与 R8 消费应用构建（需 Android SDK `37.0`）：
     `./gradlew -Pmeguviz.profile=android verifyHost verifyAndroid --build-cache --no-daemon`
3. **失败与回退核对**：
   - 检查 `scope.closeFailureType()`（仅用于记录排空后 Provider 清理异常，不代表阻塞清理已完成）。
   - 核对 `PersistentResolutions.restore` 在返回 `STALE_IDENTITY` / `POLICY_MISMATCH` 时能安全丢弃旧缓存并回退到在线 `QuerySpec` 解析。
4. **交付摘要**：列出已迁移的反射调用点、接入坐标与版本、`CodeScope` 关闭与 `advanceCodeEpoch` 位置、R8 Keep 变更及实测命令结果。
