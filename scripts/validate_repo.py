#!/usr/bin/env python3
"""Validate the Valenced plugin index and every package hosted in this repository."""
from __future__ import annotations

import hashlib
import json
import re
import zipfile
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
INDEX_PATH = ROOT / "index.json"
LOCAL_PREFIX = "https://raw.githubusercontent.com/LiFawaz/fawaz-plugins/main/"
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def fail(message: str) -> None:
    raise SystemExit("ERROR: " + message)


def main() -> None:
    try:
        index = json.loads(INDEX_PATH.read_text(encoding="utf-8"))
    except Exception as exc:
        fail("could not parse index.json: " + str(exc))

    if index.get("format") != 1:
        fail("index.json format must be 1")

    plugins = index.get("plugins")
    if not isinstance(plugins, dict) or len(plugins) != 5:
        fail("expected exactly five plugins in the repository index")

    names = [str(item.get("name", "")).lower() for item in plugins.values()]
    ids = [str(plugin_id).lower() for plugin_id in plugins]
    if any("hidden channels" in name for name in names) or any("hidden-channel" in item for item in ids):
        fail("Hidden Channels must remain excluded")

    expected_ids = {
        "com.lifawaz.vc-follow",
        "com.lifawaz.fake-deaf",
        "com.lifawaz.role-color-everywhere",
        "lifawaz.reaction-logs",
        "com.lifawaz.voice-message-downloader",
    }
    if set(plugins) != expected_ids:
        fail("plugin IDs do not match the approved plugin set")

    checked = 0
    for plugin_id, plugin in plugins.items():
        versions = plugin.get("versions")
        latest = plugin.get("channels", {}).get("latest")
        if not isinstance(versions, dict) or latest not in versions:
            fail(plugin_id + ": latest channel does not refer to a listed version")

        for version, metadata in versions.items():
            url = metadata.get("url", "")
            sha = metadata.get("sha256", "")
            size = metadata.get("size")
            if not url.startswith("https://"):
                fail(plugin_id + "@" + version + ": package URL must use HTTPS")
            if not SHA256_RE.fullmatch(str(sha)):
                fail(plugin_id + "@" + version + ": invalid SHA-256")
            if not isinstance(size, int) or size <= 0:
                fail(plugin_id + "@" + version + ": package size must be a positive integer")

            if not url.startswith(LOCAL_PREFIX):
                continue

            relative = unquote(url[len(LOCAL_PREFIX):])
            package = ROOT / relative
            if not package.is_file():
                fail(plugin_id + "@" + version + ": local package missing: " + relative)

            data = package.read_bytes()
            if len(data) != size:
                fail(plugin_id + "@" + version + ": size mismatch (index " + str(size) + ", file " + str(len(data)) + ")")
            actual_sha = hashlib.sha256(data).hexdigest()
            if actual_sha != sha:
                fail(plugin_id + "@" + version + ": SHA-256 mismatch (index " + sha + ", actual " + actual_sha + ")")

            try:
                with zipfile.ZipFile(package) as archive:
                    bad = archive.testzip()
                    if bad:
                        fail(plugin_id + "@" + version + ": corrupt ZIP entry " + bad)
                    manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
            except SystemExit:
                raise
            except Exception as exc:
                fail(plugin_id + "@" + version + ": invalid install ZIP: " + str(exc))

            if manifest.get("id") != plugin_id:
                fail(plugin_id + "@" + version + ": package manifest ID does not match index")
            if str(manifest.get("version")) != str(version):
                fail(plugin_id + "@" + version + ": package manifest version does not match index")
            checked += 1

    print("OK: " + str(len(plugins)) + " plugins, " + str(checked) + " local ZIP packages validated.")


if __name__ == "__main__":
    main()
