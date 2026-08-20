#!/usr/bin/env python3
import os
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    print(f"release consistency check failed: {message}", file=sys.stderr)
    sys.exit(1)


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


gradle = read("app/build.gradle.kts")
version_name_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
version_code_match = re.search(r"versionCode\s*=\s*(\d+)", gradle)

if not version_name_match:
    fail("versionName not found in app/build.gradle.kts")
if not version_code_match:
    fail("versionCode not found in app/build.gradle.kts")

version_name = version_name_match.group(1)
version_code = int(version_code_match.group(1))

parts = version_name.split(".")
if len(parts) != 3 or not all(part.isdigit() for part in parts):
    fail(f"versionName must be three numeric parts, got {version_name}")
if any(int(part) >= 10 for part in parts):
    fail(f"versionName parts must carry before 10, got {version_name}")
if version_code <= 0:
    fail(f"versionCode must be positive, got {version_code}")

tag_name = os.environ.get("GITHUB_REF_NAME", "")
ref_type = os.environ.get("GITHUB_REF_TYPE", "")
if ref_type == "tag" and tag_name.startswith("v"):
    tag_version = tag_name[1:]
    if tag_version != version_name:
        fail(f"tag {tag_name} does not match versionName {version_name}")

required_snippets = {
    "README.md": [
        f"App 版本：`{version_name}`",
        f"Android `versionCode`：`{version_code}`",
    ],
    "CHANGELOG.md": [f"## {version_name}"],
    "docs/index.html": [
        f"版本基线为 {version_name} / versionCode {version_code}。",
        f"开源 Android · A 股日线策略筛选 · {version_name}",
        f'<div class="version">{version_name}</div>',
        f"versionCode {version_code} · Android",
        f"AStockSelector_PRD.pdf?v={version_name}",
        f"MIT License · AStockSelector {version_name}",
    ],
    "docs/AStockSelector_schema.sql": [f"-- Version: {version_name} / schema_version"],
    "docs/signing-release.md": [f'$Version = "{version_name}"'],
    f"docs/release-v{version_name}.md": [
        f"# AStockSelector {version_name}",
        f"v{version_name}",
        "https://github.com/qwertasdfg77/AStockSelector/blob/main/docs/install.md",
        "https://github.com/qwertasdfg77/AStockSelector/blob/main/docs/signing-release.md",
    ],
    "scripts/generate-prd.py": [
        f'VERSION_NAME = "{version_name}"',
        f"VERSION_CODE = {version_code}",
    ],
}

for path, snippets in required_snippets.items():
    file_path = ROOT / path
    if not file_path.exists():
        fail(f"{path} is missing")
    content = file_path.read_text(encoding="utf-8")
    for snippet in snippets:
        if snippet not in content:
            fail(f"{path} does not contain required text: {snippet}")

print(f"AStockSelector release metadata is consistent: {version_name} ({version_code})")
