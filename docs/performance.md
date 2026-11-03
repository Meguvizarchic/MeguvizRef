# 性能说明（1.1.0 开发版）

本页记录 1.1.0 开发周期的性能改动、依据、主机测量结果和后续方向。所有数据来自主机 JDK 17 x86-64，**不是 ART 或设备上的证据**；Android 上的收益需要设备基准另行确认。

## 运行基准

基准位于 `benchmark-jvm/`，只在显式开启时纳入构建，不发布，也不参与 `verifyHost`：

```sh
bash gradlew -Pmeguviz.benchmarks=true :benchmark-jvm:jmh \
  -Pjmh.include='InvokeBenchmark|ResolveBenchmark' \
  "-Pjmh.args=-f 2 -wi 3 -i 5 -w 1s -r 1s -prof gc"
```

结果写入 `benchmark-jvm/build/reports/jmh/results.json`。`-prof gc` 的 `gc.alloc.rate.norm` 即每次操作分配字节数（B/op），用法见 JMH 的 [Profilers 示例](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_35_Profilers.java)。

| 基准类 | 内容 |
|---|---|
| `InvokeBenchmark` | MethodPlan/FieldPlan/ConstructorPlan 与直接反射对比，含槽位号大于 127 的计划和 4 线程共享计划 |
| `ResolveBenchmark` | 作用域打开关闭；精确/结构查询的缓存命中（含 4 线程）与全新作用域未命中；155 个方法的 owner 上同一作用域内 8 个不同查询；桥接式 ALL 查询加逐个 `exportRaw` |
| `DescriptorBenchmark` | schema 快照、`MemberDescriptor.from`、描述符哈希、`summary()` 与桥接式查询构造 |
| `RawResolveBenchmark` | 与桥接式导出对应的 `resolveRaw` 版本（1.1.0 新 API） |

## 改动与依据

### 计划调用：无锁准入

1.0.0 的每次计划调用进入两次作用域监视器（准入和释放），并用 `HashMap<Long, Slot>` 查槽位，槽位号超过 127 时每次调用都装箱。JDK 15 起偏向锁默认关闭（[JEP 374](https://openjdk.org/jeps/374)），无竞争的监视器也需要原子操作；多线程共享同一作用域时监视器竞争使单次调用升到约 1 µs。

现在调用路径不进入监视器：

1. 令牌对一个 volatile 绑定快照校验 identity、epoch、按下标寻址的槽位和描述符（先比较引用，同一槽位的令牌共享描述符实例）。
2. 在途计数与关闭标志放在同一个原子整数里：只有关闭位未置位时才 CAS 计数加一。
3. `close` 在锁内用一次 `getAndAdd` 置位关闭位，这是唯一的线性化点。之前准入的调用已计入并参与排空；之后的调用看到关闭位直接拒绝，不修改计数。

没有采用"先自增再检查"：被拒绝的调用方可能成为最后一个释放者，从而在别的线程里执行 Provider 关闭，并让 `close()` 在没有真实在途工作时也不同步关闭 Provider。压力测试能捕获这种实现以及"非原子读取计数"的实现。该推理依赖 volatile 与原子操作的全局同步顺序（[JLS §17.4.4](https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html#jls-17.4.4)）。也测过把计数放到单独缓存行：4 线程结果差异在误差内（264±77 对 249±60 ns/op），因此未采用，避免每个作用域多分配约 150 字节。

### 作用域构造

`UUID.randomUUID()` 每次都经过共享的 `SecureRandom`（见 [JDK 17 UUID 源码](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/util/UUID.java)）。identity 改为"每次类加载一个随机前缀 + 递增序号"，仍是 UUID 文本格式，进程内唯一。identity 从来不是安全凭证：`ResolvedMember` 有公开构造器，代码注释也说明令牌不是能力凭证。`ReflectionResolver` 与 `QueryCoordinator` 改为首次未命中时创建。

### 结构查询：作用域内成员表

HotSpot 的 `getDeclaredMethods()` 每次返回全部 Method 的新副本（[JDK 17 `Class.copyMethods`](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/lang/Class.java)）；ART 的 `getDeclaredMethodsUnchecked` 每次按隐藏 API 规则过滤并分配新的 Method 镜像（[AOSP java_lang_Class.cc](https://android.googlesource.com/platform/art/+/refs/heads/main/runtime/native/java_lang_Class.cc)）。同一作用域内对同一 owner 的多次结构查询因此重复复制整张表。

- `ReflectionResolver` 按 (Class 身份, 成员种类, public/declared) 缓存结构查询的成员表，保持首次枚举顺序，`FIRST` 不变。
- 按表 LRU 淘汰，保留的 Member 引用总数不超过作用域容量（空表计 1）；超过容量的单表只用一次。
- 枚举抛出 `LinkageError`/`SecurityException` 时不缓存，下次重新枚举。
- epoch 推进与 close 时清空。
- Provider 候选域与 `ProviderCoverageVerifier` 继续直接枚举，保持独立复核。

Spring 的 `ReflectionUtils` 也缓存 `getDeclaredMethods()` 结果以避免新建 Method 实例（[源码](https://github.com/spring-projects/spring-framework/blob/main/spring-core/src/main/java/org/springframework/util/ReflectionUtils.java)），但它是进程级软引用缓存。这里限定在作用域内，以保留 close/epoch 释放 loader 的合同。代价：首次枚举之后才生效的隐藏 API 豁免或类结构变化不会反映到已缓存的表，调用方应推进 epoch 或换作用域，这与"代码变化后推进 epoch"的既有约定一致。

去掉了对每个枚举成员的 `visited` 集合。遍历域内不存在相等的 Member：Member 相等要求声明类相同，而一个 class 文件不能声明同名同描述符的两个方法或字段（[JVMS §4.5、§4.6](https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-4.6)）；`JAVA_PUBLIC` 只调用一次 `getMethods()`/`getFields()`，其结果已去重。只对已接受的匹配从第二个起保留防御性去重。`candidateChecks` 在本地累加、结束时发布一次。

### 描述符与查询

- `TypeDescriptor.of` 原位校验，不再 substring；`MemberDescriptor.from` 与 `MethodSchema` 每个类型只校验一次。合同测试以 1.0.0 实现的逐字副本为 oracle 做差分。
- `MemberDescriptor.hashCode()` 与 `QuerySpec.summary()` 惰性记忆。

### 缓存命中

查询缓存仍是锁内 access-order LRU。新增一个 volatile 的"最近使用项"视图：由锁内命中设置，写入新项和 clear/epoch/close 时清空，所以非空时必为 LRU 尾部，直接返回不改变淘汰顺序。线程已中断时走原锁路径。

### 面向桥接的 API

- `resolveRaw(QuerySpec)`：与 `resolve` 相同的状态和回执，直接返回原始成员，不创建 binding，长会话不会因导出累计触发 `binding_capacity`。
- `isCurrent(loader, identity, epoch)`：一次加锁完成三项检查。

### 精确描述符类型缓存：加载器身份审查（第 2 轮）

精确描述符查询每次未命中都对 owner 调用 `Class.forName(owner, false, 作用域 loader)`，并对每个引用类型的参数和返回/字段类型调用 `Class.forName(name, false, L)`。Provider 候选复核和遮蔽声明类的精确复核走同一路径。实现缓存之前，先对照[核心约定](api/core-contracts.md)核对加载器身份：

1. 约定：owner 经作用域 loader 加载；类型优先用 owner 的定义加载器 L 解析，使类型身份与实际声明一致；读取 L 或用 L 解析被安全管理器拒绝时回退到作用域 loader，再用反射得到的实际 `Class` 校验；不初始化；没有负缓存。
2. 键：（发起加载器的引用身份，名字）。先按加载器身份选子表，再按名字查找，所以一个加载器永远拿不到经另一个加载器查到的 `Class`；回退路径以作用域 loader 为键，和定义加载器的条目分开。owner 的二进制名不能含 `;` 或 `[`，类型条目用描述符字符串（`L...;`、`[...`），两类名字不会相同。
3. 命中等价于重新调用：HotSpot 在 `loadClass` 返回由其他加载器定义的类后，把 L 记为该类的发起加载器（JDK 17 [`SystemDictionary::load_instance_class`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/classfile/systemDictionary.cpp) 中的 `update_dictionary`），之后同一 L 的查找先命中 L 的字典；ART 的 [`ClassLinker::FindClass`](https://android.googlesource.com/platform/art/+/refs/heads/main/runtime/class_linker.cc) 先查 L 的类表，`loadClass` 成功后把结果插入 L 的类表，竞态时返回先记录的类。JVMS [§5.3](https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-5.html#jvms-5.3) 规定 JVM 记录发起加载器。因此缓存首次成功的结果，与之后对同一 L 再次调用得到同一个类。
4. 只缓存成功：`ClassNotFoundException`、`LinkageError` 和 `SecurityException` 都不进缓存，下次重新查找。
5. 生命周期与上限：缓存属于 resolver，随 epoch 推进和 close 丢弃，与成员表和 schema 缓存相同；条目数不超过作用域容量。打开期间强持有这些 `Class`，因而也延长其定义加载器的生命周期，直到 epoch 推进或 close。
6. 安全管理器：命中时不再执行 `Class.forName` 内部的权限检查，同一（加载器，名字）的决定只在首次查找时评估。SecurityManager 自 JDK 17 起弃用并计划移除（[JEP 411](https://openjdk.org/jeps/411)），Android 没有它；每次查询仍调用 `getClassLoader()` 并执行其权限检查，回退行为不变。`LoaderSecurityContractTest` 的四项用例照常通过。
7. 不增加单次解析的成本：resolver 的第一次查找不使用缓存、不分配缓存结构，同一作用域出现第二次查找后才开始缓存。

结论：可以做，键必须包含发起加载器身份。合同测试用一个子优先加载器构造"两个 owner 的定义加载器不同、参数类型同名但身份不同"的场景，在同一作用域内交替查询，并检查无负缓存、容量上限和随 epoch/close 丢弃；变异检查确认"只按名字做键"和"不受容量约束"两种变体会失败。

## MethodHandle / LambdaMetafactory 调研（未采用）

- JDK 18 起核心反射本身改用 method handle 实现（[JEP 416](https://openjdk.org/jeps/416)）。JDK 17 的 `Method.invoke` 前若干次走本地实现，之后生成字节码访问器。上表中直接 `Method.invoke` 约 3.8 ns，计划调用剩余开销主要来自准入，不在反射调用本身。
- 语义不同。`MethodHandle.invokeWithArguments` 原样抛出目标异常，不包装为 `InvocationTargetException`；类型不符时抛 `ClassCastException` 或 `WrongMethodTypeException`，而不是 `IllegalArgumentException`（[Android MethodHandle 文档](https://developer.android.com/reference/java/lang/invoke/MethodHandle)）。访问检查发生在查找时而不是每次调用，调用者敏感方法看到的调用者也不同。
- Android 上 `MethodHandle.invoke`/`invokeExact` 需要 minSdk 26；lambda 由 D8/R8 在构建期脱糖（[Java 8 支持说明](https://developer.android.com/studio/write/java8-support)），运行时不能依赖 `LambdaMetafactory`。

结论：默认路径保持 core reflection。将来若提供可选的 method handle 计划，必须另起名称，并用差分测试覆盖异常、转换和访问矩阵。

## 测量结果

环境：OpenJDK 17.0.2（64-Bit Server VM 17.0.2+8-86），Intel Xeon Platinum 8488C，8 vCPU 共享沙箱；JMH 1.37，`-f 2 -wi 3 -i 5 -w 1s -r 1s -prof gc`。

- 优化前：提交 `fb9d258` 的库源码（即 d8cc690）加同一套基准源码。
- 优化后：提交 `58dc759`。
- `±` 为 JMH 给出的 99.9% 置信区间。

`bridgeAll*` 三行在完整运行中受宿主负载干扰（误差约 ±1–3 µs），表中是单独重跑的结果。

| 基准 | 线程 | 优化前 ns/op | 优化后 ns/op | 优化前 B/op | 优化后 B/op |
|---|---|---|---|---|---|
| `InvokeBenchmark.directInvoke`（参考） | 1 | 3.82 ± 0.11 | 3.80 ± 0.09 | 0 | 0 |
| `InvokeBenchmark.planInvoke` | 1 | 38.7 ± 0.29 | 16.7 ± 0.21 | 0 | 0 |
| `InvokeBenchmark.planInvokeHighSlot` | 1 | 40.7 ± 3.69 | 16.9 ± 0.45 | 24 | 0 |
| `InvokeBenchmark.planFieldGet` | 1 | 38.7 ± 0.28 | 16.7 ± 0.98 | 0 | 0 |
| `InvokeBenchmark.planNewInstance` | 1 | 40.1 ± 2.39 | 18.8 ± 1.93 | 56 | 56 |
| `InvokeBenchmark.directInvokeThreads4`（参考） | 4 | 98.9 ± 347.5 | 6.78 ± 1.46 | 0 | 0 |
| `InvokeBenchmark.planInvokeThreads4` | 4 | 995.1 ± 226.9 | 229.3 ± 58.9 | 0 | 0 |
| `ResolveBenchmark.scopeOpenClose` | 1 | 293.2 ± 49.6 | 67.3 ± 0.49 | 752 | 376 |
| `ResolveBenchmark.exactHit` | 1 | 20.3 ± 1.70 | 9.31 ± 0.19 | 0 | 0 |
| `ResolveBenchmark.exactHitThreads4` | 4 | 477.4 ± 101.3 | 9.72 ± 0.47 | 0 | 0 |
| `ResolveBenchmark.structuralHit` | 1 | 19.8 ± 1.24 | 9.02 ± 0.06 | 0 | 0 |
| `ResolveBenchmark.exactMissFreshScope` | 1 | 1380.3 ± 115.5 | 869.0 ± 13.2 | 2728 | 2064 |
| `ResolveBenchmark.structuralMissFreshScope` | 1 | 6683.4 ± 105.9 | 4115.6 ± 50.5 | 24856 | 17000 |
| `ResolveBenchmark.inheritedMissFreshScope` | 1 | 7588.6 ± 1405.0 | 4082.1 ± 24.5 | 26040 | 18256 |
| `ResolveBenchmark.bridgeAllMissFreshScope` | 1 | 10037.2 ± 3061.3 | 4865.1 ± 126.1 | 28048 | 19144 |
| `ResolveBenchmark.bridgeAllExportFreshScope` | 1 | 8321.0 ± 164.5 | 4968.4 ± 88.7 | 28088 | 19176 |
| `RawResolveBenchmark.bridgeAllRawFreshScope` | 1 | - | 4652.9 ± 52.3 | - | 18576 |
| `ResolveBenchmark.structuralEightQueriesOneScope` | 1 | 53232.2 ± 2028.4 | 10882.0 ± 159.4 | 189203 | 27080 |
| `ResolveBenchmark.sessionEightQueriesExport` | 1 | 53278.9 ± 2956.6 | 11298.9 ± 122.4 | 189399 | 27592 |
| `RawResolveBenchmark.sessionEightQueriesRaw` | 1 | - | 10700.3 ± 270.9 | - | 25848 |
| `DescriptorBenchmark.methodSchemaOneString` | 1 | 396.4 ± 142.3 | 207.5 ± 9.38 | 1248 | 640 |
| `DescriptorBenchmark.methodSchemaThreeInts` | 1 | 319.1 ± 88.7 | 147.1 ± 21.4 | 872 | 392 |
| `DescriptorBenchmark.descriptorFromOneString` | 1 | 316.8 ± 6.77 | 211.9 ± 9.58 | 1216 | 584 |
| `DescriptorBenchmark.descriptorHashCode` | 1 | 18.1 ± 0.14 | 0.49 ± 0.02 | 40 | 0 |
| `DescriptorBenchmark.querySummary` | 1 | 52.1 ± 1.65 | 0.57 ± 0.01 | 192 | 0 |
| `DescriptorBenchmark.bridgeQueryBuild` | 1 | 171.4 ± 11.0 | 118.5 ± 9.49 | 640 | 360 |

解读：

- 4 线程共享同一计划时仍约 230 ns/op，开销来自所有线程更新同一个在途计数。直接 `Method.invoke` 没有共享写。
- 全新作用域的结构查询主要花在一次 `getDeclaredMethods()` 复制上（155 个方法约 17 KB）。成员表缓存只在同一作用域内复用，因此"每次解析新开作用域"的模式只受益于构造、描述符和 visited 的改动。
- `querySummary` 与 `descriptorHashCode` 的数字是同一实例重复调用的记忆效果；新建查询的首次调用仍需计算一次。

## 后续方向

1. 分段在途计数：在保持"被拒绝方不执行 Provider 关闭"的前提下降低多线程计划调用的共享写。需要新的排空交接协议和对应的压力测试。
2. 精确查询每次未命中都对参数和返回类型调用 `Class.forName`；可按 (定义加载器, 描述符) 做作用域内类型缓存，需先确认 loader 身份合同。
3. 用 `samples/android-consumer` 的设备基准在 ART 上验证准入与成员表缓存，补充设备证据。
4. 桥接采用 `resolveRaw` 与 `isCurrent`，在导出前按描述符过滤，并预先计算排序键。
5. single-flight 在无等待者时延迟创建 `CompletableFuture`；记忆覆盖范围字符串。

[返回文档目录](README.md)
