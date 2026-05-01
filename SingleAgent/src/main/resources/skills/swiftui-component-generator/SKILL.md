---
name: swiftui-component-generator
description: 基于 SwiftUI 组件开发规范生成、重构和补齐 iOS 16+ 组件库代码，覆盖 Hz 命名/API 规范、主题 Token 接入、Preview/Demo/README 同步以及 Pixso MCP（getDSL/exportImage）驱动还原。仅用于可复用组件库场景，不用于页面级业务编排。
---

# SwiftUI 组件生成

按统一规范生成或改造 SwiftUI 组件，确保命名、API、主题 Token、设计稿还原和交付同步一致。

只覆盖 SwiftUI / iOS 16+ 组件库代码。如果需求是页面编排、导航容器或业务状态流转，停止使用本 skill 并切换页面类 skill。

## 使用边界

适用：

- 基础组件
- 复合组件
- 组件库共享能力
- 组件 Preview、Demo、README 同步

不适用：

- 页面级 `View`
- Feature 页面与业务流程编排
- 导航容器
- 数据加载与业务异步状态管理

## 执行流程

### 流程 A：输入包含 Pixso 链接或节点 ID（强制）

1. 识别并确认目标节点。
2. 必须先调用 `getDSL`，获取结构、布局、文本、状态和视觉语义。
3. 先完成 DSL -> SwiftUI + Token 映射，优先代码还原。
4. 仅当元素无法通过 SwiftUI + Token 精确还原时调用 `exportImage`。
5. 导出资源后继续组件实现，颜色与尺寸仍走 token。
6. 若导图失败，保留可编译 fallback，并在交付说明标注未完成资产位。
7. 结束前执行颜色/交付检查脚本。

### 流程 B：输入不包含 Pixso 设计稿

1. 基于现有组件模式和 token 体系设计 API。
2. 使用模板结构实现组件。
3. 同步 Preview、Demo、README。
4. 执行颜色/交付检查脚本。

## 命名与 API 规则（硬约束）

- 公开基础组件类型必须使用 `Hz` 前缀，如 `HzButton`、`HzDialog`。
- 公开便捷 modifier 必须使用 `hz` 前缀小驼峰，如 `.hzDialog(...)`、`.hzToastHost(...)`。
- 禁止新增无前缀公开组件名（如 `Dialog`、`Toast`）或保留新旧命名并存。
- `Public Chain API` 默认使用内部 `updating(_:)` helper 实现，统一“复制 - 修改 - 返回”写法。
- 属性包装器只用于值约束与归一化，例如 `@Clamped`、`@NilIfBlank`；不要用于生成链式 API。
- 只有 fluent setter 会修改到的存储属性才允许声明为 `private var`。
- 其余不会被 fluent setter 修改的存储属性一律保持 `private let`，不要为了风格统一把全部字段改成 `var`。
- 对外 API 顺序固定：
  1. `Public Types`
  2. `Public Init`
  3. `Public View`
  4. `Public Chain API`
  5. `Private Storage`
  6. `Private Dependencies`
  7. `Private Helpers`
  8. `#Preview`
- `init` 只保留必需参数和必要回调，可选能力优先链式 API。

## 模板基线（对齐 HzButton）

- 默认按“主 `public init` + 私有完整构造 + `updating(_:)` fluent setter”模式组织可选能力。
- 组件 body 只做组合，不把复杂视觉状态分支散落在 `body` 外。
- 默认从 `theme.colors.*` 与 `theme.components.*` 读取 token。
- 长文案策略必须显式指定：`lineLimit`、`truncationMode`、`multilineTextAlignment`。

## 颜色规则（严格模式）

默认颜色优先级：

1. 显式覆盖参数（如 `.style(...)` 或 `.color(...)`）
2. `theme.colors` 语义 token
3. `.clear`（仅用于“透明/无填充”语义）

禁止项（组件主实现中）：

- `Color(...)`
- `Color.white` / `Color.black`
- `: Color = .white/.black/.secondary`
- `.foregroundStyle(.secondary)`
- 过时 API：`theme.colors.primary/secondary/onPrimary`

允许例外：

- `.clear`
- `#Preview` 内演示色

## 交付同步门禁

当新增组件或修改组件公开 API / 推荐用法时，必须同时满足：

- 对应 Demo 页面已新增或更新
- Demo 组件列表入口已更新（新增组件时）
- README 示例与接入说明已同步

仅内部实现重构且不影响公开 API 时，可跳过 README 更新，但必须在检查命令中声明 `--internal-only --note "原因"`。

## 执行命令

在组件仓库根目录运行：

```bash
python3 skills/swiftui-component-generator/scripts/check_component_color_rules.py --path Sources/Components
python3 skills/swiftui-component-generator/scripts/check_component_delivery_sync.py --repo-root .
```

内部重构场景：

```bash
python3 skills/swiftui-component-generator/scripts/check_component_delivery_sync.py --repo-root . --internal-only --note "仅重构内部实现，不影响公开 API"
```

## 资源

- 规则摘要：`references/swiftui-rules.md`
- 交付检查：`references/delivery-checklist.md`
- 组件模板：`assets/component_template.swift.txt`
- 颜色检查脚本：`scripts/check_component_color_rules.py`
- 交付同步检查脚本：`scripts/check_component_delivery_sync.py`
