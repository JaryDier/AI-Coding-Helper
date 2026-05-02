# Reactor Flux 常用知识点

> 目标：用最容易理解的方式掌握 `Flux`。看完后能读懂 Spring WebFlux / Reactor 代码，也能写常见的流式接口、异步编排、错误处理和多轮递归逻辑。

## 1. Flux 是什么

`Flux<T>` 表示一个异步数据流，可以发出 0 到 N 个元素。

可以把它理解成：

```text
List<T> 是一次性拿到一堆数据
Flux<T> 是未来陆续吐出一堆数据
```

例如：

```java
Flux.just("A", "B", "C")
```

表示一个流，订阅后依次产生：

```text
A -> B -> C -> 完成
```

## 2. Flux 和 Mono 的区别

| 类型 | 含义 | 类比 |
|---|---|---|
| `Mono<T>` | 0 或 1 个元素 | `Optional<T>` / 一个异步结果 |
| `Flux<T>` | 0 到 N 个元素 | 异步版 `List<T>` / 数据流 |

常见使用：

```java
Mono<User> findUserById(String id);

Flux<User> findAllUsers();
```

## 3. Flux 的三种信号

Reactor 流里不只是数据，还有信号。

```text
onNext     产生一个元素
onError    出错，流结束
onComplete 正常结束
```

一个流只能有一种终止方式：

```text
onNext -> onNext -> onComplete
```

或者：

```text
onNext -> onError
```

`onError` 后不会再继续 `onNext`。

## 4. Flux 是懒执行的

Flux 默认不会立刻执行，只有被订阅时才执行。

```java
Flux<String> flux = Flux.just("A", "B")
        .doOnNext(System.out::println);

// 这里还不会打印

flux.subscribe();
```

在 WebFlux Controller 里，你通常不用手动 `subscribe()`，框架会帮你订阅。

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> stream() {
    return service.stream();
}
```

## 5. 创建 Flux

### 5.1 just

`just` 用来创建一个包含固定元素的 `Flux`。

它的特点是：元素在创建 `Flux` 时就已经确定，订阅时只是把这些元素按顺序发出去。

```java
Flux.just("A", "B", "C");
```

订阅后会依次发出：

```text
A -> B -> C -> onComplete
```

适合场景：

- 写测试数据；
- 返回固定提示；
- 把几个已知值快速包装成流。

注意点：

- `Flux.just(...)` 里的值会在创建时就求值；
- 如果你写 `Flux.just(UUID.randomUUID())`，UUID 只会生成一次；
- 如果你希望每次订阅都重新生成值，用 `defer`。

### 5.2 fromIterable

`fromIterable` 用来把一个集合转换成 `Flux`。

它不会一次性把集合整体作为一个元素发出，而是逐个发出集合里的元素。

```java
List<String> list = List.of("A", "B", "C");
Flux.fromIterable(list);
```

发出顺序：

```text
A -> B -> C -> onComplete
```

适合场景：

- 已经有 `List`，但下游方法需要 `Flux`；
- Controller 返回流式数据；
- 对集合里的元素逐个做 `map/filter/flatMap`。

注意点：

- 集合本身已经在内存里；
- 如果集合非常大，仍然会有内存压力；
- 如果数据来自数据库、文件、远程接口，更推荐直接使用响应式查询或分页生成。

### 5.3 empty

`empty` 创建一个空的 `Flux`。

它不会发出任何 `onNext`，只会直接发出 `onComplete`。

```java
Flux.empty();
```

常用于“不需要继续返回内容”：

```java
if (completed) {
    return Flux.empty();
}
```

适合场景：

- 判断完成后不需要额外输出；
- 条件不满足时返回空结果；
- 在 `switchIfEmpty`、`concatWith` 等组合里表达“这里没有内容”。

你当前 agent 递归流里就有一个典型用法：

```java
if (judgeResult.isCompleted()) {
    return Flux.empty();
}
```

因为本轮内容已经通过实时流返回过了，判断分支不能再重复返回。

### 5.4 error

`error` 创建一个直接失败的 `Flux`。

订阅后不会发出正常元素，而是直接触发 `onError`。

```java
Flux.error(new RuntimeException("失败了"));
```

适合场景：

- 在响应式链路里返回错误；
- 空结果转异常；
- 根据业务条件主动中断流。

例如：

```java
findUser(id)
        .switchIfEmpty(Flux.error(new RuntimeException("用户不存在")));
```

注意点：

- `Flux.error(...)` 不是立刻抛异常，而是订阅后发出错误信号；
- 如果后面有 `onErrorResume`，可以恢复；
- 如果没有错误处理，错误会一路传递到框架层。

### 5.5 range

`range` 创建一个连续整数流。

第一个参数是起始值，第二个参数是数量。

```java
Flux.range(1, 5); // 1,2,3,4,5
```

这表示：

```text
从 1 开始，发出 5 个数
```

所以结果是：

```text
1 -> 2 -> 3 -> 4 -> 5 -> onComplete
```

适合场景：

- 测试；
- 生成页码；
- 批量任务编号；
- 模拟多条数据。

注意：

```java
Flux.range(1, 5)
```

不是从 1 到 5 的闭区间语义，而是“起点 1，数量 5”。碰巧结果也是 1 到 5。

### 5.6 interval

`interval` 创建一个定时流。

它会按照固定时间间隔发出递增的 `Long`，从 `0` 开始。

```java
Flux.interval(Duration.ofSeconds(1));
```

会每秒产生一个递增的 Long。

输出类似：

```text
0 -> 1 -> 2 -> 3 -> ...
```

适合场景：

- 心跳；
- 定时轮询；
- 进度刷新；
- 模拟流式输出。

注意点：

- `interval` 默认是无限流，不会自己结束；
- 通常需要配合 `take` 限制数量；
- 它依赖调度器，涉及异步线程。

例如只发 5 次：

```java
Flux.interval(Duration.ofSeconds(1))
        .take(5);
```

### 5.7 defer

`defer` 的核心作用是：**推迟创建 Flux，直到有人订阅时才执行创建逻辑**。

它非常重要，因为 Reactor 默认是懒执行，但有些值如果写在 `just` 里，仍然会在组装阶段就计算出来。

```java
Flux.defer(() -> Flux.just(UUID.randomUUID().toString()));
```

区别：

```java
Flux<String> a = Flux.just(UUID.randomUUID().toString());
Flux<String> b = Flux.defer(() -> Flux.just(UUID.randomUUID().toString()));
```

`a` 的 UUID 创建一次；`b` 每次订阅都会重新创建。

更直观一点：

```java
Flux<String> flux = Flux.just("当前时间：" + LocalDateTime.now());
```

这个时间在创建 `flux` 时就固定了。

而：

```java
Flux<String> flux = Flux.defer(() ->
        Flux.just("当前时间：" + LocalDateTime.now())
);
```

每次订阅都会拿到新的当前时间。

适合场景：

- 每次订阅都要读取最新状态；
- 每次请求都要重新构造查询条件；
- 包装可能抛异常的逻辑；
- 避免方法调用过早执行。

常见写法：

```java
return Flux.defer(() -> {
    if (someCondition()) {
        return Flux.just("ok");
    }
    return Flux.error(new RuntimeException("条件不满足"));
});
```

注意点：

- `defer` 里返回的是 `Publisher`，比如 `Flux` 或 `Mono`；
- 不要在 `defer` 外面提前执行真正的业务逻辑；
- 它适合“每次订阅都重新执行”的场景，不适合需要缓存结果的场景。

## 6. 转换 API

### 6.1 map

`map` 是最常用的转换 API。

它对流里的每个元素做一次同步转换：进来一个元素，出去一个新元素。

```java
Flux.just(1, 2, 3)
        .map(i -> i * 10);
```

结果：

```text
10, 20, 30
```

适合场景：

- 实体转 DTO；
- 提取字段；
- 字符串格式化；
- 对每个元素做轻量计算。

例如：

```java
Flux<User> users = findUsers();

Flux<String> names = users.map(User::getName);
```

注意点：

- `map` 里的函数应该返回普通对象；
- 如果返回 `Mono` 或 `Flux`，会变成嵌套类型；
- 异步调用不要用 `map`，要用 `flatMap`。

错误示例：

```java
Flux<Mono<User>> result = Flux.just("1", "2")
        .map(id -> findUserById(id));
```

正确写法：

```java
Flux<User> result = Flux.just("1", "2")
        .flatMap(id -> findUserById(id));
```

### 6.2 flatMap

`flatMap` 用来处理“每个元素转换后又得到一个 Mono/Flux”的情况。

它会把内部的 `Mono/Flux` 展开，最终得到一个平铺后的 `Flux`。

```java
Flux.just("1", "2")
        .flatMap(id -> findUserById(id));
```

如果 `findUserById` 返回 `Mono<User>`，`flatMap` 会把里面的 `User` 拿出来继续往下游传。

注意：`flatMap` 可能并发执行，结果顺序不一定和原始顺序一致。

适合场景：

- 根据 id 异步查数据库；
- 调用远程接口；
- 调用工具；
- 一个元素展开成多个元素。

例如：

```java
Flux<String> ids = Flux.just("1", "2", "3");

Flux<Order> orders = ids.flatMap(id -> orderService.findOrdersByUserId(id));
```

如果每个 id 会查出多个订单，`flatMap` 会把它们合并成一个 `Flux<Order>`。

注意点：

- 默认可能并发，输出顺序可能变化；
- 如果下游依赖顺序，用 `concatMap`；
- 如果并发太高，可以使用带并发参数的重载：

```java
flux.flatMap(this::callRemote, 4);
```

表示最多同时处理 4 个内部流。

### 6.3 concatMap

`concatMap` 和 `flatMap` 很像，但它保证顺序。

它会等第一个元素转换出来的内部流完成后，再处理第二个元素。

```java
Flux.just("1", "2", "3")
        .concatMap(id -> callRemoteApi(id));
```

适合：

- 必须按顺序执行；
- 不希望并发请求；
- 下一步依赖上一步的外部副作用。

典型场景：

- 按顺序执行任务步骤；
- 按顺序写文件；
- 调用有顺序要求的接口；
- 工具调用必须一个完成后再下一个。

代价：

- 吞吐量通常低于 `flatMap`；
- 某个内部流很慢，会阻塞后续元素处理。

选择口诀：

```text
要速度，用 flatMap
要顺序，用 concatMap
既要并发又要最终顺序，用 flatMapSequential
```

### 6.4 flatMapMany

`flatMapMany` 常用于 `Mono` 转 `Flux`。

它的语义是：等 `Mono` 有结果后，根据这个结果返回一个新的 `Flux`。

```java
Mono<List<String>> monoList = Mono.just(List.of("A", "B"));

monoList.flatMapMany(list -> Flux.fromIterable(list));
```

你的代码里：

```java
stringFlux.collectList()
        .flatMapMany(outputs -> {
            return runAgentRound(...);
        });
```

含义是：

```text
先把 Flux 收集成 Mono<List>
然后根据这个 List 再返回一个新的 Flux
```

适合场景：

- `collectList()` 后继续返回流；
- 等一个异步判断结果完成后，再决定返回哪个 Flux；
- `Mono<Response>` 变成 `Flux<Event>`。

例如：

```java
Flux<String> result = roundStream.collectList()
        .flatMapMany(outputs -> {
            if (outputs.isEmpty()) {
                return Flux.just("没有输出");
            }
            return nextRound();
        });
```

注意点：

- `flatMapMany` 前面通常是 `Mono`；
- 如果前面是 `Flux`，一般用 `flatMap`；
- 如果前面用了 `collectList()`，一定要记得它会等上游完整结束。

## 7. 过滤 API

### 7.1 filter

`filter` 根据条件保留元素。

每个元素都会进入 `Predicate` 判断，返回 `true` 的保留，返回 `false` 的丢弃。

```java
Flux.just("A", "", "B")
        .filter(StringUtils::isNotBlank);
```

结果：

```text
A, B
```

适合场景：

- 过滤空字符串；
- 过滤无效结果；
- 只保留特定类型状态；
- 根据权限、状态、条件筛选数据。

例如：

```java
flux.filter(msg -> msg.startsWith("data:"));
```

注意点：

- 被过滤掉的元素不会进入下游；
- 如果全部被过滤掉，下游会收到空流；
- 空流可以配合 `switchIfEmpty` 做兜底。

### 7.2 take

`take` 用来截取前 N 个元素。

当拿够数量后，它会取消上游订阅。

```java
Flux.range(1, 100)
        .take(3);
```

结果：

```text
1, 2, 3
```

适合场景：

- 只需要前几条数据；
- 给无限流设置结束条件；
- 调试时限制输出数量。

例如：

```java
Flux.interval(Duration.ofSeconds(1))
        .take(5);
```

这会让无限定时流只发 5 次。

### 7.3 skip

`skip` 用来跳过前 N 个元素。

前 N 个元素不会进入下游，从第 N+1 个开始继续发出。

```java
Flux.range(1, 5)
        .skip(2);
```

结果：

```text
3, 4, 5
```

适合场景：

- 分页；
- 跳过标题行；
- 忽略初始化阶段的数据；
- 丢掉不需要的前几个事件。

### 7.4 distinct

`distinct` 用来去重。

它会记住已经出现过的元素，后面重复出现的会被丢弃。

```java
Flux.just("A", "A", "B")
        .distinct();
```

结果：

```text
A, B
```

适合场景：

- 去重 id；
- 去重事件；
- 保证下游只处理一次。

注意点：

- `distinct` 需要记住历史值；
- 对很长的流或无限流，可能造成内存增长；
- 如果只想去掉连续重复，用 `distinctUntilChanged()`。

## 8. 收集 API

### 8.1 collectList

`collectList` 会等整个 Flux 完成后，把所有元素收集到一个 `List` 里。

返回类型是：

```java
Mono<List<T>>
```

```java
Flux.just("A", "B", "C")
        .collectList();
```

结果是：

```java
Mono<List<String>>
```

注意：`collectList()` 必须等上游完成后才会有结果。

所以：

```java
flux.collectList()
```

会破坏实时返回，因为它要等整条流结束。

适合场景：

- 需要完整结果后再判断；
- 聚合流式输出；
- 把多个 chunk 拼成完整字符串；
- 测试时收集结果。

你的 agent 代码里：

```java
shared.collectList().flatMapMany(outputs -> {
    String roundOutput = String.join("", outputs);
    ...
});
```

意思是：实时输出走 `shared`，判断分支用 `collectList()` 等完整结果。

注意点：

- 上游不完成，`collectList()` 就不会返回；
- 无限流上不能直接用；
- 数据很多时会占内存。

### 8.2 reduce

`reduce` 把多个元素一步步合并成一个最终结果。

它类似普通 Java 里的累加器。

```java
Flux.just(1, 2, 3)
        .reduce(0, Integer::sum);
```

结果：

```text
6
```

适合场景：

- 求和；
- 拼接字符串；
- 合并统计结果；
- 根据流中元素计算最终状态。

例如：

```java
Flux.just("A", "B", "C")
        .reduce("", (a, b) -> a + b);
```

结果：

```text
ABC
```

注意点：

- `reduce` 只在上游完成后发出最终结果；
- 如果你想每次累加都发出中间值，用 `scan`。

### 8.3 collectMap

`collectMap` 把流里的元素收集成 `Map`。

你需要告诉它 key 怎么来。

```java
Flux.just(user1, user2)
        .collectMap(User::getId);
```

返回类型：

```java
Mono<Map<K, User>>
```

适合场景：

- 根据 id 建索引；
- 把列表转换成 Map；
- 后续需要快速按 key 查询。

注意点：

- 如果 key 重复，后面的元素会覆盖前面的；
- 如果需要一个 key 对应多个值，用 `collectMultimap`。

## 9. 拼接和合并 API

### 9.1 concatWith

`concatWith` 用来顺序拼接两个流。

它会先完整执行当前流，当前流正常完成后，再订阅后面的流。

```java
Flux.just("A", "B")
        .concatWith(Flux.just("C", "D"));
```

结果：

```text
A, B, C, D
```

适合需要严格顺序的场景。

适合场景：

- 先返回一段内容，再返回另一段内容；
- 先执行准备流，再执行业务流；
- 多个阶段必须按顺序输出。

注意点：

- 前一个流不完成，后一个流永远不会开始；
- 如果前一个流报错，后一个流也不会执行，除非做错误恢复。

### 9.2 merge

`merge` 用来并发合并多个流。

它会同时订阅多个上游，哪个上游先产生元素，就先把哪个元素发给下游。

```java
Flux.merge(flux1, flux2);
```

结果可能交错：

```text
flux1-A, flux2-A, flux1-B, flux2-B
```

适合并发合并。

适合场景：

- 合并多个异步来源；
- 同时监听多个事件流；
- 实时输出分支 + 后台判断分支。

注意点：

- 不保证顺序；
- 多个流的输出可能交错；
- 如果其中一个流报错，整体 merge 通常会报错结束。

### 9.3 zip

`zip` 用来把多个流按位置配对。

它会等每个流都拿到一个元素后，组合成一个结果。

```java
Flux.zip(
        Flux.just("A", "B"),
        Flux.just(1, 2)
);
```

结果：

```text
(A,1), (B,2)
```

适合场景：

- 两个异步结果一一配对；
- 并行查多个信息后组合；
- 用户流和订单流按顺序组合。

注意点：

- 如果某个流比较慢，zip 会等它；
- 如果某个流提前结束，zip 整体也会结束；
- 它不是简单合并，而是“配对组合”。

### 9.4 thenMany

`thenMany` 会忽略前一个流产生的所有元素，只关心它是否完成。

前一个流完成后，再执行并返回后一个流。

```java
saveUser(user)
        .thenMany(findAllUsers());
```

适合场景：

- 先执行一个只关心完成状态的操作；
- 保存完成后查询列表；
- 初始化完成后开始输出数据。

注意点：

- 前一个流的元素会被丢弃；
- 如果前一个流报错，后一个流不会执行。

### 9.5 switchIfEmpty

`switchIfEmpty` 用于空流兜底。

如果上游没有发出任何元素就完成，它会切换到备用流。

```java
findUser(id)
        .switchIfEmpty(Mono.error(new RuntimeException("用户不存在")));
```

适合场景：

- 查不到数据时返回默认值；
- 查不到数据时抛业务异常；
- 搜索结果为空时返回提示。

例如：

```java
search(keyword)
        .switchIfEmpty(Flux.just("没有找到结果"));
```

注意点：

- 只有上游“空完成”才触发；
- 如果上游报错，不会触发 `switchIfEmpty`，需要 `onErrorResume`。

## 10. 副作用 API

副作用 API 不改变数据本身，主要用于日志、调试、统计、清理。

### 10.1 doOnNext

`doOnNext` 会在每个元素经过时执行一段副作用逻辑。

它不会改变元素本身，元素会原样继续传给下游。

```java
flux.doOnNext(item -> log.info("收到: {}", item));
```

适合场景：

- 打日志；
- 记录 trace；
- 统计数量；
- 观察流中间经过了什么数据。

例如：

```java
agentStream
        .doOnNext(trace::record)
        .map(MessageResolver::messageResolve);
```

这里 `doOnNext` 用来记录原始流事件，后面的 `map` 再做转换。

注意点：

- 不要在 `doOnNext` 里改变核心业务数据；
- 不要依赖它完成复杂业务逻辑；
- 如果副作用抛异常，流也会失败。

### 10.2 doOnError

`doOnError` 会在流发生错误时执行一段副作用逻辑。

它主要用于观察和记录错误。

```java
flux.doOnError(e -> log.error("失败", e));
```

注意：`doOnError` 只是观察错误，不会吞掉错误。

也就是说：

```java
flux.doOnError(e -> log.error("失败", e));
```

错误仍然会继续往下游传。

如果你想把错误转换成正常结果，要用：

```java
flux.onErrorResume(e -> Flux.just("失败提示"));
```

适合场景：

- 失败时清理临时状态；
- 记录错误日志；
- 记录监控指标。

注意点：

- `doOnError` 里不要再抛新异常，否则可能覆盖原始异常；
- 清理状态时建议写得非常简单。

### 10.3 doOnComplete

`doOnComplete` 会在流正常完成时执行。

注意，它只处理正常完成，不处理错误和取消。

```java
flux.doOnComplete(() -> log.info("完成"));
```

适合场景：

- 正常结束时打日志；
- 统计成功完成次数；
- 通知某个流程正常收尾。

注意点：

- 如果流出错，不会执行；
- 如果客户端取消订阅，也不会执行；
- 如果你想无论什么结束都执行，用 `doFinally`。

### 10.4 doFinally

`doFinally` 是最通用的收尾回调。

无论流是正常完成、异常结束，还是被取消，都会执行。

```java
flux.doFinally(signalType -> {
    log.info("结束信号: {}", signalType);
});
```

适合清理资源。

常见 `signalType`：

```text
ON_COMPLETE 正常完成
ON_ERROR    异常结束
CANCEL      下游取消
```

适合场景：

- 清理 Map；
- 释放临时资源；
- 关闭文件/连接；
- 记录最终结束状态。

例如：

```java
flux.doFinally(signal ->
        MemoryHelperUtil.normalizeUserTask.remove(conversationId)
);
```

注意点：

- 如果你只想错误时清理，用 `doOnError`；
- 如果完成、错误、取消都要清理，用 `doFinally` 更稳。

### 10.5 doOnSubscribe

`doOnSubscribe` 会在流被订阅时执行。

因为 Flux 是懒执行的，所以订阅通常意味着“这个流真的开始跑了”。

```java
flux.doOnSubscribe(s -> log.info("开始订阅"));
```

适合场景：

- 记录开始时间；
- 打印请求开始日志；
- 初始化一些观察状态。

注意点：

- WebFlux Controller 返回 Flux 时，真正订阅者通常是框架；
- 不要在这里做耗时业务逻辑。

## 11. 错误处理 API

### 11.1 onErrorReturn

`onErrorReturn` 用一个固定值兜底错误。

当上游出错时，它会发出这个默认值，然后正常完成。

```java
flux.onErrorReturn("默认结果");
```

适合场景：

- 简单降级；
- 出错时返回固定提示；
- 不关心具体异常类型。

例如：

```java
callRemote()
        .onErrorReturn("远程服务暂时不可用");
```

注意点：

- 它只能返回一个固定值；
- 如果你要根据异常类型决定返回内容，用 `onErrorResume`。

### 11.2 onErrorResume

`onErrorResume` 是最常用的错误恢复 API。

当上游出错时，它可以根据异常返回一个新的 `Mono/Flux`。

```java
flux.onErrorResume(e -> Flux.just("降级结果"));
```

适合：

- 调用外部服务失败；
- JSON 解析失败；
- 工具调用失败后给用户友好提示。

例如：

```java
return agentStream
        .onErrorResume(e -> Flux.just("执行失败：" + e.getMessage()));
```

也可以按异常类型分支：

```java
flux.onErrorResume(e -> {
    if (e instanceof TimeoutException) {
        return Flux.just("请求超时，请稍后重试");
    }
    return Flux.just("执行失败");
});
```

注意点：

- `onErrorResume` 会把错误转成正常流；
- 如果备用流也报错，错误仍然会继续传递。

### 11.3 onErrorMap

`onErrorMap` 用来转换异常。

它不会把错误恢复成正常结果，而是把一种异常包装或转换成另一种异常继续抛出。

```java
flux.onErrorMap(e -> new BusinessException("业务失败", e));
```

适合场景：

- 把底层异常转换成业务异常；
- 给异常补充上下文；
- 统一异常类型。

注意点：

- 它仍然是错误流；
- 如果你想恢复成正常输出，用 `onErrorResume`。

### 11.4 retry

`retry` 会在上游出错后重新订阅上游。

简单理解：失败了，从头再跑一次。

```java
flux.retry(3);
```

注意：只有上游重新订阅后有意义。对于有副作用的操作要谨慎，比如写文件、扣款、发消息。

适合场景：

- 短暂网络抖动；
- 偶发远程接口失败；
- 临时超时。

不适合场景：

- 写数据库；
- 写文件；
- 扣款；
- 发送不可重复消息。

因为这些操作重试可能造成重复副作用。

### 11.5 timeout

`timeout` 给流设置超时时间。

如果指定时间内没有收到下一个元素，就触发超时错误。

```java
flux.timeout(Duration.ofSeconds(10));
```

适合场景：

- 模型调用超时；
- 工具调用超时；
- 外部接口超时保护；
- 防止流无限等待。

可以配合 `onErrorResume`：

```java
flux.timeout(Duration.ofSeconds(10))
        .onErrorResume(TimeoutException.class,
                e -> Flux.just("执行超时"));
```

## 12. publish / share / cache

这些 API 和“订阅次数”有关，很容易踩坑。

### 12.1 冷流

大多数 Flux 默认是冷流：每订阅一次，上游就重新执行一次。

```java
Flux<String> flux = callRemoteApi();

flux.subscribe();
flux.subscribe();
```

可能会调用两次远程接口。

### 12.2 publish

`publish` 可以让多个下游分支共享同一次上游订阅。

这是理解你当前代码的关键 API。

如果没有 `publish`，同一个冷流被多个分支使用时，可能会导致上游执行多次。

你的场景：

```java
return roundStream.publish(shared -> Flux.merge(
        shared,
        shared.collectList().flatMapMany(outputs -> ...)
));
```

含义：

```text
shared 分支 1：实时返回给前端
shared 分支 2：收集完整输出，等本轮结束后判断是否进入下一轮
```

关键好处：

```text
不会因为 shared 和 collectList 两个分支而把 agent 执行两遍
```

你的代码里：

```java
return roundStream.publish(shared -> Flux.merge(
        shared,
        shared.collectList().flatMapMany(outputs -> ...)
));
```

含义是：

```text
roundStream 只执行一遍
shared 分支实时返回内容
collectList 分支等结束后拿完整内容做判断
```

注意点：

- `publish` 的 transformer 里可以安全地多次使用 `shared`；
- 判断分支不要重复返回已经由实时分支发出的内容；
- 如果只是简单复用结果，也可以考虑 `cache`，但 `cache` 会缓存数据。

### 12.3 share

`share` 会把冷流转换成共享热流。

多个订阅者会共享同一个正在运行的上游。

```java
Flux<String> shared = flux.share();
```

适合简单共享，但订阅时机更敏感。

适合场景：

- 多个订阅者监听同一个实时事件源；
- 简单广播；
- 不需要给后来者补历史数据。

注意点：

- 订阅晚的人可能错过之前的数据；
- 如果所有订阅者都取消，上游可能停止；
- 对于“一个方法内部多分支共享”，`publish(transformer)` 通常更清晰。

### 12.4 cache

`cache` 会缓存上游已经产生的数据。

后续订阅者再次订阅时，可以直接拿到缓存内容，而不是重新执行上游。

```java
Flux<String> cached = flux.cache();
```

慎用：数据多时可能占内存。

适合场景：

- 查询结果可复用；
- 多个订阅者都需要完整历史；
- 上游调用成本高，不希望重复执行。

注意点：

- 默认可能缓存所有元素；
- 对无限流或大流很危险；
- 可以使用带参数的重载限制缓存数量或缓存时间。

## 13. 调度器 Scheduler

Reactor 默认不强制切线程。你可以用调度器指定在哪类线程执行。

### 13.1 subscribeOn

`subscribeOn` 指定上游从哪个线程开始执行。

它影响的是“订阅动作”和源头附近的执行线程。
因为它的含义是订阅某个流到某个线程上，订阅后就会导致这个流在指定线程上运行。

```java
flux.subscribeOn(Schedulers.boundedElastic());
```

常用于阻塞任务：

- 文件 IO；
- JDBC；
- 调用阻塞 SDK；
- `.block()` 包装。

例如，把阻塞调用包进响应式流：

```java
Mono.fromCallable(() -> blockingReadFile())
        .subscribeOn(Schedulers.boundedElastic());
```

注意点：

- 一个链路里多个 `subscribeOn`，通常最靠近源头生效；
- 它不等于马上执行，仍然要订阅后才执行；
- 阻塞 IO 优先用 `boundedElastic`，不要用 `parallel`。

### 13.2 publishOn

`publishOn` 会切换它后面操作的执行线程。
它的含义是在某个线程上发布/公开一个流，那么后续订阅该流的肯定就只能在指定线程上运行。
而订阅流之后流本身的处理是不受指定线程影响的，应为它是上游。它被发布到下游后，才会影响下游处理

```java
flux.publishOn(Schedulers.parallel())
        .map(this::cpuWork);
```

上面表示：`publishOn` 之后的 `map` 会在 `parallel` 调度器上执行。

适合场景：

- 上游是 IO，后面要做 CPU 计算；
- 某一段操作需要切换线程；
- 分隔不同执行阶段。

注意点：

- `publishOn` 只影响它后面的操作；
- 每次切线程都有成本，不要乱用；
- CPU 密集型用 `parallel`，阻塞 IO 用 `boundedElastic`。

### 13.3 常用 Scheduler

| Scheduler | 适合场景 |
---|---|
| `Schedulers.parallel()` | CPU 密集型任务 |
| `Schedulers.boundedElastic()` | 阻塞 IO |
| `Schedulers.single()` | 单线程顺序执行 |
| `Schedulers.immediate()` | 当前线程 |

## 14. 背压

背压是指：下游处理不过来时，如何控制上游生产速度。

常见 API：

```java
onBackpressureBuffer()
onBackpressureDrop()
onBackpressureLatest()
limitRate(n)
```

### 14.1 onBackpressureBuffer

`onBackpressureBuffer` 表示下游处理不过来时，先把数据缓存起来。

```java
flux.onBackpressureBuffer();
```

风险：缓存太多会占内存。

适合场景：

- 不想丢数据；
- 短时间流量突增；
- 下游偶尔处理慢，但最终能追上。

注意点：

- 如果上游持续比下游快，缓存会越来越大；
- 建议使用带容量限制的重载；
- 超过容量后要设计丢弃或报错策略。

### 14.2 onBackpressureDrop

`onBackpressureDrop` 表示下游处理不过来时，直接丢弃多余数据。

```java
flux.onBackpressureDrop();
```

适合不要求每条都处理的场景，比如实时指标。

适合场景：

- 实时指标；
- 高频日志；
- 鼠标移动事件；
- 只关心大概趋势，不要求每条都到达。

注意点：

- 会丢数据；
- 不适合订单、支付、文件写入等不能丢的业务。

### 14.3 limitRate

`limitRate` 用来控制下游向上游请求数据的节奏。

它不是简单限速器，而是调整 Reactive Streams 的 request 批量大小。

```java
flux.limitRate(100);
```

意思是下游按一定批量向上游请求数据，避免一次性请求太多。

适合场景：

- 调试背压；
- 控制内存；
- 避免上游一次性推太多数据。

注意点：

- 它控制的是请求批量，不是“每秒多少条”；
- 如果要按时间限速，需要用 `delayElements` 等 API。

## 15. block 的使用

`block()` 会把异步流变成同步等待。

它会阻塞当前线程，直到结果出来或发生错误。

```java
String result = mono.block();
```

在 WebFlux 主链路里应尽量避免 `block()`，否则会阻塞响应线程。

但在以下场景可以谨慎使用：

- 非响应式项目；
- 启动初始化；
- 测试；
- 确定跑在 `boundedElastic` 等可阻塞线程上。

你的代码里：

```java
chatClient.prompt()
        .stream()
        .content()
        .collectList()
        .block();
```

这是同步等待模型判断结果。逻辑简单，但会阻塞当前执行线程。如果后续并发量变高，可以改成全响应式链路。

几个容易混淆的 API：

```java
mono.block();       // 等 Mono 的唯一结果
flux.blockFirst();  // 等 Flux 的第一个元素
flux.blockLast();   // 等 Flux 的最后一个元素
```

注意：流式文本不能用 `blockFirst()` 拿完整内容。

例如模型流式返回：

```text
用户
提出
了
一个
需求
```

如果你写：

```java
String text = chatClient.prompt()
        .stream()
        .content()
        .blockFirst();
```

可能只拿到：

```text
用户
```

如果要拿完整内容，应该：

```java
List<String> chunks = chatClient.prompt()
        .stream()
        .content()
        .collectList()
        .block();

String text = chunks == null ? "" : String.join("", chunks);
```

如果不需要流式，直接用：

```java
String text = chatClient.prompt()
        .user("...")
        .call()
        .content();
```

## 16. 常用组合套路

### 16.1 实时返回，同时收集完整结果

这是你当前 agent 场景最重要的模式。

需求：

```text
本轮内容要实时返回给前端
但必须等本轮完整结束后，才能判断是否进入下一轮
```

写法：

```java
Flux<String> roundStream = agent.stream(input)
        .map(this::resolve)
        .filter(StringUtils::isNotBlank);

return roundStream.publish(shared -> Flux.merge(
        shared,
        shared.collectList().flatMapMany(outputs -> {
            String roundOutput = String.join("", outputs);

            if (isCompleted(roundOutput)) {
                return Flux.empty();
            }

            return runNextRound();
        })
));
```

注意：判断分支里不要再返回 `roundOutput`，因为 `shared` 已经实时返回过了。

### 16.2 严格顺序拼接

```java
return firstFlux.concatWith(secondFlux);
```

表示：

```text
firstFlux 完成后，再执行 secondFlux
```

### 16.3 出错后返回友好提示

```java
return service.stream()
        .onErrorResume(e -> Flux.just("执行失败：" + e.getMessage()));
```

### 16.4 空结果兜底

```java
return search(keyword)
        .switchIfEmpty(Flux.just("没有找到结果"));
```

### 16.5 阻塞操作放到弹性线程池

```java
Mono.fromCallable(() -> blockingCall())
        .subscribeOn(Schedulers.boundedElastic());
```

## 17. 你当前代码里的 Flux 流程解释

你的核心逻辑可以理解成：

```text
1. 调用 agent，得到 roundStream
2. roundStream 实时返回给前端
3. 同时 collectList 收集完整输出
4. 本轮结束后判断任务是否完成
5. 完成：清理任务状态，不再返回重复内容
6. 未完成：构造下一轮 prompt，递归调用 runAgentRound
7. 达到最大轮次：追加停止说明
```

伪流程：

```java
return roundStream.publish(shared -> Flux.merge(
        shared,
        shared.collectList().flatMapMany(outputs -> {
            if (completed) {
                return Flux.empty();
            }

            if (maxLoop) {
                return Flux.just("达到最大轮次");
            }

            return runAgentRound(nextInput, config, round + 1);
        })
));
```

这个模式的核心是：

```text
实时输出和完整收集同时进行
递归下一轮必须等 collectList 完成
完成分支不能重复返回本轮内容
```

## 18. 常见坑

### 坑 1：collectList 会让流不再实时

```java
return flux.collectList().flatMapMany(...);
```

这会等 flux 完成后才继续。

如果要实时输出，同时收集完整结果，用：

```java
flux.publish(shared -> Flux.merge(
        shared,
        shared.collectList().flatMapMany(...)
));
```

### 坑 2：一个冷流被订阅两次，上游会执行两次

```java
Flux<String> flux = callAgent();

Flux.merge(
        flux,
        flux.collectList().flatMapMany(...)
);
```

这可能导致 `callAgent()` 执行两遍。

用 `publish` 共享一次订阅：

```java
flux.publish(shared -> Flux.merge(
        shared,
        shared.collectList().flatMapMany(...)
));
```

### 坑 3：doOnError 不会吞错误

```java
flux.doOnError(e -> log.error("失败", e));
```

错误还是会继续传递。

如果要恢复：

```java
flux.onErrorResume(e -> Flux.just("失败提示"));
```

### 坑 4：flatMap 不保证顺序

```java
flux.flatMap(this::asyncCall);
```

如果需要顺序：

```java
flux.concatMap(this::asyncCall);
```

### 坑 5：完成分支重复返回

如果你已经实时返回：

```java
shared
```

那么判断完成后应该：

```java
return Flux.empty();
```

不要：

```java
return Flux.just(roundOutput);
```

否则会重复输出。

## 19. API 速查表

这一节适合写代码时快速查。重点看三列：它返回什么、具体做什么、什么时候用。

### 19.1 创建类 API

| API | 返回类型 | 功能说明 | 使用场景 / 注意点 |
|---|---|---|---|
| `Flux.just(T...)` | `Flux<T>` | 把已经存在的几个值包装成一个 Flux，订阅后按传入顺序依次发出。 | 适合测试、固定响应、少量已知数据。传入的值在创建时就已经确定。 |
| `Flux.fromIterable(Iterable<T>)` | `Flux<T>` | 把 `List`、`Set` 等集合转换成 Flux，订阅后逐个发出集合元素。 | 适合集合转响应式流。注意集合本身已经在内存里，不适合超大数据一次性加载。 |
| `Flux.empty()` | `Flux<T>` | 创建一个不发出任何元素、直接 `onComplete` 的空流。 | 常用于“无需返回内容”“完成后不要重复输出”。比如实时分支已输出，判断分支返回 `Flux.empty()`。 |
| `Flux.error(Throwable)` | `Flux<T>` | 创建一个订阅后直接 `onError` 的错误流。 | 用于把异常作为响应式错误传递，而不是直接 `throw`。常配合 `switchIfEmpty`、`onErrorResume`。 |
| `Flux.range(start, count)` | `Flux<Integer>` | 从 `start` 开始连续发出 `count` 个整数。 | 适合分页模拟、测试、批量任务编号。`Flux.range(1, 3)` 发出 `1,2,3`。 |
| `Flux.interval(Duration)` | `Flux<Long>` | 按固定时间间隔不断发出递增数字，从 `0` 开始。 | 适合心跳、定时轮询、模拟流式数据。默认不会自己停止，通常配合 `take`。 |
| `Flux.defer(Supplier<Publisher<T>>)` | `Flux<T>` | 等到每次订阅时才执行 Supplier 创建真正的流。 | 适合“每次订阅都要重新计算/重新读取状态”的场景。避免在 Flux 创建时提前固定值。 |
| `Flux.create(...)` | `Flux<T>` | 手动通过 `sink.next/error/complete` 推送数据。 | 适合包装回调式 API。要小心线程安全和背压。 |
| `Flux.generate(...)` | `Flux<T>` | 同步地一轮生成一个元素，可带状态。 | 适合按状态逐步生成数据。每次回调最多 `next` 一次。 |

### 19.2 转换类 API

| API | 返回类型 | 功能说明 | 使用场景 / 注意点 |
|---|---|---|---|
| `map(Function<T,R>)` | `Flux<R>` | 对每个元素做一对一同步转换。输入一个元素，输出一个新元素。 | 最常用转换 API。适合字段提取、DTO 转换、字符串处理。不能返回 `Mono/Flux`，否则会变成嵌套流。 |
| `flatMap(Function<T, Publisher<R>>)` | `Flux<R>` | 把每个元素转换成一个 `Mono/Flux`，再把内部结果摊平成一个 Flux。 | 适合异步调用数据库/HTTP/工具。可能并发执行，结果顺序不保证。 |
| `concatMap(Function<T, Publisher<R>>)` | `Flux<R>` | 和 `flatMap` 类似，但严格按上游顺序执行：前一个内部流完成后才处理下一个。 | 适合必须顺序执行的外部操作，比如按步骤修改文件、顺序调用工具。吞吐比 `flatMap` 低。 |
| `flatMapSequential(...)` | `Flux<R>` | 内部可以并发执行，但最终按原始顺序向下游发出。 | 适合既想并发提高速度，又想保持输出顺序的场景。 |
| `flatMapMany(...)` | `Flux<R>` | 常用于 `Mono<T>` 转 `Flux<R>`。根据 Mono 的结果返回一个 Flux。 | 典型写法：`collectList().flatMapMany(list -> ...)`，等收集完成后再展开新流。 |
| `cast(Class<R>)` | `Flux<R>` | 把元素强转为指定类型。 | 适合上游是父类/接口，确定实际类型时使用。类型不匹配会抛 `ClassCastException`。 |
| `ofType(Class<R>)` | `Flux<R>` | 只保留指定类型的元素，并自动转换类型。 | 比 `filter + cast` 更简洁，适合混合事件流中过滤某类事件。 |
| `index()` | `Flux<Tuple2<Long,T>>` | 给每个元素附加从 0 开始的序号。 | 适合日志、排序、标记第几个 chunk。 |

### 19.3 过滤和截取 API

| API | 返回类型 | 功能说明 | 使用场景 / 注意点 |
|---|---|---|---|
| `filter(Predicate<T>)` | `Flux<T>` | 只保留满足条件的元素，不满足的元素被丢弃。 | 常用于过滤空字符串、过滤无效事件、只保留特定状态。 |
| `take(n)` | `Flux<T>` | 只取前 `n` 个元素，取够后取消上游。 | 适合只要前几条结果，或给无限流加结束条件。 |
| `take(Duration)` | `Flux<T>` | 只取指定时间窗口内产生的元素。 | 适合采样一段时间的数据流。 |
| `skip(n)` | `Flux<T>` | 跳过前 `n` 个元素，之后的元素继续发出。 | 适合分页、忽略开头的初始化事件。 |
| `skip(Duration)` | `Flux<T>` | 跳过一段时间内产生的元素。 | 适合忽略刚启动时的一批不稳定数据。 |
| `distinct()` | `Flux<T>` | 对整个流去重，只发出第一次出现的值。 | 会记住历史元素，长流可能占内存。 |
| `distinctUntilChanged()` | `Flux<T>` | 只去掉连续重复的元素。 | 适合状态变化流，比如 `A,A,B,B,A` 变成 `A,B,A`。内存压力比 `distinct` 小。 |
| `next()` | `Mono<T>` | 取第一个元素，然后取消上游。 | 类似 `blockFirst` 的响应式版本。适合只关心第一条结果。 |

### 19.4 收集和聚合 API

| API | 返回类型 | 功能说明 | 使用场景 / 注意点 |
|---|---|---|---|
| `collectList()` | `Mono<List<T>>` | 等上游完成后，把所有元素收集成一个 List。 | 会等完整流结束，不适合直接用于实时响应主链路。适合“流式返回同时后台判断”里的判断分支。 |
| `collectMap(keyMapper)` | `Mono<Map<K,T>>` | 把元素收集成 Map，key 由元素计算得到，value 默认是元素本身。 | key 重复时后面的值会覆盖前面的值。 |
| `collectMultimap(keyMapper)` | `Mono<Map<K, Collection<T>>>` | 按 key 分组收集，一个 key 对应多个元素。 | 适合分类统计、按类型归档。 |
| `reduce(initial, accumulator)` | `Mono<T>` | 把多个元素逐步合并成一个结果。 | 适合求和、拼接、累积状态。只在上游完成后产生最终值。 |
| `scan(initial, accumulator)` | `Flux<T>` | 类似 `reduce`，但每次累积后都会发出中间结果。 | 适合实时展示累计进度，比如当前总数、当前状态。 |
| `count()` | `Mono<Long>` | 统计元素数量。 | 要等上游完成。无限流上不会返回。 |
| `all(predicate)` | `Mono<Boolean>` | 判断所有元素是否都满足条件。 | 遇到第一个不满足的元素可提前结束。 |
| `any(predicate)` | `Mono<Boolean>` | 判断是否至少有一个元素满足条件。 | 遇到第一个满足的元素可提前结束。 |

### 19.5 拼接、合并和组合 API

| API | 返回类型 | 功能说明 | 使用场景 / 注意点 |
|---|---|---|---|
| `concatWith(Publisher<T>)` | `Flux<T>` | 当前流正常完成后，再订阅并发出下一个流。 | 严格顺序拼接。适合“先输出 A，再输出 B”。如果前一个流不完成，后一个永远不会开始。 |
| `Flux.concat(...)` | `Flux<T>` | 按顺序订阅多个流，一个完成后再下一个。 | 和 `concatWith` 类似，但适合多个流。 |
| `Flux.merge(...)` | `Flux<T>` | 同时订阅多个流，谁先产生就先向下游发谁。 | 适合并发合并。输出可能交错，不保证顺序。 |
| `mergeWith(Publisher<T>)` | `Flux<T>` | 当前流和另一个流并发合并。 | 两个流同时跑，常用于合并实时数据和后台判断结果。 |
| `zip` | `Flux<TupleN>` | 多个流按位置配对：第 1 个和第 1 个组，第 2 个和第 2 个组。 | 适合多个来源结果一一对应。任一流提前完成，zip 也会结束。 |
| `combineLatest` | `Flux<R>` | 多个流任意一个更新时，用每个流的最新值组合出结果。 | 适合实时仪表盘、多个状态源组合。 |
| `switchIfEmpty(Publisher<T>)` | `Flux<T>` | 如果上游没有任何元素就完成，则切换到备用流。 | 适合空结果兜底，比如查不到用户就返回默认提示。 |
| `then()` | `Mono<Void>` | 忽略当前流所有元素，只关心它是否完成。 | 适合“执行完即可”，不关心结果内容。 |
| `thenMany(Publisher<R>)` | `Flux<R>` | 忽略当前流元素，等当前流完成后执行另一个流。 | 适合先保存/校验，再查询/返回另一个流。 |

### 19.6 副作用和生命周期 API

| API | 返回类型 | 功能说明 | 使用场景 / 注意点 |
|---|---|---|---|
| `doOnNext(Consumer<T>)` | `Flux<T>` | 每个元素经过时执行副作用，不改变元素。 | 适合日志、统计、记录 trace。不要在里面写复杂业务转换。 |
| `doOnError(Consumer<Throwable>)` | `Flux<T>` | 流出错时执行副作用。 | 只观察错误，不会吞掉错误。要恢复请用 `onErrorResume`。 |
| `doOnComplete(Runnable)` | `Flux<T>` | 上游正常完成时执行。 | 只在正常完成时触发，错误和取消不会触发。 |
| `doFinally(Consumer<SignalType>)` | `Flux<T>` | 无论正常完成、错误、取消都会执行。 | 最适合释放资源、清理 Map、关闭句柄。 |
| `doOnSubscribe(Consumer<Subscription>)` | `Flux<T>` | 被订阅时执行。 | 适合记录开始时间、打印订阅日志。 |
| `doOnCancel(Runnable)` | `Flux<T>` | 下游取消订阅时执行。 | 浏览器断开 SSE、客户端取消请求时可能触发。 |
| `doOnRequest(LongConsumer)` | `Flux<T>` | 下游请求更多数据时执行。 | 调试背压时有用。 |
| `log()` | `Flux<T>` | 打印 Reactor 信号日志。 | 调试用，生产环境慎开，日志量可能很大。 |

### 19.7 错误处理 API

| API | 返回类型 | 功能说明 | 使用场景 / 注意点 |
|---|---|---|---|
| `onErrorReturn(value)` | `Flux<T>` | 出错时用一个固定默认值结束流。 | 适合简单兜底，但拿不到异常细节做分支判断。 |
| `onErrorResume(fn)` | `Flux<T>` | 出错时根据异常切换到另一个 `Publisher`。 | 最常用错误恢复 API。可按异常类型返回不同提示或备用数据。 |
| `onErrorMap(fn)` | `Flux<T>` | 把原始异常转换成新的异常继续抛。 | 适合把底层异常包装成业务异常。 |
| `onErrorContinue(...)` | `Flux<T>` | 某个元素处理失败时跳过该元素，继续处理后续元素。 | 容易隐藏问题，谨慎使用。不是所有操作符都支持。 |
| `retry(n)` | `Flux<T>` | 出错后重新订阅上游，最多重试 n 次。 | 有副作用的操作要小心，比如写文件、扣款、发消息可能重复执行。 |
| `retryWhen(...)` | `Flux<T>` | 按自定义策略重试，比如延迟、指数退避。 | 适合远程接口短暂失败。 |
| `timeout(Duration)` | `Flux<T>` | 指定时间内没有新元素就报超时错误。 | 适合外部服务、流式模型、工具调用设置保护。 |

### 19.8 共享、缓存和订阅 API

| API | 返回类型 | 功能说明 | 使用场景 / 注意点 |
|---|---|---|---|
| `publish(transformer)` | `Flux<R>` | 在 transformer 内让多个分支共享同一次上游订阅。 | 适合“实时输出 + 同时收集完整结果”。避免冷流被订阅两次导致上游执行两遍。 |
| `share()` | `Flux<T>` | 把冷流转成共享热流，多个订阅者共享正在发生的流。 | 订阅晚的人可能错过之前的数据。适合实时广播，不适合需要回放的场景。 |
| `cache()` | `Flux<T>` | 缓存已经发出的数据，后续订阅者可以收到缓存内容。 | 适合结果复用。数据很多或无限流慎用，可能占大量内存。 |
| `replay(n)` | `ConnectableFlux<T>` | 回放最近 n 个元素给新订阅者。 | 比 `cache()` 更可控。需要理解 connect/refCount。 |
| `subscribe(...)` | `Disposable` | 手动订阅流，触发执行。 | WebFlux Controller 中通常不要手动订阅，直接 return Flux。 |
| `block()` | `T` | 阻塞当前线程等待 Mono 结果。 | 响应式主链路里尽量少用。摘要、判断等同步辅助逻辑可谨慎使用。 |
| `blockFirst()` | `T` | 阻塞等待 Flux 第一个元素。 | 流式模型返回时只会拿第一个 chunk，容易出现摘要只拿到“用户”这种问题。 |
| `blockLast()` | `T` | 阻塞等待 Flux 最后一个元素。 | 不等于收集完整文本。流式文本要用 `collectList().block()` 后 `String.join`。 |

### 19.9 线程和调度 API

| API | 返回类型 | 功能说明 | 使用场景 / 注意点 |
|---|---|---|---|
| `subscribeOn(Scheduler)` | `Flux<T>` | 指定上游订阅和源头执行所在线程。 | 通常放靠近源头位置。适合把阻塞调用放到 `boundedElastic`。 |
| `publishOn(Scheduler)` | `Flux<T>` | 从当前位置之后，切换下游操作执行线程。 | 影响它后面的 `map/filter/doOnNext` 等操作。 |
| `Schedulers.parallel()` | `Scheduler` | 固定大小线程池，适合 CPU 密集型任务。 | 不适合阻塞 IO。 |
| `Schedulers.boundedElastic()` | `Scheduler` | 可弹性增长、有上限的线程池，适合阻塞 IO。 | 包装 JDBC、文件 IO、阻塞 SDK、`.block()` 辅助逻辑。 |
| `Schedulers.single()` | `Scheduler` | 单线程调度器。 | 适合必须串行执行的任务。 |

### 19.10 背压 API

| API | 返回类型 | 功能说明 | 使用场景 / 注意点 |
|---|---|---|---|
| `onBackpressureBuffer()` | `Flux<T>` | 下游处理不过来时，把上游数据先缓存起来。 | 不丢数据，但可能占内存。可设置容量和溢出策略。 |
| `onBackpressureDrop()` | `Flux<T>` | 下游处理不过来时直接丢弃新来的数据。 | 适合实时指标、日志采样等允许丢数据的场景。 |
| `onBackpressureLatest()` | `Flux<T>` | 下游忙时只保留最新元素，旧的未处理元素被替换。 | 适合状态刷新，比如只关心最新进度。 |
| `limitRate(n)` | `Flux<T>` | 控制下游每次向上游请求的数据量。 | 用于调节吞吐和内存压力，调试背压时常用。 |

## 20. 学习顺序建议

建议按这个顺序掌握：

```text
1. Flux / Mono 区别
2. onNext / onError / onComplete
3. map / flatMap / concatMap
4. collectList 为什么会阻塞实时输出
5. concatWith / merge 区别
6. publish 解决多分支共享
7. doOnError / onErrorResume 区别
8. subscribeOn / publishOn
9. 背压
```

掌握到第 6 步，你现在这类 agent 流式递归代码就基本能写稳了。
