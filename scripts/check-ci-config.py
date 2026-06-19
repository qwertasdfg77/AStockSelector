#!/usr/bin/env python3
import py_compile
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    print(f"CI config check failed: {message}", file=sys.stderr)
    sys.exit(1)


def compile_python_scripts() -> None:
    for script in sorted((ROOT / "scripts").glob("*.py")):
        py_compile.compile(str(script), doraise=True)


def parse_workflows() -> dict[str, dict]:
    workflows: dict[str, dict] = {}
    for workflow in sorted((ROOT / ".github" / "workflows").glob("*.yml")):
        with workflow.open("r", encoding="utf-8") as handle:
            data = yaml.safe_load(handle)
        if not isinstance(data, dict):
            fail(f"{workflow} did not parse as a YAML mapping")
        workflows[workflow.name] = data
    return workflows


def require_concurrency(workflows: dict[str, dict]) -> None:
    for name in ["android-ci.yml", "codeql.yml", "release-apk.yml"]:
        workflow = workflows.get(name)
        if workflow is None:
            fail(f"missing workflow: {name}")
        concurrency = workflow.get("concurrency")
        if not isinstance(concurrency, dict):
            fail(f"{name} must define concurrency")
        if concurrency.get("cancel-in-progress") is not True:
            fail(f"{name} must set concurrency.cancel-in-progress to true")
        if not concurrency.get("group"):
            fail(f"{name} must set concurrency.group")


def release_workflow_text() -> str:
    return (ROOT / ".github" / "workflows" / "release-apk.yml").read_text(encoding="utf-8")


def check_release_only_uploads_release_apk() -> None:
    text = release_workflow_text()
    forbidden = [
        "app-debug.apk",
        "-debug.apk",
        "release-files/*.apk",
        "assembleDebug",
    ]
    for token in forbidden:
        if token in text:
            fail(f"release workflow must not reference debug or wildcard APK uploads: {token}")

    required = [
        "release-files/AStockSelector-${{ github.ref_name }}-release.apk",
        'RELEASE_APK="app/build/outputs/apk/release/app-release.apk"',
        'UPDATE_FILE="app-release.apk"',
        'sha256sum "$RELEASE_FILE" > SHA256SUMS.txt',
    ]
    for token in required:
        if token not in text:
            fail(f"release workflow is missing release-only guard: {token}")


def require_document_text_scan() -> None:
    for workflow_name in ["android-ci.yml", "release-apk.yml"]:
        text = (ROOT / ".github" / "workflows" / workflow_name).read_text(encoding="utf-8")
        if "python scripts/check-doc-text.py" not in text:
            fail(f"{workflow_name} must run scripts/check-doc-text.py")


def main() -> None:
    compile_python_scripts()
    workflows = parse_workflows()
    require_concurrency(workflows)
    check_release_only_uploads_release_apk()
    require_document_text_scan()
    print("CI config check passed.")


if __name__ == "__main__":
    main()
