<div align="center">
<img src="docs/brand/MeguvizRef.svg" width="96" height="96" alt="MeguvizRef">

# MeguvizRef

**面向 Java 17 与 Android 的反射查询和调用工具库**

[Maven Central](https://central.sonatype.com/artifact/io.github.jichuo1.meguviz/meguviz-ref-core) · [文档](docs/README.md) · [示例](samples)

</div>

MeguvizRef 可通过类、成员描述符或结构条件定位方法、字段与构造器。用 `CodeScope` 管理类加载器和缓存的生命周期，绑定后的调用计划可以复用，并在每次调用时传入接收对象。核心库只依赖 JDK。

## 快速开始

在 Gradle 的仓库配置中启用 `mavenCentral()`。

```kotlin
dependencies {
    implementation("io.github.jichuo1.meguviz:meguviz-ref-core:1.2.0")
}
```

```java
import io.github.jichuo1.meguviz.ref.api.query.QuerySpec;
import io.github.jichuo1.meguviz.ref.scope.CodeScope;

public final class Example {
    public static void main(String[] args) throws Throwable {
        try (CodeScope scope = CodeScope.open(Example.class.getClassLoader())) {
            var substring = scope.bindMethod(QuerySpec.method(String.class, "substring", int.class));
            System.out.println(substring.call("hello", 2)); // llo
        }
    }
}
```

结构查询默认要求唯一匹配。对公开成员遵循 Java 访问检查，访问非公开成员取决于平台权限。关闭作用域后会拒绝新的调用。

## 按需选择模块

下表制品使用 groupId `io.github.jichuo1.meguviz`。

| 制品 | 用途 |
| --- | --- |
| `meguviz-ref-core` | 查询、调用计划和类加载器作用域 |
| `meguviz-ref-diagnostics` | 结构化诊断与解析结果持久化 |
| `meguviz-ref-android` | Android 平台适配和代码身份辅助 |

Android 模块需要 `compileSdk` 37 或更新版本，设备最低 API 27。

## 文档

- [核心 API](docs/api/core-contracts.md) · [版本策略](docs/api/version-policy.md)
- [Java 示例](samples/jvm-consumer) · [Kotlin 示例](samples/kotlin-consumer) · [Android 示例](samples/android-consumer)
- [Apache License 2.0](LICENSE) · [版权与第三方声明](NOTICE)
