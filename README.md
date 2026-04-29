# kong-core 快速入门

`kong-core` 是一组轻量级 Java 工具和模式封装，当前测试用例主要覆盖：

- `Opt`：同时表达有值、空值、错误三种状态。
- `Pipe`：把多个处理步骤按输入输出类型串起来。
- `Chain`：责任链式处理上下文，可继续、跳过或提前结束。
- `Bus`：本地事件总线，支持按 topic 或实体类型订阅、推送、回调和注解绑定。

## 环境要求

项目使用 Maven 构建，当前模块坐标为：

```xml
<dependency>
    <groupId>io.github.kongweiguang</groupId>
    <artifactId>kong-core</artifactId>
    <version>0.6</version>
</dependency>
```

在父工程中运行测试：

```bash
mvn -pl kong-core test
```

如果只在 `kong-core` 模块目录下运行，需要确保父 POM `io.github.kongweiguang:kong:0.6` 已经可以被 Maven 解析。

## 1. Opt：处理值、空值和错误

`Opt<T, E>` 的第一个泛型是正常值类型，第二个泛型是错误类型。

```java
import io.github.kongweiguang.v1.core.lang.Opt;

Opt<String, String> ok = Opt.of("  hello  ");
Opt<String, String> empty = Opt.empty();
Opt<String, String> error = Opt.error("无效输入");

String result = ok
        .map(String::trim)
        .filter(s -> s.length() > 3)
        .map(String::toUpperCase)
        .orElse("DEFAULT");

System.out.println(result); // HELLO
System.out.println(empty.orElse("DEFAULT")); // DEFAULT
System.out.println(error.getError()); // 无效输入
```

根据三种状态分别处理：

```java
Opt<String, String> input = Opt.ofNullable("kong");

String message = input.matchResult(
        value -> "处理值: " + value,
        () -> "输入为空",
        err -> "处理错误: " + err
);

System.out.println(message);
```

把可能抛异常的调用转成 `Opt`：

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

Opt<String, IOException> content = Opt.ofTry(() ->
        Files.readString(Paths.get("config.toml"))
);

content.match(
        System.out::println,
        () -> System.out.println("文件内容为空"),
        e -> System.out.println("读取失败: " + e.getMessage())
);
```

## 2. Pipe：串联多个转换步骤

`Pipe<I, O>` 适合把多个有明确输入输出类型的步骤组合成一条流水线。

```java
import io.github.kongweiguang.v1.core.pattern.pipe.Pipe;

Integer length = Pipe.of((String input) -> input.trim())
        .next(String::toUpperCase)
        .next(String::length)
        .exec("  kong-core  ");

System.out.println(length); // 9
```

每个 `next` 的入参类型必须匹配上一步的输出类型。例如：`String -> Integer -> Double -> String`。

## 3. Chain：责任链处理上下文

`Chain<C>` 会持有一个上下文对象。每个处理器返回 `true` 表示继续执行下一个处理器，返回 `false` 表示停止。

```java
import io.github.kongweiguang.v1.core.pattern.chain.Chain;

import java.util.HashMap;
import java.util.Map;

Map<String, Object> context = new HashMap<>();

Chain.<Map<String, Object>>of(context)
        .add(chain -> {
            chain.get().put("step1", "ok");
            return true;
        })
        .add(chain -> {
            chain.get().put("step2", "ok");
            return true;
        })
        .add(chain -> {
            chain.get().put("done", true);
            return true;
        })
        .process();

System.out.println(context);
```

常用控制方法：

- `chain.next()`：手动执行下一个处理器。
- `chain.skip(n)`：跳过后续 `n` 个处理器。
- `chain.end()`：结束整条链。
- `chain.set(context)`：替换当前上下文。

## 4. Bus：本地事件总线

`Bus.hub()` 返回默认 hub。可以按字符串 topic 订阅和推送消息。

```java
import static io.github.kongweiguang.v1.core.pattern.bus.Bus.hub;

String topic = "branch.test1";

hub().pull(topic, oper -> {
    System.out.println("id = " + oper.id());
    System.out.println("content = " + oper.content());
});

hub().push(topic, "content");
```

### 推送并接收回调

订阅者可以通过 `oper.res(...)` 把结果返回给推送方。

```java
import io.github.kongweiguang.v1.core.pattern.bus.Bus;

String topic = "user.created";

Bus.<String, String>hub().pull(topic, oper -> {
    System.out.println("收到消息: " + oper.content());
    oper.res("处理完成");
});

Bus.<String, String>hub().push(topic, "kong", result -> {
    System.out.println("回调结果: " + result);
});
```

### 多个订阅者和执行顺序

同一个 topic 可以注册多个订阅者。`pull(topic, index, handler)` 的 `index` 越小越先执行。

```java
import static io.github.kongweiguang.v1.core.pattern.bus.Bus.hub;

String topic = "ordered.topic";

hub().pull(topic, 0, oper -> System.out.println("第二个执行"));
hub().pull(topic, -1, oper -> System.out.println("第一个执行"));

hub().push(topic, "content");
```

### 命名订阅者和取消订阅

实现 `Merge` 时可以覆盖 `name()`，然后通过 `remove(topic, name)` 移除。

```java
import io.github.kongweiguang.v1.core.pattern.bus.core.Merge;
import io.github.kongweiguang.v1.core.pattern.bus.core.Oper;

import static io.github.kongweiguang.v1.core.pattern.bus.Bus.hub;

String topic = "removable.topic";

hub().pull(topic, new Merge<Oper<Object, Object>>() {
    @Override
    public String name() {
        return "print_handler";
    }

    @Override
    public void mr(Oper<Object, Object> oper) {
        System.out.println(oper.content());
    }
});

hub().push(topic, "before remove");
hub().remove(topic, "print_handler");
hub().push(topic, "after remove");
```

### 使用实体类型作为 topic

直接推送对象时，默认 topic 是对象类名；订阅时使用 `pull(Class<?>, handler)`。

```java
import static io.github.kongweiguang.v1.core.pattern.bus.Bus.hub;

record User(int id, String name) {
}

hub().pull(User.class, oper -> {
    System.out.println(oper.content());
    oper.res("ok");
});

hub().push(new User(1, "kong"));
```

### 使用注解注册订阅方法

`@Pull` 可以把类里的方法注册成订阅者。方法有返回值时，返回值会触发推送方回调。

```java
import io.github.kongweiguang.v1.core.pattern.bus.anno.Pull;
import io.github.kongweiguang.v1.core.pattern.bus.core.Oper;

import static io.github.kongweiguang.v1.core.pattern.bus.Bus.hub;

class UserHandler {
    @Pull("user.create")
    public String onCreate() {
        return "created";
    }

    @Pull
    public String onUser(User user) {
        return "hello " + user.name();
    }

    @Pull
    public String onUserOper(Oper<User, String> oper) {
        return "oper " + oper.content().name();
    }
}

record User(int id, String name) {
}

hub().pullClass(new UserHandler());

hub().push("user.create", "content", System.out::println);
hub().push(new User(1, "kong"), System.out::println);
```

### 自定义 hub 做隔离

不同名称的 hub 相互隔离，适合把不同业务场景的事件分开。

```java
import static io.github.kongweiguang.v1.core.pattern.bus.Bus.hub;

hub("order").pull("created", System.out::println);
hub("order").push("created", "order-1");

hub().push("created", "default hub message"); // 不会推送到 order hub 的订阅者
```

### 给消息添加标签

需要传递额外元数据时可以使用 `Oper.tag(k, v)`。

```java
import io.github.kongweiguang.v1.core.pattern.bus.Bus;
import io.github.kongweiguang.v1.core.pattern.bus.core.Oper;

String topic = "tag.topic";

Bus.<String, Void>hub().pull(topic, oper -> {
    String traceId = oper.tag("traceId");
    System.out.println(traceId + " -> " + oper.content());
});

Bus.<String, Void>hub().push(
        Oper.<String, Void>of(topic, "content").tag("traceId", "t-001")
);
```

## 推荐阅读测试

更多完整用法可以直接查看测试文件：

- `src/test/java/io/github/kongweiguang/core/lang/OptTest.java`
- `src/test/java/io/github/kongweiguang/core/pattern/PipeTest.java`
- `src/test/java/io/github/kongweiguang/core/pattern/ChainTest.java`
- `src/test/java/io/github/kongweiguang/core/pattern/bus/*Test.java`
