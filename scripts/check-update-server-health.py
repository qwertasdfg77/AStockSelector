#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
import sys
import time
import urllib.error
import urllib.request


def fail(message: str) -> None:
    print(f"update server health check failed: {message}", file=sys.stderr)
    sys.exit(1)


def fetch_bytes(url: str, timeout: int = 60) -> bytes:
    request = urllib.request.Request(
        url,
        headers={
            "Cache-Control": "no-cache",
            "User-Agent": "AStockSelector-release-health-check",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        status = getattr(response, "status", 200)
        if status < 200 or status >= 300:
            raise urllib.error.HTTPError(url, status, "unexpected status", response.headers, None)
        return response.read()


def parse_latest(raw: bytes) -> dict:
    try:
        return json.loads(raw.decode("utf-8-sig"))
    except json.JSONDecodeError as exc:
        fail(f"latest.json is not valid JSON: {exc}")


def validate_latest(data: dict, expected_version_name: str, expected_version_code: int) -> None:
    required = ["versionCode", "versionName", "apkUrl", "apkSha256", "apkSize", "releaseNotes"]
    for key in required:
        if key not in data:
            fail(f"latest.json missing field: {key}")

    if data["versionCode"] != expected_version_code:
        fail(f"versionCode {data['versionCode']} does not match {expected_version_code}")
    if data["versionName"] != expected_version_name:
        fail(f"versionName {data['versionName']} does not match {expected_version_name}")
    if not isinstance(data["apkUrl"], str) or not data["apkUrl"].startswith("https://"):
        fail("apkUrl must be an https URL")
    if not isinstance(data["apkSha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", data["apkSha256"]):
        fail("apkSha256 must be a lowercase 64-character hex string")
    if not isinstance(data["apkSize"], int) or data["apkSize"] <= 0:
        fail("apkSize must be a positive integer")
    if not isinstance(data["releaseNotes"], str) or not data["releaseNotes"].strip():
        fail("releaseNotes must not be empty")


def validate_apk(apk: bytes, latest: dict) -> None:
    actual_size = len(apk)
    if actual_size != latest["apkSize"]:
        fail(f"APK size {actual_size} does not match latest.json {latest['apkSize']}")

    actual_sha256 = hashlib.sha256(apk).hexdigest()
    if actual_sha256 != latest["apkSha256"]:
        fail(f"APK SHA256 {actual_sha256} does not match latest.json {latest['apkSha256']}")


def run_once(args: argparse.Namespace) -> None:
    latest = parse_latest(fetch_bytes(args.latest_url, timeout=args.timeout))
    validate_latest(latest, args.expected_version_name, args.expected_version_code)
    apk = fetch_bytes(latest["apkUrl"], timeout=args.timeout)
    validate_apk(apk, latest)
    print(
        "Update server is healthy: "
        f"{latest['versionName']} ({latest['versionCode']}) "
        f"{latest['apkSize']} bytes"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate the published AStockSelector update server.")
    parser.add_argument("--latest-url", required=True, help="Remote latest.json URL.")
    parser.add_argument("--expected-version-name", required=True, help="Expected versionName.")
    parser.add_argument("--expected-version-code", required=True, type=int, help="Expected versionCode.")
    parser.add_argument("--retries", type=int, default=1, help="Total attempts before failing.")
    parser.add_argument("--retry-delay", type=int, default=5, help="Delay between attempts in seconds.")
    parser.add_argument("--timeout", type=int, default=120, help="HTTP timeout in seconds.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    attempts = max(args.retries, 1)
    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            run_once(args)
            return
        except SystemExit:
            raise
        except Exception as exc:  # Network/CDN propagation failures are retried.
            last_error = exc
            if attempt < attempts:
                print(f"Attempt {attempt}/{attempts} failed: {exc}. Retrying in {args.retry_delay}s...")
                time.sleep(args.retry_delay)

    fail(f"remote update check failed after {attempts} attempts: {last_error}")


if __name__ == "__main__":
    main()
