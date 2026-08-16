#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    print(f"release artifact check failed: {message}", file=sys.stderr)
    sys.exit(1)


def read_gradle_version() -> tuple[str, int]:
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    version_name_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    version_code_match = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    if not version_name_match:
        fail("versionName not found in app/build.gradle.kts")
    if not version_code_match:
        fail("versionCode not found in app/build.gradle.kts")
    return version_name_match.group(1), int(version_code_match.group(1))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_tag(tag: str, version_name: str) -> None:
    if not tag:
        return
    if not tag.startswith("v"):
        fail(f"release tag must start with v, got {tag}")
    tag_version = tag[1:]
    if tag_version != version_name:
        fail(f"tag {tag} does not match versionName {version_name}")


def check_latest_json(path: Path, version_name: str, version_code: int) -> dict:
    if not path.exists():
        fail(f"latest.json not found: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as exc:
        fail(f"latest.json is not valid JSON: {exc}")

    required = ["versionCode", "versionName", "apkUrl", "apkSha256", "apkSize", "releaseNotes"]
    for key in required:
        if key not in data:
            fail(f"latest.json missing field: {key}")

    if data["versionCode"] != version_code:
        fail(f"latest.json versionCode {data['versionCode']} does not match {version_code}")
    if data["versionName"] != version_name:
        fail(f"latest.json versionName {data['versionName']} does not match {version_name}")
    if not isinstance(data["apkUrl"], str) or not data["apkUrl"].startswith("https://"):
        fail("latest.json apkUrl must be an https URL")
    if not isinstance(data["apkSha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", data["apkSha256"]):
        fail("latest.json apkSha256 must be a lowercase 64-character hex string")
    if not isinstance(data["apkSize"], int) or data["apkSize"] <= 0:
        fail("latest.json apkSize must be a positive integer")
    if not isinstance(data["releaseNotes"], str) or not data["releaseNotes"].strip():
        fail("latest.json releaseNotes must not be empty")

    return data


def check_previous_latest_json(path: Path, version_name: str, version_code: int) -> None:
    if not path.exists():
        fail(f"previous latest.json not found: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as exc:
        fail(f"previous latest.json is not valid JSON: {exc}")

    previous_code = data.get("versionCode")
    previous_name = data.get("versionName")
    if not isinstance(previous_code, int) or previous_code <= 0:
        fail("previous latest.json versionCode must be a positive integer")
    if not isinstance(previous_name, str) or not previous_name:
        fail("previous latest.json versionName must not be empty")
    if not re.fullmatch(r"\d+\.\d+\.\d+", previous_name):
        fail("previous latest.json versionName must contain three numeric parts")
    if version_code < previous_code:
        fail(f"versionCode {version_code} must not be lower than published versionCode {previous_code}")
    if version_code == previous_code and version_name != previous_name:
        fail(
            f"versionCode {version_code} is already published as {previous_name}; "
            f"increase versionCode before publishing {version_name}"
        )
    if version_code > previous_code:
        current_version = tuple(int(part) for part in version_name.split("."))
        previous_version = tuple(int(part) for part in previous_name.split("."))
        if current_version <= previous_version:
            fail(
                f"versionName {version_name} must be higher than published versionName {previous_name} "
                f"when versionCode increases"
            )


def check_apk(path: Path, latest: dict | None) -> None:
    if not path.exists():
        fail(f"APK not found: {path}")
    if not path.is_file():
        fail(f"APK path is not a file: {path}")
    if path.suffix.lower() != ".apk":
        fail(f"APK file must end with .apk: {path}")

    if latest is None:
        return

    actual_size = path.stat().st_size
    if actual_size != latest["apkSize"]:
        fail(f"APK size {actual_size} does not match latest.json {latest['apkSize']}")
    actual_sha = sha256(path)
    if actual_sha != latest["apkSha256"]:
        fail(f"APK SHA256 {actual_sha} does not match latest.json {latest['apkSha256']}")


def check_signing_report(path: Path | None, expected_sha256: str | None) -> None:
    if path is None:
        fail("signed release check requires --signing-report")
    if not path.exists():
        fail(f"signing report not found: {path}")
    content = path.read_text(encoding="utf-8", errors="replace")
    if not content.strip():
        fail("signing report is empty")
    if "DOES NOT VERIFY" in content or "ERROR" in content:
        fail("signing report contains verification failure")
    if "Signer #1 certificate" not in content and "Number of signers:" not in content:
        fail("signing report does not contain signer certificate details")
    if expected_sha256:
        expected = expected_sha256.strip().lower()
        if not re.fullmatch(r"[0-9a-f]{64}", expected):
            fail("expected signer SHA256 must be a lowercase 64-character hex string")
        match = re.search(r"certificate SHA-256 digest:\s*([0-9a-f]{64})", content, re.IGNORECASE)
        if not match:
            fail("signing report does not contain a certificate SHA-256 digest")
        actual = match.group(1).lower()
        if actual != expected:
            fail(f"signing certificate SHA256 {actual} does not match expected {expected}")


def check_with_apksigner(apksigner: str | None, apk: Path) -> None:
    if not apksigner:
        return
    result = subprocess.run(
        [apksigner, "verify", "--print-certs", str(apk)],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if result.returncode != 0:
        fail(f"apksigner verification failed:\n{result.stdout}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate AStockSelector release APK and update metadata.")
    parser.add_argument("--apk", required=True, help="APK file to validate.")
    parser.add_argument("--latest-json", help="latest.json file to validate against the APK.")
    parser.add_argument("--previous-latest-json", help="Previously published latest.json for versionCode monotonicity.")
    parser.add_argument("--tag", default="", help="Release tag, for example v0.2.6.")
    parser.add_argument("--require-signed", action="store_true", help="Require release signing evidence.")
    parser.add_argument("--signing-report", help="release-signing-report.txt generated by apksigner.")
    parser.add_argument("--expected-signer-sha256", help="Required release signing certificate SHA256.")
    parser.add_argument("--apksigner", help="Optional apksigner executable for direct APK verification.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    version_name, version_code = read_gradle_version()
    check_tag(args.tag, version_name)

    if args.previous_latest_json:
        check_previous_latest_json(Path(args.previous_latest_json).resolve(), version_name, version_code)

    latest = None
    if args.latest_json:
        latest = check_latest_json(Path(args.latest_json).resolve(), version_name, version_code)

    apk = Path(args.apk).resolve()
    check_apk(apk, latest)
    if args.require_signed:
        check_signing_report(
            Path(args.signing_report).resolve() if args.signing_report else None,
            args.expected_signer_sha256,
        )
    check_with_apksigner(args.apksigner, apk)

    print(f"Release artifact is valid: {version_name} ({version_code}) {apk.name}")


if __name__ == "__main__":
    main()
