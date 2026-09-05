#!/usr/bin/env python3
"""Verified Gradle bootstrap. Requires Python 3 and a Java 21 JDK."""
from pathlib import Path
import hashlib, os, subprocess, sys, urllib.request, zipfile
VERSION = "9.2.1"
SHA256 = "72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f"
root = Path(__file__).resolve().parents[1]
tools = root / ".tools"
archive = tools / ("gradle-" + VERSION + "-bin.zip")
launcher = tools / ("gradle-" + VERSION) / "bin" / ("gradle.bat" if os.name == "nt" else "gradle")
def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()
if not launcher.exists():
    tools.mkdir(exist_ok=True)
    if not archive.exists() or digest(archive) != SHA256:
        temporary = archive.with_suffix(".download")
        url = "https://services.gradle.org/distributions/gradle-" + VERSION + "-bin.zip"
        print("Downloading pinned Gradle " + VERSION, flush=True)
        with urllib.request.urlopen(url, timeout=60) as source, temporary.open("wb") as out:
            while chunk := source.read(1024 * 1024):
                out.write(chunk)
        if digest(temporary) != SHA256:
            temporary.unlink(missing_ok=True)
            raise SystemExit("Gradle checksum mismatch; nothing executed")
        temporary.replace(archive)
    with zipfile.ZipFile(archive) as z:
        for member in z.infolist():
            if not (tools / member.filename).resolve().is_relative_to(tools.resolve()):
                raise SystemExit("Unsafe archive path")
        z.extractall(tools)
    if os.name != "nt":
        launcher.chmod(0o755)
args = sys.argv[1:] or ["clean", "build"]
raise SystemExit(subprocess.run([str(launcher), "--no-daemon", "--max-workers=1", *args], cwd=root).returncode)
