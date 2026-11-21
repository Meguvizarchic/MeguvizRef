# MeguvizRef API 与模块速查

本文汇总 MeguvizRef `1.2.0` 的真实 Maven 制品、编译要求、核心类签名以及家族配合边界，所有符号均以仓库源码为依据。

## 1. 制品与工具链要求

统一 groupId：`io.github.jichuo1.meguviz`，当前发布版本 `1.2.0`（支持 `io.github.jichuo1.meguviz:meguviz-bom:1.2.0` 统一对齐）。

| 制品 `artifactId` | 核心能力 | 编译/运行要求 | 第三方生产依赖 |
| --- | --- | --- | --- |
| `meguviz-ref-core` | 描述符、查询规范、作用域、调用计划、SPI | Java 17 字节码 | 无（仅 JDK） |
| `meguviz-ref-diagnostics` | 强代码身份、收据 JSON、文件存储与复验 | Java 17 字节码，依赖 `ref-core` | 无 |
| `meguviz-ref-android` | `AndroidEnvironment`（APK 路径与 ABI 快照） | Android `minSdk 27`、`compileSdk 37+`、Java 17 | 无 |

> 备用 Maven 仓库：`https://raw.githubusercontent.com/jichuo1/MeguvizRef/maven/`；JitPack 坐标组为 `com.github.jichuo1.MeguvizRef`，请勿在同一工程混用不同来源的坐标。

## 2. 核心类型与方法签名

### 2.1 作用域：`io.github.jichuo1.meguviz.ref.scope.CodeScope`

实现 `AutoCloseable`，管理缓存、绑定槽位、Provider 注册表与 `ClassLoader` 生命周期。

- **创建与配置**
  - `static CodeScope open(ClassLoader loader)`（默认 `AccessPolicy.PRESERVE_JAVA_ACCESS`，容量 `4096`）
  - `static CodeScope open(ClassLoader loader, AccessPolicy accessPolicy)`（容量 `4096`）
  - `static CodeScope open(ClassLoader loader, AccessPolicy accessPolicy, int capacity)`（`capacity` 范围 `1..1000000`）
  - `void installProviders(ProviderRegistry registry, CodeIdentity codeIdentity)`（仅可在首次解析前调用一次，且需 `CodeIdentity.Strength.STRONG`）
- **状态与一致性核对**
  - `State state()`：`OPEN` / `RETIRING` / `CLOSED`
  - `AccessPolicy accessPolicy()`、`long codeEpoch()`、`String identity()`
  - `boolean usesLoader(ClassLoader candidate)`
  - `boolean isCurrent(ClassLoader candidate, String expectedIdentity, long expectedEpoch)`（单次加锁核对 loader、identity 与 epoch）
  - `Map<String, Long> diagnostics()`（含 `cacheHits`、`cachedQueries`、`bindings`、`schemas`、`parameterReads`、`candidateChecks`、`lookups`、`inFlight`）
  - `String closeFailureType()`（仅在排空后 Provider 关闭失败时记录异常类名）
- **查询、绑定与导出**
  - `ResolutionResult resolve(QuerySpec query)`
  - `RawResolution resolveRaw(QuerySpec query) throws IllegalAccessException`（不创建 binding、不写入查询缓存，返回 `RawResolution`）
  - `MethodPlan bindMethod(ResolvedMember member) throws IllegalAccessException`
  - `MethodPlan bindMethod(QuerySpec query) throws IllegalAccessException`（等价于 `bindMethod(resolve(query).requireSingle())`）
  - `FieldPlan bindField(ResolvedMember member) throws IllegalAccessException` / `bindField(QuerySpec query)`
  - `ConstructorPlan bindConstructor(ResolvedMember member) throws IllegalAccessException` / `bindConstructor(QuerySpec query)`
  - `Member exportRaw(ResolvedMember token) throws IllegalAccessException`（导出原始 `Member`，脱离作用域生命周期）
- **刷新与关闭**
  - `void advanceCodeEpoch(String reason)`（清空缓存与当前绑定并递增 epoch；`Long.MAX_VALUE` 后轮换 `identity` 并归零）
  - `void close()`（幂等拒绝新调用，不等待已在途目标方法；在途排空后自动关闭已安装 Provider）

### 2.2 描述符：`io.github.jichuo1.meguviz.ref.api.descriptor`

- `TypeDescriptor`
  - `static TypeDescriptor of(String value)`（校验 JVM 类型描述符语法，如 `I`、`V`、`[Ljava/lang/String;`）
  - `static TypeDescriptor fromClass(Class<?> type)`
  - `Class<?> resolve(ClassLoader loader) throws ClassNotFoundException`（使用 `Class.forName(..., false, loader)`，不主动初始化）
- `MemberKind`：`METHOD`、`FIELD`、`CONSTRUCTOR`
- `MemberDescriptor`
  - `static MemberDescriptor method(String owner, String name, String returnType, String... parameters)`
  - `static MemberDescriptor field(String owner, String name, String type)`
  - `static MemberDescriptor constructor(String owner, String... parameters)`
  - `static MemberDescriptor from(Member member)`
  - `String signature()`、`MemberKind kind()`、`String owner()`、`String name()`、`List<TypeDescriptor> parameters()`、`TypeDescriptor type()`

### 2.3 查询与策略：`io.github.jichuo1.meguviz.ref.api.query`

> 注意真实枚举命名：类继承范围枚举为 `Hierarchy`（不是 `LookupScope`），匹配选择枚举为 `Selection`（不是 `MatchPolicy`）。

- `QuerySpec`（`RULE_VERSION = "mvx-ref-1"`，`SCHEMA_MAJOR = 1`）
  - `static QuerySpec exact(MemberDescriptor descriptor)`（可移植精确查询，默认 `Selection.EXACT`）
  - `static QuerySpec method(Class<?> owner, String name, Class<?>... parameters)`（runtime-only，默认 `UNIQUE` + `DECLARED`）
  - `static QuerySpec field(Class<?> owner, String name)`（runtime-only，默认 `UNIQUE` + `DECLARED`）
  - `static QuerySpec constructor(Class<?> owner, Class<?>... parameters)`（runtime-only，默认 `UNIQUE` + `DECLARED`）
  - `static Builder builder(Class<?> owner, MemberKind kind)` / `static Builder builder(String owner, MemberKind kind)`
  - `Builder` 配置项：
    - `name(String)`、`parameters(Class<?>...)`、`arguments(CompatibilityPolicy, Object...)`、`returns(Class<?>)`
    - `hierarchy(Hierarchy)`、`selection(Selection)`
    - `staticOnly(boolean)`、`bridge(boolean)`、`synthetic(boolean)`、`parameterCount(int)`
    - `candidateBudget(int)`（默认 `16384`）、`resultLimit(int)`（默认 `4096`）、`depthLimit(int)`（默认 `256`）、`provider(String id)`
- `AccessPolicy`：`PRESERVE_JAVA_ACCESS`、`TRY_SUPPRESS_JAVA_CHECKS`
- `Hierarchy`：`DECLARED`（仅声明类）、`SUPERCLASSES`（当前类沿父类逐级向上至 `Object`，不枚举接口 DAG）、`JAVA_PUBLIC`（公开成员语义）
- `Selection`：`EXACT`、`FIRST`、`UNIQUE`、`ALL`
- `CompatibilityPolicy`：
  - `LEGACY_BIL`：严格装箱匹配，`null` 仅匹配引用类型，不做原始类型拓宽
  - `JAVA_INVOCATION`：允许反射允许的原始类型拓宽（如 `Integer` -> `long`/`float`/`double`），禁止窄化与 `boolean` 混用

### 2.4 调用计划与结果：`invoke`、`result` 与 `evidence`

- `MethodPlan`：`Object invoke(Object receiver, Object[] arguments)`、`Object call(Object receiver, Object... arguments)`、`MemberDescriptor descriptor()`
- `FieldPlan`：`Object get(Object receiver)`、`void set(Object receiver, Object value)`、`MemberDescriptor descriptor()`
- `ConstructorPlan`：`Object newInstance(Object[] arguments)`、`Object create(Object... arguments)`、`MemberDescriptor descriptor()`
- `StalePlanException`（继承 `IllegalStateException`）：作用域已关闭或 epoch 已推进时抛出
- `ResolutionResult`：`ResolutionStatus status()`、`List<ResolvedMember> members()`、`ResolutionReceipt receipt()`、`ResolvedMember requireSingle()`
- `RawResolution`：`ResolutionStatus status()`、`List<Member> members()`、`ResolutionReceipt receipt()`
- `ResolutionStatus`：`RESOLVED`、`ALL_MATCHES`、`AMBIGUOUS`、`NOT_FOUND_IN_VISIBLE_UNIVERSE`、`UNAVAILABLE`、`PARTIAL`、`CANCELLED`、`ACCESS_DENIED`、`STALE`
- `ResolutionReceipt`：`querySummary()`、`selection()`、`status()`、`coverage()`、`descriptors()`、`reason()`、`causeType()`、`interruptionOrigin()`
  - `InterruptionOrigin`：`UNKNOWN`、`SINGLE_FLIGHT_WAITER`

### 2.5 诊断、持久化与 Android：`ref-diagnostics` / `ref-android`

- `CodeIdentities.hashFiles(Map<String, Path> files, String loadingConfiguration, String ruleVersion)`（生成 `CodeIdentity.Strength.STRONG`）
- `FileReceiptStore(Path directory, int capacity)` / `MemoryReceiptStore`（实现 `ReceiptStore`）
- `PersistentResolutions.restore(ReceiptStore store, String key, CodeIdentity current, AccessPolicy policy, ClassLoader loader, CodeScope scope)`
  - 返回 `LoadResult`（`Status`：`REBOUND` / `MISS` / `INVALID_RECORD` / `STALE_IDENTITY` / `POLICY_MISMATCH` / `IO_FAILURE` / `UNAVAILABLE` / `CANCELLED`）
- `AndroidEnvironment.capture(Context context)`：复制 API、targetSdk、APK/split 路径与支持 ABI 列表，不持有 `Context` 或 `ClassLoader`

## 3. 与家族其他库的配合边界

- **与 `MeguvizHook` 配合**：在模块或插件中用反射定位方法/构造器并安装 Hook 时，直接引入 `io.github.jichuo1.meguviz.hook:hook-ref-bridge:1.2.0`（它以 `MeguvizRefMemberResolver` 与 `MeguvizRefResolverSession` 对接 `CodeScope`），不要自己重造一套解析桥。
- **与 `DexMeguviz` 配合**：`MeguvizRef` 只在已加载类的 Java 可见范围内做反射查询，不做字节码或全 DEX 扫描；若需要按字符串常量、调用关系先在未加载 DEX 中筛选候选方法再绑定，转交 `dexmeguviz` 或 `hook-dex-bridge`。
