#!/usr/bin/env python3
import argparse
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


DEFAULT_NOTES = [
    "Release 流程只发布正式签名 release APK，不再上传 debug APK。",
    "发布后自动校验 latest.json、APK 大小和 SHA256。",
    "App 内更新流程显示下载进度、重试原因，并清理未完成安装包。",
]


def fail(message: str) -> None:
    raise SystemExit(f"prepare-release failed: {message}")


def read_text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write_text(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8", newline="\n")


def replace_once(text: str, pattern: str, repl, path: str) -> str:
    updated, count = re.subn(pattern, repl, text, count=1, flags=re.MULTILINE)
    if count != 1:
        fail(f"expected exactly one match in {path}: {pattern}")
    return updated


def validate_version(version_name: str) -> None:
    parts = version_name.split(".")
    if len(parts) != 3 or not all(part.isdigit() for part in parts):
        fail("versionName must use three numeric parts, for example 0.2.5.")
    if any(int(part) >= 10 for part in parts):
        fail("each versionName part must carry before 10; do not use versions like 0.1.10.")


def parse_current_release() -> tuple[str, int]:
    gradle = read_text("app/build.gradle.kts")
    version_name_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    version_code_match = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    if not version_name_match or not version_code_match:
        fail("version metadata not found in app/build.gradle.kts")
    return version_name_match.group(1), int(version_code_match.group(1))


def update_gradle(version_name: str, version_code: int) -> None:
    gradle = read_text("app/build.gradle.kts")
    gradle = replace_once(
        gradle,
        r"versionCode\s*=\s*\d+",
        f"versionCode = {version_code}",
        "app/build.gradle.kts",
    )
    gradle = replace_once(
        gradle,
        r'versionName\s*=\s*"[^"]+"',
        f'versionName = "{version_name}"',
        "app/build.gradle.kts",
    )
    write_text("app/build.gradle.kts", gradle)


def update_readme(version_name: str, version_code: int) -> None:
    readme = read_text("README.md")
    readme = replace_once(
        readme,
        r"(^- App .*?`)\d+\.\d+\.\d+(`)",
        lambda match: f"{match.group(1)}{version_name}{match.group(2)}",
        "README.md",
    )
    readme = replace_once(
        readme,
        r"(^- Android `versionCode`.*?`)\d+(`)",
        lambda match: f"{match.group(1)}{version_code}{match.group(2)}",
        "README.md",
    )
    write_text("README.md", readme)


def update_index(version_name: str) -> None:
    index = read_text("docs/index.html")
    index = replace_once(
        index,
        r'<div class="version">\d+\.\d+\.\d+</div>',
        f'<div class="version">{version_name}</div>',
        "docs/index.html",
    )
    write_text("docs/index.html", index)


def update_changelog(version_name: str, notes: list[str]) -> None:
    changelog = read_text("CHANGELOG.md")
    if re.search(rf"^## {re.escape(version_name)}$", changelog, flags=re.MULTILINE):
        return
    note_lines = "\n".join(f"- {note}" for note in notes)
    changelog = re.sub(
        r"\A(#[^\n]*\n+)",
        rf"\1## {version_name}\n\n{note_lines}\n\n",
        changelog,
        count=1,
    )
    write_text("CHANGELOG.md", changelog)


def write_release_doc(version_name: str, notes: list[str]) -> None:
    note_lines = "\n".join(f"- {note}" for note in notes)
    release_doc = f"""# AStockSelector {version_name} 下载页

这是 AStockSelector {version_name} 发布页。

## 下载

- APK：AStockSelector-v{version_name}-release.apk
- 下载地址：<https://github.com/qwertasdfg77/AStockSelector/releases/download/v{version_name}/AStockSelector-v{version_name}-release.apk>

## 安装

1. 用手机下载 APK，或从电脑发送到手机。
2. 打开 APK。
3. 如果系统提示未知来源，允许当前浏览器或文件管理器安装。
4. 首次打开 App 后，点击“智能更新并筛选”。

详细说明见：[docs/install.md](install.md)

## 当前 APK 类型

当前只发布正式签名 release APK，不再在 Release 中上传 debug APK。

签名说明见：[docs/signing-release.md](signing-release.md)

## 主要变化

{note_lines}

## 已知限制

- 当前主要基于日 K 数据，不包含实时分时数据。
- 节假日判断未内置完整交易日历。
- 公开行情源可能存在延迟、限流或接口变化。

## 风险提示

本项目只用于学习、复盘和策略研究，不构成投资建议。筛选结果只代表满足程序规则，不代表买卖建议。
"""
    write_text(f"docs/release-v{version_name}.md", release_doc)


def run_consistency_check() -> None:
    subprocess.run(
        [sys.executable, str(ROOT / "scripts/check-release-consistency.py")],
        cwd=ROOT,
        check=True,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare AStockSelector release metadata.")
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--previous-version")
    parser.add_argument("--note", action="append", dest="notes", default=[])
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    validate_version(args.version_name)

    current_version, current_code = parse_current_release()
    if args.version_code <= current_code:
        fail(f"versionCode must be greater than current versionCode {current_code}.")
    if args.previous_version and args.previous_version != current_version:
        print(
            f"warning: previous version {args.previous_version} does not match current {current_version}",
            file=sys.stderr,
        )

    notes = args.notes or DEFAULT_NOTES
    update_gradle(args.version_name, args.version_code)
    update_readme(args.version_name, args.version_code)
    update_index(args.version_name)
    update_changelog(args.version_name, notes)
    write_release_doc(args.version_name, notes)
    run_consistency_check()

    print(f"Prepared AStockSelector {args.version_name} ({args.version_code}).")
    print(f"Review changes, then commit and tag with: git tag v{args.version_name}")


if __name__ == "__main__":
    main()
