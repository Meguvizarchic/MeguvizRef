# 核心 API 与行为约定

实现为 Java17 字节码、零第三方生产依赖；Kotlin 消费者通过正常 Java 互操作使用。公开签名基线和兼容性检查见[版本与兼容性](version-policy.md)。

`TypeDescriptor.of` 校验 JVM 类型语法、void 位置、255 维数组和大小预算。`MemberDescriptor` 保存真实声明 owner、成员种类、名称、完整参数及返回/字段类型。`QuerySpec.exact` 为 portable 完整描述；`QuerySpec.builder(Class, kind)` 及 Class/实参便捷入口是 runtime-only，缓存按 Class 对象身份隔离，不将相同类名跨 loader 合并。实参入口只保存参数的 Class 或 null，不保留 receiver/对象值。

`QuerySpec.method(owner, name, parameters...)`、`QuerySpec.field(owner, name)` 和 `QuerySpec.constructor(owner, parameters...)`（1.1.0 起）分别等价于 `builder(owner, kind)` 加 `name`/`parameters` 后 `build()`：默认 `UNIQUE`、`DECLARED`，参数按 `Class` 身份精确匹配，不附加 bridge/synthetic 等其他过滤。

构建完成的 `QuerySpec` 不可变，可以显式复用和跨线程传递；它是查询条件，不是 scope token，也不固定解析结果。`CodeScope` 的缓存、binding 和 epoch 相互独立：推进 epoch 后必须重新解析并取得新 token，关闭后需使用新 scope；Provider 查询还需满足新作用域的代码身份要求。显式复用不替代调用方自己的 loader、epoch 或目标条件校验。

携带 `runtimeOwner`、参数/返回 `Class` 或实参类型的查询会强持有这些类；调用方长期保存查询时也可能延长其定义加载器的生命周期，scope 关闭不会清除调用方持有的查询。`QuerySpec.builder(Class, ...)` 使用传入的 Class，即使作用域配置了另一个 loader；`scope.usesLoader(loader)` 只核对作用域自身的 loader，不证明查询的 Class 由该 loader 选择。调用方可将通过预期 loader 实际加载得到的 owner 与查询的 `runtimeOwner()` 按引用比较；使用 `getClassLoader() == loader` 会错误排除正常的父加载器委派。对更新后的代码或其他 loader，应重新确认条件并在必要时重建查询。

精确描述符先走公开精确反射入口。owner 经作用域 loader 加载后，参数及返回/字段类型优先使用该 owner 的定义加载器解析；这与实际声明的类型身份一致，避免子加载器遮蔽同名类型造成误判。若读取定义加载器或使用它解析类型被安全管理器拒绝，则回退到原有作用域 loader 路径，并继续用反射实际 `Class` 身份校验；权限受限且类型被遮蔽时仍可能保守失败。协变返回/bridge 的返回类型不符时，在预算内回退到声明域验证完整签名。类加载使用 `Class.forName(name, false, loader)`，查询不会主动初始化。同一作用域（同一 epoch）内从第二次查找起，owner 与参数/返回/字段类型的成功加载结果按（发起加载器身份，名字）缓存（1.1.0 起）：命中与对同一加载器重复调用 `Class.forName` 得到同一个 `Class`，一个加载器不会得到经另一个加载器查到的类；失败不缓存，条目数不超过作用域容量，随 `advanceCodeEpoch` 和 `close` 丢弃。缓存命中时不再重复 `Class.forName` 内部的安全管理器检查，同一（加载器，名字）的权限决定只在首次查找时评估；`getClassLoader()` 的检查与上述回退不变。不存在与系统过滤统一为 `UNAVAILABLE/not_found_or_filtered`，不会构造物理不存在证明。

`DECLARED` 只遍历声明类；`SUPERCLASSES` 按当前类、父类直到 Object 逐级处理；`JAVA_PUBLIC` 使用 Java 公共成员查询语义。构造器不继承，不提供自定义 interface DAG 查询。

新查询默认 `UNIQUE`：只有完整可见域中的单个匹配才成功；两个实际验证的不同成员可直接证明歧义。`FIRST` 保留公开反射枚举顺序，找到即停，`RESOLVED` 并不表示 coverage 完整或唯一。`ALL` 输出达到 limit 时保守标 `PARTIAL`。链接失败、预算、取消、Provider 不完整都不能提升成完整负结论。`requireSingle()` 只接受 `RESOLVED`，不会吞目标返回 null 或目标异常。

Provider 查询的完整覆盖需独立重放可见域；`FIRST` 在覆盖得到证明后使用该重放的第一个匹配，而不是 provider 的候选排列顺序。已发布的 1.0.0 版本可能按 provider 顺序选出不同成员；升级时应核对实际使用的版本。

`CompatibilityPolicy.LEGACY_BIL` 使用严格装箱匹配，null 只接受引用类型；`JAVA_INVOCATION` 允许反射的 primitive widening，禁止 narrowing/boolean 混用。不实现 Java 编译器重载决议，不隐式打包 varargs。

结构筛选按 name/static/arity/flags 先短路，再建立一次参数快照。私有数组不会经 getter 外泄。精确查询不创建全类 schema；schema 缓存有界。query cache 只缓存完整且稳定的成功，默认没有负缓存；命中最近使用的一项时不加锁返回，LRU 顺序与 `cacheHits` 计数不变。

结构查询在作用域内按 (Class 身份, 成员种类, public/declared) 复用首次枚举得到的成员表，保持该次枚举的顺序，`FIRST` 与 `inspected` 不变。枚举失败（链接错误、安全拒绝）不缓存。成员表随 `advanceCodeEpoch` 和 `close` 清空；首次枚举之后才生效的 Android 隐藏 API 豁免或类结构变化不会反映到已缓存的表，应推进 epoch 或换作用域。Provider 候选域和覆盖复核仍直接枚举。遍历域内的 Member 互不相等（不同类各自的声明表，或单次 `getMethods`/`getFields`），结果仍按 Member 去重。`parameterReads` 计数的是成功创建的方法或构造器 schema，不包括创建失败时已发生的读取、独立覆盖验证或精确路径的直接反射读取，也不包含 VM 枚举内部分配；它不是全部元数据读取次数或时间收益。

默认容量 4096，同时约束 query/schema 缓存、成员表缓存保留的 Member 引用总数（空表计 1，单表超过容量时只用一次、不保留）和 live binding 数。query/schema/成员表按 LRU 淘汰，已经发布的 binding 不自动驱逐；新成员超出 live binding 容量返回 `PARTIAL/binding_capacity`，既有计划仍有效。长期作用域调用方需选择合适容量或显式换作用域，不能把无界成员集合放入本库。

已取消查询若部分匹配需要的新 binding 超出容量，保留 `CANCELLED` 和原中断原因，不创建 binding 或返回成员；容量充足时仍保留已有的部分成员行为。取消回执的 coverage 始终不完整，不能作为完整查询结果。

作用域 access policy 固定，默认不更改 accessible；`CodeScope.open(loader, policy)`（1.1.0 起）使用默认容量 4096。显式 `TRY_SUPPRESS_JAVA_CHECKS` 只使用公共 `setAccessible`；实际拒绝以 IllegalAccessException 保留 cause，不承诺绕过 Java 模块或 Android hidden API。

MethodPlan、FieldPlan、ConstructorPlan 分离，无 receiver 字段。调用保留反射 conversion、virtual dispatch、初始化、InvocationTargetException；目标进入后不切候选或重试。计划只持 CodeScope 和描述 token，Member 存于 scope slot。`MethodPlan.call(receiver, args...)` 和 `ConstructorPlan.create(args...)`（1.1.0 起）是 `invoke`/`newInstance` 的可变参数形式，数组约定与 `Method.invoke` 相同（单个 `Object[]` 实参即参数数组），转换和异常完全一致；原有方法签名与 Kotlin 源码含义不变。`bindMethod/bindField/bindConstructor(QuerySpec)` 等价于对 `resolve(query).requireSingle()` 绑定，非唯一结果抛 `ResolutionException`。

计划调用不进入作用域监视器：先用当前绑定快照校验令牌的 identity、epoch、槽位和描述符，再在作用域尚未开始关闭时原子计入在途数。在途数平时只有一个计数字；某次调用在它上面的 CAS 连续失败（观察到竞争）后，作用域在锁内创建一次按线程号分配、彼此相隔 128 字节的计数单元（1.1.0 起，每个作用域最多一次，之后的调用不加锁），此后的计划调用在本线程的单元计数，解析仍计入计数字。close 先关闭计数字再关闭各单元，单元上的调用先确认计数字尚未关闭，因此在任一调用被拒绝之后才开始的调用都会被拒绝。`advanceCodeEpoch` 之前已取得旧快照的调用仍可完成，等同于发生在 epoch 推进之前；之后开始的调用得到 `StalePlanException`。`diagnostics()` 的 `inFlight` 是计数字与各单元之和：没有调用开始或结束时是精确值，并发变化中读到的和与 `LongAdder.sum()` 一样不一定对应某一时刻。`identity()` 是进程内唯一的 UUID 格式文本（每次类加载一个随机前缀加递增序号），不保证不可预测；令牌不是安全凭证。

原始 `exportRaw` 是显式所有权外逸，调用方可缓存或交给 Hook，关闭不会撤销已导出 Member。`resolveRaw(query)`（1.1.0 起）是批量形式：状态、原因、覆盖和描述符与 `resolve` 相同，但直接返回原始 Member，不创建 binding、不写入查询缓存，因此不会出现 `binding_capacity`；已缓存的令牌结果会被复用。返回前按作用域访问策略逐个准备访问，`TRY_SUPPRESS_JAVA_CHECKS` 下第一个被拒绝的成员抛出 `IllegalAccessException`。它与 `resolve` 使用不同的 single-flight 键，同样计入在途排空；只有 `CodeScope` 能创建 `RawResolution`。

`close`: OPEN→RETIRING→CLOSED。同步线性化点（锁内原子置位关闭标志：没有计数单元时是一次置位，有单元时先置位计数字、再逐个置位单元）拒绝新调用并清理缓存、槽位及 loader；在它之前准入的解析和调用计入排空，之后的调用直接被拒绝，不计数，被拒绝的调用方也不会执行 Provider 关闭；已进入的方法持自己的局部 Member，可完成后释放。close 不等待在途工作排空，但没有在途工作时会同步执行 Provider 关闭，因而耗时取决于 Provider。有计数单元时，每个仍有在途工作的计数由其最后一个释放者交还一份排空份额，close 自己持一份，取得最后一份者关闭 Provider：若这些工作在 close 的锁内阶段已全部结束，由 close 同步关闭（失败照常从 close 抛出），否则由最后释放的在途调用关闭。重复 close 幂等。Provider 在全部在途解析/调用排空后关闭；排空时的非 `Error` Provider close 失败记录 `closeFailureType()`，不盖住目标异常，并继续关闭其余 Provider。`Error` 仍会传播，可能覆盖在途异常；不保证其余 Provider 继续关闭。没有在途工作时显式 close 的关闭失败原样抛出。清理过程中保留已观察到的线程中断标志，即使 provider 清除了它；未声明的 `InterruptedException` 同样保留原对象并恢复标志。

`CLOSED` 表示作用域不再接受工作，可能在锁外的 Provider 关闭完成前就被观察到；`closeFailureType()` 暂时为空也不证明清理已经完成。需要知道清理是否结束的调用方应自行协调在途工作，并等待实际执行同步关闭或最后一次释放的操作完成；单独等待已提前返回的 `close()` 不足以证明排空后的清理完成。

`isCurrent(loader, identity, epoch)`（1.1.0 起）在一次加锁内求值 `usesLoader(loader) && identity().equals(identity) && codeEpoch() == epoch`，null identity 不匹配；它只核对作用域自身，不替代调用方对查询 Class 与外部 epoch 的检查。

`advanceCodeEpoch(reason)` 清除本进程绑定/缓存并使旧 token 失效；Long.MAX_VALUE 换新 scope identity，再从 0 计数。code epoch 变更同时使当前强代码指纹失效，Provider 需要携带新内容身份的新 scope。作用域不管理 UI 生命周期。

单线程调用不启动任何工作线程。single-flight 在同键 miss 上共享工作，等待最多 2000ms、并发 flight 上限 min(capacity,256)。同线程重入和跨线程等待环返回明确不完整结果；一个等待者中断不取消拥有者，close 完成等待者而不抢占已进入 Provider/目标。provider/目标代码不在全局锁内执行。

Provider 发现返回后和覆盖验证完成后会检查当前线程的中断标志，包括候选集合或可见域为空的情况；已观察到中断时返回不完整的 `CANCELLED`，不将空集合提升为完整未找到。检查后的并发中断可能与结果返回竞态，调用方仍需处理自己的取消协议。

`result.receipt().interruptionOrigin()`：只有本次 `CodeScope.resolve` 作为其他线程的 single-flight 等待者，且其 `Future.get` 因中断未取得结果，才返回 `SINGLE_FLIGHT_WAITER`；其他结果一律为 `UNKNOWN`，包括执行者中断、进入查询前已中断和重建的回执。线程可能在进入 `get` 前已被中断，因此该标记不证明线程曾实际阻塞。已有的 `CANCELLED`、`wait_interrupted` 和中断标志行为保持不变。它只说明本次 Ref 查询未执行该 flight 的 owner 工作，不保证共享工作成功、作用域仍有效或调用者在进入 Ref 前没有副作用；例如，外层查询的 provider 可能已经执行，随后在嵌套查询中成为等待者。只有直接从本次 `CodeScope.resolve` 获取的回执适合用于这一判断，公开构造的回执不是安全凭证。

桥接方仍须独立检查作用域和会话状态，未知来源应保守处理。已发布的 1.0.0 没有此 API，不能根据 `reason`、异常类型名或线程标志推断来源；跨版本调用需明确要求包含该 API 的版本，或检测能力并在不可用时保守处理。

若 Android 消费方用反射按方法名读取该能力，压缩配置需保留反射访问的成员。例如，对 `ResolutionReceipt.class.getMethod("interruptionOrigin")` 加 `Enum.name()` 的探测，可在消费方的 R8 规则中加入：

```proguard
-keepclassmembers class io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt {
    public io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt$InterruptionOrigin interruptionOrigin();
}
-keep enum io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt$InterruptionOrigin { *; }
```

此例假定回执类本身被正常引用；如果还按原始类名加载或拼接外层类名，需按实际探测方式保留相关类名。应在自己的压缩制品中验证；缺少能力、反射失败或未知值仍须保守处理。

其他历史 single-flight `reason` 同样只是诊断：例如 provider 已开始执行后若抛出与协调器使用的超时、容量、取消或递归异常同类型（或同消息）的异常，现有外层捕获可能给出相同的 reason。不能据此推断当前调用是等待者、provider 未运行或 scope 一定失效；非等待者来源目前没有可用于放宽桥接策略的结构化证明。


[返回文档目录](../README.md)
