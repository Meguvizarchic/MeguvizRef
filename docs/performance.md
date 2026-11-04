# 性能说明（1.1.0 开发版）

本页记录 1.1.0 开发周期两轮性能改动的依据、验证方法、主机测量结果和后续方向。所有数据来自主机 JDK 17 x86-64，**不是 ART 或设备上的证据**；Android 上的收益需要设备基准另行确认。

- 第 1 轮：计划调用无锁准入、作用域构造、结构查询成员表、描述符与缓存命中、`resolveRaw`/`isCurrent`。
- 第 2 轮：多线程共享作用域时的分段准入、jcstress 与变异检查、未命中路径（single-flight、覆盖范围字符串、精确描述符类型缓存）和桥接安装批量。

## 运行基准、jcstress 与变异检查

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
| `BatchResolveBenchmark`（第 2 轮） | 桥接安装批量：`java.lang.String` 上 1 或 20 个不同名的描述符目标，每个目标构造一次桥接默认结构查询；一个作用域内 `resolve`+`exportRaw` 或 `resolveRaw`、每个目标一个作用域，以及完整精确描述符 |

第 2 轮在 `InvokeBenchmark` 中增加了 2/8 线程共享计划、每线程独立作用域的 4 线程参照和"已出现过竞争的作用域上的单线程调用"，在 `ResolveBenchmark` 中增加了引用类型参数的精确未命中，在 `DescriptorBenchmark` 中增加了单个引用类型的解析。

### jcstress

`stress-jvm/` 是可选的 [jcstress](https://github.com/openjdk/jcstress) 0.16 模块（Maven Central 上的最新版本），同样只在显式开启时纳入构建，不参与 `verifyHost` 和 CI：

```sh
# 约 1.5 分钟：单一 JVM 配置、不拆分各 actor 的编译模式
bash gradlew -Pmeguviz.jcstress=true :stress-jvm:jcstress "-Pjcstress.args=-m quick -jvmArgs -Xmx1g -sc false"
# 完整组合（多种 JVM 参数与编译模式，8 vCPU 上超过 40 分钟）
bash gradlew -Pmeguviz.jcstress=true :stress-jvm:jcstress "-Pjcstress.args=-m quick"
```

报告写入 `stress-jvm/build/reports/jcstress/index.html`。

| 测试 | 内容 | 禁止的结果 |
|---|---|---|
| `AdmissionStress.CloseWhileCallingBase` / `CloseWhileCallingCells` | 两个调用者与 close 竞争，分别在单字和已分段的作用域上 | Provider 未关闭或关闭多次、在目标执行时关闭、由被拒绝的调用者关闭、关闭后仍有目标进入、拒绝消息不同 |
| `AdmissionStress.ConcurrentClosers` | 两个 close 与一个调用者竞争 | 同上 |
| `AdmissionStress.NoAdmissionAfterRejection` | 调用者 1 被拒绝后发布标志，调用者 2 先读标志再调用 | 读到拒绝标志之后仍被接受 |
| `MemoStress.HitVersusClose` / `HitVersusEpoch` | 最近项视图命中与 close、`advanceCodeEpoch` 竞争 | close 返回后仍得到缓存结果；epoch 推进返回后仍得到旧 epoch 令牌 |
| `MemoStress.MemoStaysLruTail` | 容量 2 时一个线程命中、另一个线程写入并淘汰 | 视图非空却不是 LRU 尾部 |

### 变异检查

`tools/mutation_check.py` 把 ref-core 源码复制到 `build/mutation/<变体>/`，逐个应用一个"看起来合理但错误"的实现变体，再运行合同测试（默认）或 jcstress（`--jcstress`，需先执行 `bash gradlew -Pmeguviz.jcstress=true :stress-jvm:jcstressClasspath`）。未改动的对照必须通过，每个变体都必须失败：

```sh
python3 tools/mutation_check.py
python3 tools/mutation_check.py --jcstress --only cells-before-base,cell-skips-base-check,increment-then-check
```

结果见下文"第 2 轮验证"。

## 第 1 轮改动与依据

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

## 第 2 轮改动与依据

### 多线程计划调用：按竞争分段的准入计数

第 1 轮之后，每次计划调用仍对作用域的同一个原子整数做一次 CAS 加一和一次减一。多个线程共享一个作用域时，这个缓存行在核心之间来回迁移：基线在 1/2/4/8 线程下为 16.7/96/237/618 ns/op，而每个线程各用一个作用域时 4 线程约 21 ns/op。

做法参考 `java.util.concurrent.atomic.Striped64`（`LongAdder` 的基础）：没有竞争时只更新一个基础字，CAS 失败才视为竞争并创建一组带填充的计数单元，按线程散列到单元（[JDK 17 源码](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/java/util/concurrent/atomic/Striped64.java)，其注释说明数组中的原子量容易共享缓存行，所以单元需要填充）。准入还要求"关闭后拒绝、排空后恰好关闭一次 Provider"，因此在 Striped64 之上增加了关闭顺序和排空交接：

1. 基础字：低 30 位是在途数，第 30 位是 INFLATED，符号位是 CLOSING。无竞争的计划调用读一次基础字，在两个标志位都未置位时 CAS 加一；释放时减一并检查符号位，与第 1 轮的原子操作次数相同。
2. 分段：某次调用在基础字上的 CAS 连续失败两次后，在作用域锁内创建单元表，然后给基础字置 INFLATED。表长是不小于处理器数的 2 的幂（2 至 64），单元相隔 32 个 int（128 字节，即 HotSpot `@Contended` 的默认填充宽度 [`ContendedPaddingWidth`](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/share/runtime/globals.hpp)），首尾各留一段填充，8 核上约 1.3 KB。每个作用域最多创建一次，此后不再加锁。读到 INFLATED 且 CLOSING 未置位的调用按 `Thread.getId()` 选单元并 CAS 加一，冲突时探测下一个单元；解析仍在锁内计入基础字。
3. 关闭顺序：close 在锁内先对基础字、再依次对每个单元执行 `getAndAdd(CLOSING)`。单元上的调用以"刚读到的基础字未关闭"作为准入检查。若调用 A 被拒绝，A 一定已经看到某个字的 CLOSING，而基础字最先置位，所以在 A 被拒绝之后才开始的调用 B 读基础字时一定看到 CLOSING。因此不会出现"一个调用被拒绝之后另一个调用仍被接受"，与单字时"close 的一次置位是线性化点"等价；单元 CAS 落在基础字关闭之后的调用，线性化在它读基础字的时刻。依据是 volatile 与原子操作的全局同步顺序（[JLS §17.4.4](https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html#jls-17.4.4)）。C-SNZI 的叶子到达也按"看到根仍开放的时刻"线性化，见下文。
4. 排空交接：close 在置位前写入交接计数，初值为字数加一（基础字、全部单元和 close 自己），放在单元表前部的填充里，不增加作用域对象的大小。置位时计数为零的字由 close 代为交还份额；计数不为零的字，由其最后一个释放者（减一后看到"CLOSING 且计数为零"的那次）交还一份；close 最后交还自己的一份。交还总数恰好等于初值，取得最后一份的一方关闭 Provider：若在途工作在 close 的锁内阶段已全部结束，就是 close 本身，否则是最后释放的在途调用。被拒绝的调用方不修改任何计数或份额，因此不会执行 Provider 关闭。没有单元时仍是第 1 轮的单字协议。
5. 不变的部分：拒绝消息、epoch 语义、`diagnostics()` 的键；`inFlight` 是各字之和，静止时精确，并发变化时与 `LongAdder.sum()` 一样不一定对应某一时刻。`scopeOpenClose` 的分配仍是 376 B/op（新增的 `cells` 引用落在原有的对象对齐空隙里）。

这与 BRAVO（[Dice & Kogan, USENIX ATC 2019](https://www.usenix.org/conference/atc19/presentation/dice)）一类"读者只写自己散列到的槽位，写者扫描整张表"的读者指示器相似；不同之处是 close 不等待在途调用结束，而是用份额把关闭交给最后一个离开者。

### 为什么不用 SNZI

SNZI（[Ellen, Lev, Luchangco, Moir, PODC 2007](https://dl.acm.org/doi/10.1145/1281100.1281106)）只回答"是否有剩余到达"，它的可关闭变体 C-SNZI（[Lev, Luchangco, Olszewski, SPAA 2009](https://people.csail.mit.edu/mareko/spaa09-scalablerwlocks.pdf)）在关闭后让到达失败、让最后一次离开得知已清空，关闭只修改根节点，语义上正好对应准入。但在它的树中，叶子计数从 0 变为 1 时要先到达父节点，叶子回到 0 时也要离开父节点。计划调用只持续十几纳秒，每个线程散列到的叶子几乎总在 0 和 1 之间往返，于是几乎每次调用都会更新根，竞争又集中到一个字上。SNZI 适合到达者在同一叶子上长时间重叠的场景（例如读写锁的读者），这里改用各线程独立计数、由 close 一次性扫描的分段方案。

### 第 2 轮验证

合同测试新增的准入用例：

- 只在竞争时分段：单线程 2 万次调用和 200 次开关作用域都不创建单元；4 线程竞争后创建一次，之后不再替换，单元个数与间隔符合上文。
- 排空顺序：分段后 3 个调用阻塞在单元、1 个 Provider 解析阻塞在基础字，close 后分别以"单元先排空"和"基础字最后排空"两种顺序释放，Provider 只在最后一个结束后关闭一次。
- 竞态压力：自然竞争 100 轮、强制分段 150 轮，每轮 4 个工作线程与 close 竞争，检查在途时关闭、由未准入者关闭、close 返回后仍被准入、另一调用被拒绝后仍被准入、关闭后仍有目标进入以及关闭次数。并发 close 100 轮。
- 中间状态：把分段作用域的基础字置为关闭而单元仍开放（close 第一步之后的状态），此时任何线程的调用都必须被拒绝；另有观察线程在 close 期间反复读取，确认不存在"某个单元已关闭而基础字仍开放"的时刻。
- 其他：single-flight 只在出现等待者时创建 future；成员表视图跟随 LRU 尾部并随清空丢弃；最近项视图在写入后不再服务旧尾部；覆盖范围字符串不变；精确描述符类型缓存的加载器隔离、无负缓存、容量上限与丢弃。

变异检查结果（主机 JDK 17，`tools/mutation_check.py`；"合同"列是失败的合同用例数，jcstress 列是 `-m quick -jvmArgs -Xmx1g -sc false` 下失败的配置数）：

| 变体 | 说明 | 合同测试 | jcstress |
|---|---|---|---|
| `close-skips-cells` | close 只给基础字置 CLOSING，把所有单元当作空闲 | 6 项失败 | 32/40 配置失败 |
| `cells-before-base` | close 先关单元、后关基础字 | 1 | 1/40 |
| `cell-skips-base-check` | 分段后的调用不检查基础字的 CLOSING | 1 | 1/40 |
| `no-close-share` | 交接计数漏掉 close 自己的一份 | 4 | 28/40 |
| `pending-after-closing` | 置位之后才写交接计数 | 3 | 23/40 |
| `increment-then-check` | 单元先加一、发现关闭再撤销 | 1 | 8/40 |
| `release-to-base` | 所有释放都减基础字 | 9 | 32/40 |
| `inflate-without-lock` | 不加锁、不检查状态就创建单元 | 2 | 9/48（单独复测） |
| `inflated-before-cells` | 先置 INFLATED 再发布单元表 | 4 | 未稳定复现 |
| `release-exact-closing` | 释放只在恰好等于 CLOSING 时排空，忽略 INFLATED 位 | 2 | 8/40 |
| `idle-shortcut-always` | close 总把交接当作自己完成 | 3 | 24/40 |
| `memo-survives-put` / `-close` / `-epoch` | 写入、close、epoch 推进后保留最近项视图 | 各 1 | 各 4/12 |
| `table-memo-stale-after-hit` / `table-memo-survives-clear` | 成员表视图不跟随命中、clear 后保留 | 各 1 | 未运行 |
| `type-cache-ignores-loader` / `-negative` / `-unbounded` / `-survives-clear` | 类型缓存只按名字做键、记住失败、不受容量限制、clear 后保留 | 2 / 1 / 1 / 1 | 未运行 |

未改动的对照在两种检查下都通过。20 个变体全部被合同测试发现；jcstress（`-m quick -jvmArgs -Xmx1g -sc false`）另外独立发现其中 13 个并发变体，`inflated-before-cells` 只能由合同测试中按顺序构造的中间状态用例稳定发现。

jcstress 结果（当前实现）：9 个测试、60 个配置全部通过，没有失败、软错误或硬错误（`-m quick -jvmArgs -Xmx1g -sc false`，约 1.5 分钟）。每个测试的样本数在 210 万至 550 万之间；`CloseWhileCallingCells` 观察到全部 4 种允许的结果，`MemoStaysLruTail` 的 210 万个样本全部为"视图即尾部"。

### single-flight：只在出现等待者时创建 future

每次未命中都会在协调器中登记一个 flight。以前每个 flight 都带一个 `CompletableFuture`，拥有者完成后再移除。现在第一个等待者在协调器监视器内创建 future；拥有者在同一监视器内移除 flight 并读取 future，已挂上的等待者得到同一结果或同一异常，之后的调用者开始新的 flight。没有等待者的未命中不再分配和完成 future。合同测试覆盖成功、异常和"无等待者时不分配"三种情况。

### 安装批量：同一作用域多次结构查询

MeguvizHook 桥接计划用一个作用域解析同一 owner 上的多个描述符目标。对 `BatchResolveBenchmark` 用 JFR 采样（`-prof jfr`）后，每次查询除了不可避免的成员筛选、schema 与回执外，还有几项可以去掉的开销：

- 成员表查找：每次查询都分配 `TableKey` 并在锁内查 `LinkedHashMap`。现在保留最近使用的表（即 LRU 尾部）的无锁视图，同一 owner 的连续查询直接使用；与第 1 轮的缓存命中视图一样，视图非空时必为尾部，不改变淘汰顺序。视图随表的 `clear` 丢弃，不需要淘汰时也不再创建 values 迭代器。
- 覆盖范围字符串 `"java-visible:" + owner + ":" + hierarchy`：在连续相同的 owner 与 hierarchy 之间复用（不可变对象的竞态单检查），字符串内容不变。
- owner 校验：`QuerySpec.Builder` 和 `MemberDescriptor` 以前拼出 `"L" + owner.replace('.', '/') + ";"` 再交给 `TypeDescriptor.of` 校验，现在原位检查同样的条件并给出同样的消息；合同测试对 1.0.0 实现的副本做了 2 万条随机字符串的差分。桥接式查询构造从 360 降到 184 B/op。

### 测量过但未采用

- 回执与结果的列表复制：`ResolutionReceipt` 和 `ResolutionResult` 的公开构造器会防御性复制列表。内部免复制的构造路径需要新增公开 API（这些类与 `CodeScope` 不在同一个包），改用 `List.copyOf` 又会改变 null 元素的处理且需要 Android API 30。每次查询节省约 70 B 和十几纳秒，不值得扩大 API。
- 跳过"只需导出"成员的 schema：结构查询对通过名称、静态、参数个数与标志筛选的每个成员都建立 schema；即使查询没有参数类型约束，回执也需要其描述符。跳过 schema 会改变 `parameterReads` 与 `schemas` 诊断值，而合同测试固定了这些值，因此保留。
- 作用域内 `Class` 到类型描述符的记忆：可省去引用类型描述符的 `replace` 与拼接，但 `MemberDescriptor` 没有接受现成 `TypeDescriptor` 的公开工厂，经字符串工厂还要重新校验，收益约为每个引用类型几十纳秒，未做。
- 缓存每次未命中的 `Context` 快照：会让作用域对象跨过 8 字节对齐边界，`scopeOpenClose` 每次多分配 8 B，与"不加重作用域开关"的要求冲突。

### 精确描述符类型缓存：加载器身份审查

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

### 第 2 轮测量

环境同上。优化前为 main `8340a3c`（第 1 轮合入后）的 ref-core，优化后为本分支 HEAD；两者使用同一套基准类，只替换 ref-core 构件，按 base、head、head、base 交替运行两轮（每侧每轮一个 fork，`-wi 3 -i 5 -w 1s -r 1s -prof gc`），表中为两轮全部测量迭代的中位数。运行期间主机负载约 1.6–2.5（8 vCPU）。

| 基准 | 线程 | 优化前 ns/op | 优化后 ns/op | 优化前 B/op | 优化后 B/op |
|---|---|---|---|---|---|
| `InvokeBenchmark.planInvoke` | 1 | 18.6 | 18.5 | 0 | 0 |
| `InvokeBenchmark.planInvokeAfterContention`（已分段的作用域，单线程） | 1 | 18.4 | 18.3 | 0 | 0 |
| `InvokeBenchmark.planInvokeThreads2` | 2 | 106.1 | 19.1 | 0 | 0 |
| `InvokeBenchmark.planInvokeThreads4` | 4 | 228.4 | 19.4 | 0 | 0 |
| `InvokeBenchmark.planInvokeThreads8` | 8 | 725.0 | 28.9 | 0 | 0 |
| `InvokeBenchmark.planInvokeThreads4OwnScope`（参考：每线程一个作用域） | 4 | 19.7 | 19.4 | 0 | 0 |
| `ResolveBenchmark.scopeOpenClose` | 1 | 74.6 | 75.1 | 376 | 376 |
| `ResolveBenchmark.exactHitThreads8` | 8 | 13.2 | 13.2 | 0 | 0 |
| `ResolveBenchmark.exactMissFreshScope` | 1 | 1012.5 | 1009.0 | 2064 | 1944 |
| `ResolveBenchmark.structuralMissFreshScope` | 1 | 4527.6 | 4423.8 | 17000 | 16712 |
| `ResolveBenchmark.structuralEightQueriesOneScope` | 1 | 12377.9 | 11905.2 | 27080 | 24440 |
| `BatchResolveBenchmark.exactOneScopeRaw`（20 个目标） | 1 | 23833.7 | 16663.5 | 35352 | 29760 |
| `BatchResolveBenchmark.structuralOneScopeRaw`（20 个目标） | 1 | 25669.9 | 23270.6 | 50864 | 42272 |
| `BatchResolveBenchmark.structuralOneScopeExport`（20 个目标） | 1 | 27183.5 | 24432.3 | 55107 | 46512 |
| `BatchResolveBenchmark.structuralScopePerQueryExport`（20 个目标，每个一个作用域） | 1 | 87261.2 | 81211.8 | 325014 | 317333 |
| `DescriptorBenchmark.bridgeQueryBuild` | 1 | 123.5 | 114.7 | 360 | 184 |

解读：

- 多线程共享一个作用域的计划调用从随线程数线性上升变为基本持平：8 线程 725 → 28.9 ns/op（8 vCPU 为 4 核 × 2 SMT），与"每线程一个作用域"的参照相当。单线程调用和作用域开关没有变化。
- 同一作用域解析 20 个目标时，结构查询快约 10%，完整精确描述符快约 30%（类型缓存省去重复的 `Class.forName`）。每个目标一个作用域时成员表无法复用，主要成本仍是每次的 `getDeclaredMethods()` 复制，这正是桥接应在同一次安装中共用一个作用域的原因。

## 后续方向

1. 在 ART 设备上验证分段准入、成员表与类型缓存（`samples/android-consumer`），补充设备证据；手机大小核的缓存行为与 x86 SMT 不同。
2. MeguvizHook 桥接在 1.1.0 发布后：安装批量共用一个作用域并改用 `resolveRaw`（不累计 binding），会话的身份检查改用一次加锁的 `isCurrent`。
3. 回执与结果的内部免复制构造需要跨包 API，若将来调整包结构可再评估。
4. jcstress 目前只在 x86 主机上运行；如有 ARM 主机，补跑一次完整组合。

[返回文档目录](README.md)
