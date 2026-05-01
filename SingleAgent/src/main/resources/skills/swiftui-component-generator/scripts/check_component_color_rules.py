#!/usr/bin/env python3
"""检查 SwiftUI 组件颜色规范违规。

默认规则：
- 禁止旧色彩 API: theme.colors.primary/secondary/onPrimary
- 禁止 Color(...) 字面构造
- 禁止组件实现中的 .white/.black/.secondary 默认色用法
- #Preview 中允许演示色，不计为违规
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, List


@dataclass
class Violation:
    code: str
    file: str
    line: int
    message: str
    suggestion: str


RULES = [
    (
        "R001",
        re.compile(r"\btheme\.colors\.(primary|secondary|onPrimary)\b"),
        "检测到过时颜色 API。",
        "改为 theme.colors.core.* / comp.* 等语义 token。",
    ),
    (
        "R002",
        re.compile(r"\bColor\s*\("),
        "检测到 Color(...) 字面颜色构造。",
        "使用 theme.colors.* token；仅 #Preview 演示色允许字面色。",
    ),
    (
        "R003",
        re.compile(r"\.foregroundStyle\(\s*\.secondary\b"),
        "检测到 .foregroundStyle(.secondary)。",
        "改为明确 token，例如 theme.colors.core.text.cTxt2th。",
    ),
    (
        "R004",
        re.compile(r":\s*Color\s*=\s*(?:Color\.)?(white|black|secondary)\b"),
        "检测到 Color 默认值使用 .white/.black/.secondary。",
        "默认值改为 theme.colors.* 或通过链式 API 注入。",
    ),
    (
        "R005",
        re.compile(r"\bColor\.(white|black)\b"),
        "检测到 Color.white / Color.black。",
        "改为 theme.colors.* token。",
    ),
    (
        "R006",
        re.compile(r"\.color\(\s*\.(white|black)\b"),
        "检测到 .color(.white/.black) 直接字面色。",
        "改为 .color(theme.colors.*) 或由样式解析器输出 token 色。",
    ),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="检查 SwiftUI 组件颜色规范违规")
    parser.add_argument("--path", required=True, help="待检查的 Swift 文件或目录")
    parser.add_argument("--format", choices=["text", "json"], default="text")
    return parser.parse_args()


def iter_swift_files(path: Path) -> Iterable[Path]:
    if path.is_file():
        if path.suffix == ".swift":
            yield path
        return
    yield from sorted(path.rglob("*.swift"))


def collect_violations(file_path: Path) -> List[Violation]:
    violations: List[Violation] = []
    lines = file_path.read_text(encoding="utf-8").splitlines()

    in_preview = False
    preview_brace_balance = 0

    for idx, line in enumerate(lines, start=1):
        stripped = line.strip()

        if "#Preview" in line and "{" in line:
            in_preview = True

        if in_preview:
            preview_brace_balance += line.count("{")
            preview_brace_balance -= line.count("}")
            if preview_brace_balance <= 0:
                in_preview = False
                preview_brace_balance = 0
            continue

        if not stripped or stripped.startswith("//"):
            continue

        for code, pattern, message, suggestion in RULES:
            if pattern.search(line):
                violations.append(
                    Violation(
                        code=code,
                        file=str(file_path),
                        line=idx,
                        message=message,
                        suggestion=suggestion,
                    )
                )

    return violations


def print_text(violations: List[Violation]) -> None:
    if not violations:
        print("PASS: 未发现颜色规范违规。")
        return

    for item in violations:
        print(f"{item.code} {item.file}:{item.line} {item.message}")
        print(f"  建议: {item.suggestion}")


def main() -> int:
    args = parse_args()
    target = Path(args.path).expanduser().resolve()

    if not target.exists():
        print(f"ERROR: 路径不存在: {target}", file=sys.stderr)
        return 2

    all_violations: List[Violation] = []
    for swift_file in iter_swift_files(target):
        all_violations.extend(collect_violations(swift_file))

    if args.format == "json":
        print(json.dumps([asdict(v) for v in all_violations], ensure_ascii=False, indent=2))
    else:
        print_text(all_violations)

    return 1 if all_violations else 0


if __name__ == "__main__":
    raise SystemExit(main())
