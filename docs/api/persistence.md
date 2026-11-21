# 持久化 schema 1.0

## 1. 存储内容与恢复原则

`StoredResolution` 保存强代码身份（`CodeIdentity`）、完整 `MemberDescriptor`、固定 `AccessPolicy` 以及修饰符约束。

- 查询 SHA-256 覆盖完整的精确查询条件、访问策略与修饰符要求；代码 SHA-256 覆盖有序的制品身份、加载配置与规则版本。这些哈希仅用于身份比对与损坏检测，不构成代码签名或安全授权。
- 仅 `exact_descriptor_hint` 支持恢复；`diagnostic_only` 记录不可进入执行路径。`FIRST` / `UNIQUE` / `ALL` 的覆盖结论不会在跨进程恢复时直接升级为完整证明。
- Provider 声明的 `complete` 只是提供方自述；`IndependentVerifier.verifyFiniteUniverse` 使用独立公开反射枚举对有限已知夹具核验完整候选集，用于发现遗漏。

## 2. 编解码容量与格式限制

编解码器限制单条记录最大 `64 KiB`、JSON 最大嵌套深度 16、最多 4096 个值节点、字符串最长 8192 字符以及最多 255 个参数。遇到重复键、非法 UTF-8、未知 major/minor 版本、额外未知字段、截断数据、浮点数或超限输入时一律拒绝。当前 minor 版本没有可忽略字段，因此未知 minor 也会被拒绝；未来扩展需同步更新 schema 合同与兼容夹具。

## 3. 文件存储与恢复条件

- `FileReceiptStore` 使用键的 SHA-256 作为文件名，要求调用方独占存储目录并限制最大条目数，不负责跨多写入进程的容量协调。
- 写入流程为：写临时文件 → 刷盘（`force`） → 检查线程中断标志 → 原子替换目标文件。无法执行原子替换时保留旧记录并抛出 `IOException`。
- 恢复时必须传入与目标 `CodeScope` 一致的 `ClassLoader` 及当前实际的强 `CodeIdentity`。库会校验加载器与访问策略，代码集合变更与作用域 epoch 同步仍由外部加载系统负责。持久化文件不是安全边界，不能把不可信的哈希值宣称为已验证代码。

[返回文档目录](../README.md)
