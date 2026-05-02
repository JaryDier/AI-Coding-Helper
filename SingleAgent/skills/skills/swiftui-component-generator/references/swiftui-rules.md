# SwiftUI 组件规范摘要（严格版）

## 1. 组件定位

- 只用于可复用组件库能力，不处理页面业务编排。
- 基础组件不得绑定业务模型或业务单例。
- 复合组件可承载轻量语义，但不要直接耦合接口层 DTO。

## 2. 命名与公开接口

- 公开基础组件类型必须使用 `Hz` 前缀。
- 公开便捷 modifier 必须使用 `hz` 前缀小驼峰。
- 禁止无前缀公开组件名，禁止保留新旧命名并存。
- `Public Chain API` 默认使用 `updating(_:)`，不要在每个 setter 中重复写 `var copy = self`。
- 属性包装器只负责值清洗和约束，不负责生成 fluent API。
- 只有 fluent setter 会修改到的存储属性才允许使用 `private var`。
- 其余不会被 fluent setter 修改的构造字段应保持 `private let`。

推荐 API 顺序：

1. `Public Types`
2. `Public Init`
3. `Public View`
4. `Public Chain API`
5. `Private Storage`
6. `Private Dependencies`
7. `Private Helpers`
8. `#Preview`

## 3. Pixso MCP 规则

当输入包含 Pixso 链接或节点 ID 时，必须使用以下顺序：

1. `getDSL`
2. DSL -> SwiftUI + Token 映射
3. 按需 `exportImage`
4. 资源接入 + fallback
5. 运行检查脚本

约束：

- 禁止先 `exportImage` 再反推布局。
- 可代码表达的视觉必须优先代码还原，不导图。
- 仅复杂插画/纹理/异形位图使用 `exportImage`。

## 4. 颜色与 Token（严格模式）

默认颜色优先级：

1. 显式覆盖参数
2. `theme.colors` token
3. `.clear`（仅透明语义）

禁止项（组件主实现）：

- `Color(...)`
- `Color.white` / `Color.black`
- `: Color = .white/.black/.secondary`
- `.foregroundStyle(.secondary)`
- `theme.colors.primary/secondary/onPrimary`

允许例外：

- `.clear`
- `#Preview` 演示色

## 5. Demo 与 README 门禁

当新增组件或变更公开 API/推荐用法时，必须同步：

- 对应 Demo 页面
- Demo 组件列表入口（新增组件）
- README 示例与接入说明

仅内部重构可跳过 README，但要显式声明原因并通过脚本参数传入。

## 6. 可执行检查

```bash
python3 skills/swiftui-component-generator/scripts/check_component_color_rules.py --path Sources/Components
python3 skills/swiftui-component-generator/scripts/check_component_delivery_sync.py --repo-root .
```

## 7. Chain API 模式

推荐 fluent setter 写法：

```swift
public func enabled(_ isEnabled: Bool = true) -> Self {
    updating { $0.isEnabled = isEnabled }
}
```

约束：

- 不推荐 `var copy = self` + 逐字段赋值在每个 setter 中重复展开。
- 不推荐继续新增 `private func copy(...)` 大签名模式。
- `updating(_:)` 仅作为内部 helper 使用，不暴露为公开 API。
- 所有会被 `updating(_:)` 写入的字段必须声明为 `private var`。
- 不会被 `updating(_:)` 写入的字段继续使用 `private let`，保持不可变语义。
