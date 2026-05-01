#!/usr/bin/env python3
"""检查组件变更时 Demo/README 同步情况。"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Dict, List, Set, Tuple


@dataclass
class Violation:
    code: str
    message: str
    suggestion: str


def run(cmd: List[str], cwd: Path) -> str:
    result = subprocess.run(cmd, cwd=str(cwd), check=False, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "命令执行失败")
    return result.stdout


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="检查组件变更时 Demo/README 是否同步")
    parser.add_argument("--repo-root", default=".", help="仓库根目录")
    parser.add_argument("--internal-only", action="store_true", help="仅内部重构，不要求 README 变更")
    parser.add_argument("--note", default="", help="internal-only 时必须给出原因")
    parser.add_argument("--format", choices=["text", "json"], default="text")
    return parser.parse_args()


def collect_changed_files(repo_root: Path) -> Dict[str, str]:
    changed: Dict[str, str] = {}

    diff_output = run(["git", "diff", "--name-status", "--relative", "HEAD"], repo_root)
    for raw in diff_output.splitlines():
        if not raw.strip():
            continue
        parts = raw.split("\t")
        status = parts[0]
        if status.startswith("R") and len(parts) >= 3:
            changed[parts[2]] = "R"
        elif len(parts) >= 2:
            changed[parts[1]] = status[0]

    untracked_output = run(["git", "ls-files", "--others", "--exclude-standard"], repo_root)
    for raw in untracked_output.splitlines():
        path = raw.strip()
        if path and path not in changed:
            changed[path] = "A"

    return changed


def collect_changed_components(changed: Dict[str, str]) -> Tuple[Set[str], Set[str]]:
    components: Set[str] = set()
    newly_added: Set[str] = set()

    for path, status in changed.items():
        if not path.startswith("Sources/Components/"):
            continue
        parts = path.split("/")
        if len(parts) < 3:
            continue

        component = parts[2]
        if component in {"Environment", "Internals", "Resources"}:
            continue

        components.add(component)
        if status == "A":
            newly_added.add(component)

    return components, newly_added


def check_sync(changed: Dict[str, str], components: Set[str], newly_added: Set[str], internal_only: bool, note: str) -> List[Violation]:
    violations: List[Violation] = []

    if internal_only and not note.strip():
        violations.append(
            Violation(
                code="D003",
                message="使用 --internal-only 时必须提供 --note 说明原因。",
                suggestion='补充参数：--note "仅重构内部实现，不影响公开 API"',
            )
        )

    if not components:
        return violations

    changed_paths = set(changed.keys())

    for component in sorted(components):
        demo_prefix = f"Examples/Demo/Demo/Features/Components/{component}/"
        has_demo_update = any(path.startswith(demo_prefix) for path in changed_paths)
        if not has_demo_update:
            violations.append(
                Violation(
                    code="D001",
                    message=f"组件 {component} 已变更，但对应 Demo 页面未更新。",
                    suggestion=f"更新 {demo_prefix} 下 Demo 示例。",
                )
            )

    if not internal_only and "README.md" not in changed_paths:
        violations.append(
            Violation(
                code="D002",
                message="组件发生变更，但 README.md 未更新。",
                suggestion="同步更新 README 的组件示例、接入说明和注意事项。",
            )
        )

    if newly_added and "Examples/Demo/Demo/Features/Components/BaseComponentListView.swift" not in changed_paths:
        names = ", ".join(sorted(newly_added))
        violations.append(
            Violation(
                code="D004",
                message=f"检测到新增组件（{names}），但 Demo 组件列表入口未更新。",
                suggestion="更新 BaseComponentListView.swift，加入新增组件入口。",
            )
        )

    return violations


def print_text(violations: List[Violation]) -> None:
    if not violations:
        print("PASS: Demo/README 同步检查通过。")
        return

    for item in violations:
        print(f"{item.code} {item.message}")
        print(f"  建议: {item.suggestion}")


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).expanduser().resolve()

    if not repo_root.exists():
        print(f"ERROR: 仓库路径不存在: {repo_root}", file=sys.stderr)
        return 2

    try:
        changed = collect_changed_files(repo_root)
    except RuntimeError as exc:
        print(f"ERROR: 无法读取 git 变更: {exc}", file=sys.stderr)
        return 2

    components, newly_added = collect_changed_components(changed)
    violations = check_sync(changed, components, newly_added, args.internal_only, args.note)

    if args.format == "json":
        print(json.dumps([asdict(v) for v in violations], ensure_ascii=False, indent=2))
    else:
        print_text(violations)

    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main())
