# Provider SPI 1

## 1. 注册表与生命周期

- `ProviderDescriptor` 包含稳定 ID、实现版本、SPI major/minor 版本号、互斥能力、依赖列表，以及固定的 `CALLER_THREAD` 与 `CODE_SCOPE` 关闭归属。遇到未知 major、重复 ID、缺失依赖或依赖环时，安装阶段直接拒绝；同能力存在多个 Provider 时，必须通过显式的 owner 映射裁决，被排除的 Provider 不会转交作用域，仍由原调用方负责关闭。
- `ProviderRegistry` 是不可变安装快照，仅能转交给一个 `CodeScope`，且必须在首次 `resolve` 之前安装。快照不支持原地替换；需要更新 Provider 时，应创建新作用域安装新快照，旧作用域在排空在途请求后自动关闭旧快照。

## 2. 候选发现与可见域复核

- `CandidateBatch` 仅传递描述符、代码指纹与候选域；目标作用域必须已安装 `STRONG` 强度的 `CodeIdentity`，并将每个候选按当前加载器重新绑定并核验完整类型与请求范围。不接受来自 Provider 的字段或方法原始 `Member` 对象。指纹不匹配返回 `STALE`；候选解析失败或超出请求范围返回 `PARTIAL`。
- Provider 返回的 `complete = true` 仅视为声明。对于本次支持的查询约束，`ProviderCoverageVerifier` 会以独立公开反射循环重放实际 Java 可见域，核对满足条件的完整 `Member` 集合；无法证明完整性时仍返回 `PARTIAL`，`complete = false` 不会被升级。此冷路径复核需要额外枚举开销，因此不建议把 Provider 冷查询当作性能优化手段，作用域内的成员表缓存也不参与该复核或 Provider 候选域。隐藏 API 过滤下，完整性仍仅代表 Java 可见范围。

## 3. 加载器遮蔽与版本差异

- 候选的声明类必须属于实际查询域；对于 `Hierarchy.JAVA_PUBLIC`，还必须是 `getMethods()` 或 `getFields()` 实际返回的成员，不能仅凭声明类是 owner 的父类就认定公开可见。若作用域加载器遮蔽了继承声明类，仅当在真实查询域中能唯一确认其类和成员身份时才允许恢复；存在歧义或无法核验时保持不完整。
- 便携描述符不包含加载器身份。若查询域内两个不同加载器存在同名的便携描述符成员，即使结构查询能借由 `Class` 约束区分，Provider 路径仍可能因无法证明覆盖而返回 `PARTIAL`。此时应改用结构查询，不能将 `PARTIAL` 当作未匹配或自行猜测成员身份。
- 指定 `Selection.FIRST` 时，Provider 路径不按候选列表顺序挑取，而是在完整覆盖复核通过后选用独立公开反射顺序的首项；结构查询的 `FIRST` 则在枚举中找到首项即停，两者的覆盖结论不必相同（1.0.0 版本曾按 Provider 候选顺序返回，升级时请注意核对）。

## 4. 调用性能与版本边界

绑定成 `MethodPlan` / `FieldPlan` / `ConstructorPlan` 后，实际调用不再枚举候选或触发 Provider 回调。API 版本、SPI major（1）、回执 schema major（1）与持久化信封格式各自独立演进。当前版本未提供代码生成器，也不存在生成代码 ABI 承诺。

[返回文档目录](../README.md)
