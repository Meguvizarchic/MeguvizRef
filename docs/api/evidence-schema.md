# 解析证据与代码身份

## 1. 诊断收据 `ResolutionReceipt`

`ResolutionReceipt` 是不含运行期对象的纯值记录，包含查询摘要、选择模式、结果状态、可见域、已检查数量、完整性标志、成员描述符、原因字符串与异常类型名。它不保存 `Throwable`、`Class`、`Member`、接收者、`ClassLoader` 或进程内引用，从而与运行期作用域令牌彻底隔离。

`interruptionOrigin()` 仅作为单次调用的运行时诊断信息，不写入 schema 1 的持久化 JSON；通过旧构造器或持久化数据重建时恒为 `UNKNOWN`。消费方不能凭异常类型名还原原始 `Throwable`，也不能将手工构造或反序列化的回执当作单飞等待者身份的可信证明。使用前提见[核心 API](core-contracts.md)。

## 2. 代码身份 `CodeIdentity`

`CodeIdentity.Strength` 区分 `STRONG`、`WEAK` 与 `UNVERIFIED` 三级：

- **`STRONG`**：工厂方法要求每个制品摘要均为 64 位小写 SHA-256 十六进制字符串；`CodeIdentity.strong(...)` 本身不代读磁盘文件，可按需使用 `CodeIdentities.hashFiles(...)` 计算真实文件哈希。
- **`WEAK` / `UNVERIFIED`**：文件修改时间（mtime）或版本号只能构成弱提示，不可用于命中持久化恢复。

## 3. 独立复验器 `IndependentVerifier`

独立复验器不调用核心查询选择器，而是通过公开反射枚举，独立构造基本类型、数组和对象类型的描述符，并校验声明类 `owner`、完整参数类型、返回类型及修饰符标志位。若签名中缺少类或公开反射 API 不可用，复验返回 `UNAVAILABLE`，不会把未完成枚举误判为“成员不存在”。

对应合同测试见 [DiagnosticsContractTest](../../ref-diagnostics/src/test/java/io/github/jichuo1/meguviz/ref/diagnostics/DiagnosticsContractTest.java)，测试输出位于 `ref-diagnostics/build/test-results/contracts/`。

[返回文档目录](../README.md)
