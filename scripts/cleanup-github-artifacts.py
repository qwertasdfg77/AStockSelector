#!/usr/bin/env python3
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


GITHUB_API = "https://api.github.com"


def fail(message: str) -> None:
    print(f"artifact cleanup failed: {message}", file=sys.stderr)
    sys.exit(1)


def api_request(token: str, method: str, path: str) -> bytes:
    request = urllib.request.Request(
        f"{GITHUB_API}/{path.lstrip('/')}",
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "User-Agent": "AStockSelector-artifact-cleanup",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        fail(f"GitHub API {method} {path} returned HTTP {exc.code}: {body}")


def list_artifacts(repo: str, token: str) -> list[dict]:
    artifacts: list[dict] = []
    page = 1
    while True:
        query = urllib.parse.urlencode({"per_page": 100, "page": page})
        raw = api_request(token, "GET", f"repos/{repo}/actions/artifacts?{query}")
        data = json.loads(raw.decode("utf-8"))
        batch = data.get("artifacts", [])
        artifacts.extend(batch)
        if len(batch) < 100:
            return artifacts
        page += 1


def choose_kept_ids(artifacts: list[dict], keep_artifacts: list[str], keep_latest: list[str]) -> set[int]:
    kept: set[int] = set()
    for artifact in artifacts:
        if artifact.get("name") in keep_artifacts:
            kept.add(int(artifact["id"]))

    for artifact_name in keep_latest:
        matching = [artifact for artifact in artifacts if artifact.get("name") == artifact_name]
        if matching:
            latest = max(matching, key=lambda artifact: artifact.get("created_at", ""))
            kept.add(int(latest["id"]))

    return kept


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Delete old GitHub Actions artifacts.")
    parser.add_argument("--repo", required=True, help="Repository in owner/name form.")
    parser.add_argument(
        "--keep-artifact",
        action="append",
        default=[],
        help="Artifact name to keep. May be repeated.",
    )
    parser.add_argument(
        "--keep-latest",
        action="append",
        default=[],
        help="Keep the newest artifact with this name. May be repeated.",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print planned deletions without deleting.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        fail("GITHUB_TOKEN is required")

    artifacts = list_artifacts(args.repo, token)
    kept_ids = choose_kept_ids(artifacts, args.keep_artifact, args.keep_latest)
    deleted_count = 0

    for artifact in artifacts:
        artifact_id = int(artifact["id"])
        name = artifact.get("name", "")
        if artifact_id in kept_ids:
            print(f"keep artifact {artifact_id}: {name}")
            continue
        print(f"delete artifact {artifact_id}: {name}")
        if not args.dry_run:
            api_request(token, "DELETE", f"repos/{args.repo}/actions/artifacts/{artifact_id}")
        deleted_count += 1

    print(f"Artifact cleanup complete: kept {len(kept_ids)}, deleted {deleted_count}.")


if __name__ == "__main__":
    main()
