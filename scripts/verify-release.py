"""Verify a signed fork release before installation or publication. Python 3, Android SDK, JDK required."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

PACKAGE = "org.jellyfin.androidtv.debug"
CERTIFICATE = "6644d28e5c9249280a7552f404c675bde1316508eef2438fa2217b10121d8272"


def run(tool, *args):
    result = subprocess.run([str(tool), *map(str, args)], capture_output=True, text=True, timeout=90)
    if result.returncode:
        raise SystemExit(f"Verification failed: {tool.name} exited {result.returncode}")
    return result.stdout


def require(condition, message):
    if not condition:
        raise SystemExit("REFUSED: " + message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--previous-code", type=int, required=True)
    args = parser.parse_args()
    require(args.apk.is_file(), "APK does not exist")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
    require((sdk / "build-tools").is_dir(), "Set ANDROID_HOME to your Android SDK")
    suffix = ".exe" if os.name == "nt" else ""
    versions = sorted((sdk / "build-tools").iterdir(), key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)), reverse=True)
    tools = next((p for p in versions if (p / ("aapt" + suffix)).is_file()), None)
    require(tools is not None, "Android build tools missing")
    badging = run(tools / ("aapt" + suffix), "dump", "badging", args.apk.resolve())
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    require(package is not None, "Package/version cannot be read")
    app_id, version_code, version = package.groups()
    version_code = int(version_code)
    require(app_id == PACKAGE, "Wrong package; never replace official Jellyfin")
    require(version == args.version, "Unexpected version name")
    require(version_code > args.previous_code, "Version code must increase")
    require("application-label:'Jellyfin'" in badging, "Launcher label must be Jellyfin")
    require("sdkVersion:'21'" in badging, "Unexpected minimum SDK")
    abi_line = next((line for line in badging.splitlines() if line.startswith("native-code:")), "")
    require("'arm64-v8a'" in abi_line and "'armeabi-v7a'" in abi_line, "Both Fire/Shield ARM ABIs required")

    analyzer = sdk / "cmdline-tools" / "latest" / "bin" / ("apkanalyzer.bat" if os.name == "nt" else "apkanalyzer")
    require(run(analyzer, "manifest", "application-id", args.apk.resolve()).strip() == PACKAGE, "Independent package verification failed")
    require(run(analyzer, "manifest", "debuggable", args.apk.resolve()).strip() == "false", "Release must not be debuggable")
    signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    signature = run(signer, "verify", "--verbose", "--print-certs", args.apk.resolve())
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", signature)
    require(certificates == [CERTIFICATE], "Signing identity changed")
    result = {
        "applicationId": app_id, "versionName": version, "versionCode": version_code,
        "label": "Jellyfin", "debuggable": False, "minSdk": 21,
        "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        "signingCertificateSha256": CERTIFICATE,
    }
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
