# MeguvizRef 迁移对照与排障

本文整理从传统 Java/Android 反射迁移至 MeguvizRef 的常见范式、所有权与并发约束，以及高频错误排查方案。

## 1. 常见写法迁移对照

### 模式 1：静态 `Class.forName` + `Method` 缓存 → `CodeScope` + `MethodPlan`

**迁移前（裸反射 + 全局静态缓存，容易泄漏插件 `ClassLoader` 且热更后失效不感知）：**

```java
private static volatile Method cachedParse;

static Object invokeParse(ClassLoader loader, Object target, String input) throws Exception {
    if (cachedParse == null) {
        Class<?> cls = Class.forName("com.example.Parser", true, loader); // 意外提前初始化类
        cachedParse = cls.getDeclaredMethod("parse", String.class);
        cachedParse.setAccessible(true);
    }
    return cachedParse.invoke(target, input);
}
```

**迁移后（作用域管理生命周期 + 不主动初始化 + 调用计划复用）：**

```java
// 由加载器/代次管理方持有 scope 与 plan，代次变化时调用 advanceCodeEpoch 或 close 重建
try (CodeScope scope = CodeScope.open(loader, AccessPolicy.TRY_SUPPRESS_JAVA_CHECKS)) {
    QuerySpec query = QuerySpec.builder("com.example.Parser", MemberKind.METHOD)
            .name("parse")
            .parameters(String.class)
            .build();
    MethodPlan parsePlan = scope.bindMethod(query);
    Object result = parsePlan.call(target, input);
}
```

### 模式 2：按实际调用参数匹配重载或父类方法

**迁移前**：循环遍历 `getDeclaredMethods()` 和父类，手动比对参数数组长度与 `isAssignableFrom`。

**迁移后**：

```java
QuerySpec query = QuerySpec.builder(TargetSub.class, MemberKind.METHOD)
        .name("handle")
        .hierarchy(Hierarchy.SUPERCLASSES) // 从当前类沿父类逐级查找
        .arguments(CompatibilityPolicy.JAVA_INVOCATION, 100, "tag")
        .selection(Selection.UNIQUE)
        .build();
MethodPlan plan = scope.bindMethod(query);
```

- 若已知严格签名且希望可跨进程持久化描述，使用 `QuerySpec.exact(MemberDescriptor.method(...))`。
- 若只需要严格装箱匹配（不允许 `int` 接收 `Byte` 拓宽），使用 `CompatibilityPolicy.LEGACY_BIL`。

### 模式 3：热重载 / 动态插件升级与一致性核对

```java
// 检查当前线程持有的作用域是否仍对应当前 loader 与代码代次
if (!scope.isCurrent(currentLoader, expectedIdentity, expectedEpoch)) {
    // 重新获取或重建 scope 与调用计划
}

// 代码集合变化（如触发热更或新注入 hidden API 豁免）时推进 epoch
scope.advanceCodeEpoch("plugin-updated");
// 推进之后，旧 MethodPlan / FieldPlan / ConstructorPlan 再调用将抛出 StalePlanException
```

### 模式 4：批量导出原始 `Member` 给 Hook 或外部组件

```java
// 1.2.0 推荐：resolveRaw 不建立 binding 槽位，避免在长生命周期导出大量成员时触及 4096 binding_capacity
RawResolution raw = scope.resolveRaw(QuerySpec.builder(TargetClass.class, MemberKind.METHOD)
        .name("onEvent")
        .selection(Selection.ALL)
        .build());
if (raw.status() == ResolutionStatus.ALL_MATCHES && raw.receipt().coverage().complete()) {
    for (Member member : raw.members()) {
        // 注意：导出的 Member 脱离 CodeScope 管理，close() 不会撤销该引用
        registerExternal(member);
    }
}
```

### 模式 5：磁盘持久化与重启后安全恢复

```java
CodeIdentity currentId = CodeIdentities.hashFiles(apkFiles, loadingConfig, QuerySpec.RULE_VERSION);
try (ReceiptStore store = new FileReceiptStore(cacheDir, 256);
     CodeScope scope = CodeScope.open(appLoader, AccessPolicy.PRESERVE_JAVA_ACCESS)) {
    PersistentResolutions.LoadResult loaded = PersistentResolutions.restore(
            store, "feature.parse", currentId, AccessPolicy.PRESERVE_JAVA_ACCESS, appLoader, scope);
    if (loaded.status() == PersistentResolutions.Status.REBOUND) {
        MethodPlan plan = scope.bindMethod(loaded.resolution().get().requireSingle());
    } else {
        // STALE_IDENTITY / MISS / UNAVAILABLE 等均安全回退为重新结构化解析
    }
}
```

## 2. 生命周期、并发与所有权规则

- **无锁调用与分段计数**：`MethodPlan.call`、`FieldPlan.get/set` 与 `ConstructorPlan.create` 不进入作用域监视锁；并发竞争时作用域会一次性切换为按线程散列的 128 字节填充计数单元。
- **`close()` 排空约定**：`CodeScope.close()` 先拒绝新调用和解析，再对已在途的调用排空；它不阻塞等待正在执行的目标方法返回。若有已注册的 `ProviderRegistry`，最后一个排空退出的工作线程或无在途时的 `close()` 会执行 Provider 关闭。
- **单飞等待（single-flight）**：同一未命中查询并发执行时共享一次解析，等待超时为 `2000 ms`，并发在途查询数上限为 `min(capacity, 256)`。等待者线程被中断时返回 `CANCELLED`，且 `receipt().interruptionOrigin()` 为 `SINGLE_FLIGHT_WAITER`。
- **容量限制**：默认容量 `4096` 同时约束查询/schema 缓存、成员表缓存保留的 `Member` 引用总数以及已发布的 `binding` 数量。新绑定超出上限会返回 `PARTIAL`（原因为 `binding_capacity`），不会驱逐已发布的绑定。

## 3. 高频问题排查表

| 现象 / 状态 | 根因分析 | 处理与回退建议 |
| --- | --- | --- |
| `AMBIGUOUS` | `Selection.UNIQUE` 下匹配到两个及以上真实成员 | 补全参数类型、返回类型或 `staticOnly(true)` 缩小条件 |
| `NOT_FOUND_IN_VISIBLE_UNIVERSE` | 在目标类可见域内完整检查后无匹配成员 | 若需查父类方法，改用 `Hierarchy.SUPERCLASSES`；若成员被 R8 裁剪，检查消费方 Keep 规则 |
| `UNAVAILABLE` | 类缺失、链接失败或被系统隐藏 API 过滤 | 不区分“不存在”与“被系统过滤”；检查 `receipt().reason()` 与 `causeType()`，走功能降级分支 |
| `PARTIAL`（`binding_capacity`） | 同一 `CodeScope` 绑定成员数超过容量上限 | 仅导出原始成员时改用 `resolveRaw`；或增大 `open` 容量 / 按生命周期拆分作用域 |
| `PARTIAL`（`single_flight_timeout`） | 并发同键查询等待超过 2000 ms | 排查查询工厂或自定义 Provider 是否存在慢 I/O 或锁争用 |
| `CANCELLED` | 线程进入前已中断或等待期间被中断 | 检查 `receipt().interruptionOrigin()`，按中断协议清理并重试或放弃 |
| `ACCESS_DENIED` / `IllegalAccessException` | `TRY_SUPPRESS_JAVA_CHECKS` 下 `setAccessible(true)` 被模块封装或安全策略拒绝 | 检查 `cause`；确认模块导出配置或改走公开接口 |
| `StalePlanException` | 计划所在 `CodeScope` 已 `close()` 或已调用 `advanceCodeEpoch` | 在当前有效 `CodeScope` 重新解析并绑定新计划 |
| R8 构建后反射探测 `interruptionOrigin()` 返回 `UNKNOWN` | 消费方 R8 删除或重命名了 `ResolutionReceipt.interruptionOrigin()` 或内部枚举 | 在消费方 `proguard-rules.pro` 显式保留该方法及 `ResolutionReceipt$InterruptionOrigin` 枚举成员 |

## 4. 安全回退原则

1. 任何非 `RESOLVED` / `ALL_MATCHES` 的结果一律按“当前不可用”处理，走业务兜底逻辑，不得强制解包 `members().get(0)`。
2. 遇到 `StalePlanException` 时重新进入最新 `CodeScope` 初始化流程，不缓存异常前的 `ResolvedMember` 令牌。
3. 持久化恢复 `PersistentResolutions.restore` 返回非 `REBOUND` 时，丢弃旧缓存记录，回退至在线 `QuerySpec` 发现路径。
