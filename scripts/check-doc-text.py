#!/usr/bin/env python3
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

TEXT_GLOBS = [
    "*.md",
    "docs/**/*.md",
    "docs/**/*.html",
    "docs/**/*.svg",
    ".github/workflows/*.yml",
    ".github/workflows/*.yaml",
]

SPECIFIC_TOKENS = [
    "锛",
    "銆",
    "鈥",
    "鑲",
    "鐣",
    "闈",
    "�",
]

MOJIBAKE_CHARS = set(
    "鑲鐣闈涓枃鏄鏂鏁绛寮瀛瑙璇鍙潰悗洿鐨彇氦鏈鐗"
    "嶄繚牸勫灏冩槑殑佺牬濈暐"
)


def iter_text_files() -> list[Path]:
    files: set[Path] = set()
    for pattern in TEXT_GLOBS:
        files.update(path for path in ROOT.glob(pattern) if path.is_file())
    return sorted(files)


def suspicious_score(line: str) -> int:
    if any(token in line for token in SPECIFIC_TOKENS):
        return 3
    return sum(1 for char in line if char in MOJIBAKE_CHARS)


def main() -> None:
    failures: list[str] = []
    for path in iter_text_files():
        rel_path = path.relative_to(ROOT)
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            failures.append(f"{rel_path}: UTF-8 decode failed: {exc}")
            continue

        for line_no, line in enumerate(text.splitlines(), 1):
            if suspicious_score(line) >= 3:
                snippet = line.strip()
                if len(snippet) > 180:
                    snippet = snippet[:177] + "..."
                failures.append(f"{rel_path}:{line_no}: possible mojibake: {snippet}")

    if failures:
        print("Document text check failed:", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        sys.exit(1)

    print("Document text check passed.")


if __name__ == "__main__":
    main()
