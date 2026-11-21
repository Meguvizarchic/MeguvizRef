# 核心 API 与行为约定

MeguvizRef 编译为 Java 17 字节码，零第三方生产依赖；Kotlin 消费方通过正常 Java 互操作即可使用。公开签名基线与兼容性检查见[版本与兼容性](version-policy.md)。

## 1. 描述符与查询构造

- `TypeDescriptor.of` 会校验 JVM 类型语法、`void` 合法位置、最多 255 维数组以及长度预算，且解析类型时不主动初始化类。
- `MemberDescriptor` 保存真实声明类 `owner`、成员种类、名称、完整参数列表及返回/字段类型。
- `QuerySpec.exact(MemberDescriptor)` 是可跨进程持久化的精确描述查询；`QuerySpec.builder(Class, MemberKind)` 及各类接受 `Class` 或实参的快捷入口仅用于当前运行时，缓存按 `Class` 对象引用隔离，不会把不同加载器下的同名类合并。实参入口仅记录参数运行时 `Class`（或 `null`），不保留接收者或参数对象值。
- `QuerySpec.method(owner, name, parameters...)`、`QuerySpec.field(owner, name)` 和 `QuerySpec.constructor(owner, parameters...)`（1.2.0 新增）分别等价于对应 `builder` 加上 `name` / `parameters` 后调用 `build()`：默认采用 `Selection.UNIQUE` 与 `Hierarchy.DECLARED`，按参数 `Class` 身份精确匹配，不附加 `bridge` / `synthetic` 等额外过滤。

### 查询复用与类加载器持有边界

构建完成的 `QuerySpec` 不可变，可安全复用或跨线程传递；它只代表查询条件，不是作用域令牌，也不锁定任何解析结果。

- 携带 `runtimeOwner`、参数/返回 `Class` 或实参类型的查询会强引用这些 `Class`；若调用方长期持有查询对象，会延长对应定义加载器的生命周期，关闭 `CodeScope` 不会清除调用方自己持有的查询。
- `QuerySpec.builder(Class, ...)` 直接使用传入的 `Class`，即使作用域配置了另一个加载器也是如此。`scope.usesLoader(loader)` 只核对作用域自身的加载器，不负责证明该 `Class` 由哪个加载器加载。
- 需要严格核对时，应把预期加载器实际加载得到的 owner 与 `query.runtimeOwner()` 按引用比较；直接用 `getClassLoader() == loader` 会错误排除正常的父加载器委派。代码更新或加载器切换后，应重新确认条件并按需重建查询。

## 2. 类加载、查询范围与匹配策略

### 精确解析与类型加载隔离

精确描述符优先走公开精确反射入口。目标类经作用域加载器加载后，参数及返回/字段类型优先使用该声明类的定义加载器解析，保证类型身份与声明处一致，避免子加载器遮蔽同名类型。

- 若读取定义加载器或使用它解析类型被安全管理器拒绝，则自动回退到作用域加载器路径，并继续用反射得到的实际 `Class` 身份校验；权限受限且存在同名遮蔽时仍可能保守失败。
- 协变返回或 bridge 方法导致返回类型表面不一致时，在预算内回退到声明域验证完整签名。
- 所有类加载均采用 `Class.forName(name, false, loader)`，查询不会主动触发静态初始化。
- 同一作用域（同一 epoch）从第二次查找起，owner 与类型的成功加载结果按“发起加载器引用 + 名字”缓存（1.2.0 起）：命中效果与对同一加载器重复调用 `Class.forName` 一致，不同加载器互不串用；加载失败不缓存，条目数不超过作用域容量，随 `advanceCodeEpoch` 与 `close` 清空。缓存命中后不再重复触发 `Class.forName` 内部的安全管理器检查（同一加载器与名称的权限仅首次检查），`getClassLoader()` 检查及回退逻辑不变。
- 不区分“成员不存在”与“被系统隐藏”，统一返回 `UNAVAILABLE`（`not_found_or_filtered`），不把未覆盖的隐藏域当成成员一定不存在。

### 继承层级与选择模式

| 维度 | 枚举值 | 行为约定 |
| --- | --- | --- |
| 查询层级 `Hierarchy` | `DECLARED` | 仅搜索目标类自身声明成员；构造器不继承，只允许 `DECLARED` |
|  | `SUPERCLASSES` | 按当前类、父类直至 `Object` 逐级搜索，不枚举接口继承图 |
|  | `JAVA_PUBLIC` | 使用 Java 公开成员查询语义（等同 `getMethods` / `getFields` 可见范围） |
| 匹配模式 `Selection` | `UNIQUE` | 默认值；要求完整可见域内仅存在唯一匹配，发现两个不同真实成员即直接判定歧义 |
|  | `FIRST` | 按公开反射枚举顺序找到首项即停；即使返回 `RESOLVED`，也不代表覆盖完整或唯一 |
|  | `ALL` | 收集所有匹配成员；达到 `resultLimit` 时保守标为 `PARTIAL` |
|  | `EXACT` | 由 `QuerySpec.exact` 使用，按完整描述符精确匹配 |
| 参数兼容 `CompatibilityPolicy` | `LEGACY_BIL` | 严格装箱匹配，`null` 仅匹配引用类型，不做原始类型拓宽 |
|  | `JAVA_INVOCATION` | 允许反射支持的原始类型拓宽，禁止窄化与 `boolean` 混用；不模拟编译器重载决议，也不隐式打包变长参数 |

链接失败、预算耗尽、线程取消或 Provider 覆盖不完整，均不会提升为“完整未找到”结论。`ResolutionResult.requireSingle()` 仅接受 `RESOLVED` 且数量为 1，不会吞掉目标返回值 `null` 或目标方法异常。

关于 Provider 覆盖：Provider 声明的 `complete = true` 需经可见域独立重放核实；`FIRST` 在覆盖证明后选用重放顺序的第一项，而不是 Provider 候选列表顺序（1.0.0 版本曾可能按 Provider 顺序返回不同成员，升级时请留意）。

## 3. 缓存机制与容量限制

- **结构查询成员表复用**：结构筛选按 `name` / `static` / `arity` / 标志位先短路，再建立一次参数快照（私有数组不通过 getter 泄露）。同一作用域内，结构查询按 `(Class 身份, 成员种类, public/declared)` 复用首次枚举得到的成员表并保持枚举顺序；`LinkageError` / `SecurityException` 枚举失败不缓存，成员表随 `advanceCodeEpoch` 与 `close` 清空。若首次枚举之后才注册 Android hidden API 豁免或类结构发生改变，需推进 epoch 或新建作用域。Provider 候选域与覆盖复核继续直接枚举。`parameterReads` 仅统计成功构建的方法或构造器 schema，不包含失败读取、独立复核、精确路径直接反射以及 VM 内部分配。
- **查询缓存与无锁最近命中**：精确查询不创建全类 schema；schema 缓存有界。查询缓存仅保存完整且稳定的正向结果，没有负缓存。命中最近使用的一项时不加锁直接返回，同时保持 LRU 顺序与 `cacheHits` 计数。
- **容量 4096 约束**：默认容量 4096 同时约束查询缓存、schema 缓存、成员表缓存保留的 `Member` 引用总数（空表计 1，单表超容时只用一次不保留）以及当前存活的绑定数量。前三类按 LRU 淘汰；已发布的 binding 不自动驱逐，新成员超出 binding 容量时返回 `PARTIAL`（`binding_capacity`），既有计划仍可正常调用。长期运行场景应按需设置容量或定期重建作用域。
- **取消查询的容量边界**：查询被取消时，若部分匹配所需新 binding 超出容量，保留 `CANCELLED` 与原中断原因且不创建 binding；容量充足时保留部分结果行为。取消回执的覆盖率始终为不完整，不能作为完整查询结论。

## 4. 调用计划、访问策略与无锁计数

- **访问策略**：作用域 `AccessPolicy` 在打开时固定，默认为 `PRESERVE_JAVA_ACCESS`；`CodeScope.open(loader, policy)`（1.2.0 起）使用默认容量 4096。选择 `TRY_SUPPRESS_JAVA_CHECKS` 时仅调用公开的 `setAccessible(true)`，被拒时抛出保留原始 cause 的 `IllegalAccessException`，不承诺绕过 Java 模块封装或 Android hidden API。
- **调用计划分离**：`MethodPlan`、`FieldPlan` 与 `ConstructorPlan` 各自独立且不持有接收者实例，调用时完整保留反射参数转换、虚方法分派、类初始化语义及 `InvocationTargetException`；进入目标方法后不会切换候选或重试。`MethodPlan.call(receiver, args...)` 与 `ConstructorPlan.create(args...)`（1.2.0 起）为可变参数快捷写法，数组约定与 `Method.invoke` 完全一致。`bindMethod/bindField/bindConstructor(QuerySpec)` 等价于对 `resolve(query).requireSingle()` 绑定，非唯一结果抛出 `ResolutionException`。
- **无锁并发计数与分段**：调用计划执行不进入作用域锁：先对照最新的绑定快照校验令牌的 `identity`、`epoch`、槽位索引与描述符，再原子增加在途计数。平时只更新单个原子计数字；当计划调用检测到 CAS 连续竞争后，作用域会在锁内一次性创建按线程 ID 分配、相隔 128 字节填充的计数单元数组（1.2.0 起），之后调用直接计入本线程所属单元，解析请求仍计入基础计数字。`advanceCodeEpoch` 前取得旧快照的调用仍可正常完成，之后开始的调用抛出 `StalePlanException`。`diagnostics().get("inFlight")` 返回计数字与各单元之和，无并发出入时为精确值。`identity()` 为进程内唯一 UUID 格式字符串，并非不可预测的安全凭证。
- **导出原始成员**：`exportRaw(token)` 导出原始 `Member` 后由调用方自行管理生命周期，关闭作用域不会撤销已导出的引用，且可能延长类加载器存活时间。`resolveRaw(query)`（1.2.0 起）是批量导出入口：执行相同的查找与状态判定，但不创建 binding、不写入查询缓存（不会触发 `binding_capacity`），并在返回前按访问策略逐一调用 `prepareAccess`，`TRY_SUPPRESS_JAVA_CHECKS` 下首个被拒成员会抛出 `IllegalAccessException`；它与 `resolve` 使用独立的单飞去重键并计入在途排空。

## 5. 作用域生命周期与单飞中断识别

### 状态转换与关闭排空

- `close()` 按 `OPEN → RETIRING → CLOSED` 推进：先在锁内原子置位关闭标志（若有分段单元，则先置位基础字再依次置位每个单元），确保在任一调用被拒绝之后才开始的调用必定也被拒绝；同时清空缓存、槽位和加载器引用。
- 在关闭标志置位前已进入的解析与计划调用会正常完成并参与排空，之后的新调用直接被拒绝，被拒绝方不参与计数也不执行 Provider 清理。
- `close()` 不阻塞等待正在执行的目标方法；若没有任何在途工作，由 `close()` 调用线程同步关闭已安装的 `ProviderRegistry`（失败原样抛出）。若存在在途工作（包括分段单元上的工作），则通过排空交接计数把 Provider 关闭交给最后一个结束的在途释放者：排空期间非 `Error` 失败记入 `closeFailureType()`（不掩盖目标异常，并继续关闭其余 Provider），`Error` 正常传播。清理过程会保留已观察到的线程中断标志。重复调用 `close()` 幂等。
- 需知：`CLOSED` 状态和空 `closeFailureType()` 本身不能证明锁外 Provider 清理已经结束；需要确认清理完毕的调用方应自行协调在途工作并等待实际执行清理的一方返回。

### 代次推进与一致性检查

- `isCurrent(loader, identity, epoch)`（1.2.0 起）在单次加锁内求值 `usesLoader(loader) && identity().equals(identity) && codeEpoch() == epoch`（`null` identity 恒不匹配），仅校验作用域自身状态，不替代外部加载器与业务代次校验。
- `advanceCodeEpoch(reason)` 会清空本进程缓存与当前绑定并使旧令牌失效（`Long.MAX_VALUE` 后轮换新 `identity` 并从 0 重新计数）；同时会使强代码身份指纹失效，已安装 Provider 的场景需新建作用域并提供新 `CodeIdentity`。作用域不管理 UI 生命周期。

### 单飞去重与 `interruptionOrigin()` 判定

- 单线程调用不开启任何后台线程。单飞去重（single-flight）对同一未命中键共享解析，等待超时为 `2000 ms`，并发在途查询上限为 `min(capacity, 256)`。同线程重入与跨线程等待环直接返回不完整结果；单个等待者中断不取消执行者。Provider 发现与覆盖复核结束后若观察到线程中断标志，即使结果为空也会返回 `CANCELLED`，不将其升格为完整未找到。
- `result.receipt().interruptionOrigin()`（1.2.0 起）：仅当本次 `CodeScope.resolve` 作为其他线程单飞请求的等待者，且在 `Future.get` 期间因中断未取得结果时返回 `SINGLE_FLIGHT_WAITER`；其余结果（包括执行者中断、进入前已中断、反序列化或手工构造回执）一律返回 `UNKNOWN`。它仅说明本次调用未亲自执行该查询的执行者工作，不保证共享工作成功或调用方此前无副作用。
- 桥接方仍须独立检查作用域和会话状态，遇到 `UNKNOWN` 时保守处理。1.0.0 版本不包含此方法，不能根据 `reason` 字符串或异常类名反推来源；跨版本场景应要求至少 1.2.0 或显式探测能力。

### Android R8 压缩保留示例

若 Android 消费方通过反射按方法名探测 `ResolutionReceipt.interruptionOrigin()` 及其枚举返回值，需要在消费方的 R8 规则中保留对应成员（本例假设 `ResolutionReceipt` 类本身已被正常引用；若还按类名动态加载，需一并保留相关类名）：

```proguard
-keepclassmembers class io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt {
    public io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt$InterruptionOrigin interruptionOrigin();
}
-keep enum io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt$InterruptionOrigin { *; }
```

其他历史单飞 `reason`（如超时、容量、取消或递归消息）仅作诊断展示，当 Provider 自身抛出同类型或同消息异常时外层也会给出相同 `reason`，不能据此反向推断执行路径。

[返回文档目录](../README.md)
