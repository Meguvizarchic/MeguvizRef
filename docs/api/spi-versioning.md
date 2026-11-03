# Provider SPI 1

ProviderDescriptor 包含稳定 ID、实现版本、SPI major/minor、互斥能力、依赖、固定 CALLER_THREAD 和 CODE_SCOPE 关闭所有者。未知 major、重复 ID、未满足依赖和环都在安装时拒绝；同能力多个 Provider 必须用显式 owner 配置裁决。被显式排除的 Provider 不转交 scope，仍由原调用方负责关闭。

ProviderRegistry 是不可变安装快照，只能转交一个 scope，且在首次 resolve 之前安装。快照不支持原地替换；新 scope 安装新快照，旧 scope 按自己的在途计数排空关闭。既有快照在所属作用域内保持不变。

CandidateBatch 只传描述符、代码指纹和候选域；scope 必须已有 STRONG CodeIdentity，并将每个候选重新绑定当前 loader、验证完整类型和请求域。字段/方法 raw 成员不从 Provider 输入接受。错误指纹为 STALE；候选解析失败、候选不在域内为 PARTIAL。

Provider 的 complete=true 只是声明。对本次已支持的结构约束，ProviderCoverageVerifier 以独立直接反射循环重放实际 Java 可见域，核对满足约束的完整 Member 集合；没有证明时仍为 PARTIAL。complete=false 不会被升级。这个冷路径复核有额外枚举成本，因此不将 Provider 冷查询当作性能优化。隐藏过滤仍只代表 Java 可见宇宙。

候选的声明类必须属于实际查询域；对于 `JAVA_PUBLIC`，还必须是 `getMethods()` 或 `getFields()` 实际返回的成员，不能仅凭声明类是 owner 的父类型就认定可见。若作用域加载器遮蔽了继承声明类，同名候选只在真实查询域中能唯一确认其类和成员身份时恢复；存在歧义或无法验证时保持不完整。

便携描述符不含加载器身份。当查询域中两个不同加载器的成员具有相同便携描述符时，即使 `parameters` 或 `returns` 的 `Class` 约束能在结构查询中区分它们，Provider 路径仍可能因无法证明候选覆盖而返回 `PARTIAL`。需要按实际 `Class` 身份筛选时，可使用结构查询；不要将该不完整结果当作无匹配或自行猜测成员身份。

显式 `FIRST` 的 Provider 路径不按候选排列顺序选择；完整覆盖复核成功后，使用独立重放的公开反射顺序中的第一项。结构查询的 `FIRST` 则在反射枚举中找到首项后直接停止；两者的 coverage 不必相同。已发布的 1.0.0 版本可能按 provider 候选顺序选择；升级时应核对实际使用的版本。

绑定后执行无需候选枚举或 Provider 回调，实际合同套件含 10000 次稳定调用计数验证。测试用 Provider 只验证此 SPI，不构成外部框架的适配实现。

API、SPI major=1、receipt schema major=1 与持久化 envelope 独立版本化。未实现 codegen，不存在已验收的生成 ABI。


[返回文档目录](../README.md)
