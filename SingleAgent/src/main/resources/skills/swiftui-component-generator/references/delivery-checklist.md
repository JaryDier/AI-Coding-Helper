# 交付检查清单（强制）

## 1. 命名与结构

- 公开组件是否使用 `Hz` 前缀。
- 公开 modifier 是否使用 `.hzXxx(...)`。
- 代码结构是否遵循固定顺序：`Public Types -> Public Init -> Public View -> Public Chain API -> Private Storage -> Private Dependencies -> Private Helpers -> #Preview`。
- `init` 是否只保留必需参数，可选能力是否由链式 API 承接。

## 2. Pixso 流程

- 若输入含设计稿，是否先调用 `getDSL`。
- 是否先完成 DSL -> SwiftUI/token 映射，再决定是否 `exportImage`。
- `exportImage` 是否仅用于无法代码还原的元素。
- 导图失败时是否保留可编译 fallback 并记录说明。

## 3. Token 与颜色

- 默认颜色是否优先使用 `theme.colors`。
- 是否避免 `Color(...)`、`Color.white/black`、`: Color = .white/.black/.secondary`。
- 是否避免过时 API：`theme.colors.primary/secondary/onPrimary`。
- 是否仅在透明语义使用 `.clear`。

## 4. 交付同步

- 新增/修改组件后，对应 Demo 页面是否更新。
- 新增组件时，Demo 组件列表入口是否更新。
- README 示例和接入说明是否同步。
- 若声明“仅内部重构”，是否提供原因说明。

## 5. 自动检查命令

在仓库根目录运行：

```bash
python3 skills/swiftui-component-generator/scripts/check_component_color_rules.py --path Sources/Components
python3 skills/swiftui-component-generator/scripts/check_component_delivery_sync.py --repo-root .
```

内部重构模式：

```bash
python3 skills/swiftui-component-generator/scripts/check_component_delivery_sync.py --repo-root . --internal-only --note "仅重构内部实现，不影响公开 API"
```
