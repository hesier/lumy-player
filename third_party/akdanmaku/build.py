"""Reproduce the debug-only AkDanmaku AAR using JDK 17 and verified Maven inputs."""
import hashlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).resolve().parent
WORK = ROOT / ".local" / "akdanmaku"
MAVEN = "https://repo.maven.apache.org/maven2/"
INPUTS = {
    "upstream.aar": (
        "com/kuaishou/akdanmaku/1.0.3/akdanmaku-1.0.3.aar",
        "895fb837191214a97c3f9d3cc725d90e3c9803b12e93b54652ee76603c046c7b",
    ),
    "asm.jar": (
        "org/ow2/asm/asm/9.7.1/asm-9.7.1.jar",
        "8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281",
    ),
}
CLASSES = ["com/kuaishou/akdanmaku/ui/DanmakuPlayer.class",
           "com/kuaishou/akdanmaku/ecs/DanmakuEngine.class"]
CACHE_CLASSES = ["com/kuaishou/akdanmaku/cache/CacheManager.class",
                 "com/kuaishou/akdanmaku/cache/CacheManager$CacheHandler.class"]


def download(name, spec):
    path = WORK / name
    if not path.exists():
        with urllib.request.urlopen(MAVEN + spec[0], timeout=60) as response:
            path.write_bytes(response.read())
    if hashlib.sha256(path.read_bytes()).hexdigest() != spec[1]:
        raise ValueError(f"Checksum mismatch: {path}")
    return path


def replace_entry(original, name, replacement):
    output = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(output, "w") as target:
        for entry in source.infolist():
            target.writestr(entry, replacement if entry.filename == name else source.read(entry.filename))
    return output.getvalue()


def compile_helpers(classes, java_bin):
    (WORK / "classes.jar").write_bytes(classes)
    sdk = Path(os.environ["ANDROID_HOME"])
    with tempfile.TemporaryDirectory(prefix="helpers-", dir=WORK) as directory:
        helpers = Path(directory)
        subprocess.run([str(java_bin / "javac"), "--release", "8", "-cp",
                        os.pathsep.join([str(WORK / "classes.jar"), str(sdk / "platforms/android-35/android.jar")]),
                        "-d", str(helpers), str(SOURCE / "LumyCacheBudget.java")], check=True)
        output = io.BytesIO(classes)
        with zipfile.ZipFile(output, "a") as jar:
            for helper in sorted(helpers.rglob("*.class")):
                info = zipfile.ZipInfo(helper.relative_to(helpers).as_posix(), (1980, 1, 1, 0, 0, 0))
                jar.writestr(info, helper.read_bytes())
        return output.getvalue()


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    inputs = {name: download(name, spec) for name, spec in INPUTS.items()}
    java_bin = Path(os.environ["JAVA_HOME"]) / "bin"
    subprocess.run([str(java_bin / "javac"), "--release", "17", "-cp", str(inputs["asm.jar"]),
                    "-d", str(WORK), str(SOURCE / "PatchRelease.java"), str(SOURCE / "PatchCache.java")], check=True)
    with zipfile.ZipFile(inputs["upstream.aar"]) as aar:
        classes = aar.read("classes.jar")
    for name in CLASSES + CACHE_CLASSES:
        with zipfile.ZipFile(io.BytesIO(classes)) as jar:
            (WORK / "original.class").write_bytes(jar.read(name))
        subprocess.run([str(java_bin / "java"), "-cp", os.pathsep.join([str(WORK), str(inputs["asm.jar"])]),
                        "PatchCache" if name in CACHE_CLASSES else "PatchRelease", str(WORK / "original.class"), str(WORK / "patched.class")], check=True)
        classes = replace_entry(classes, name, (WORK / "patched.class").read_bytes())
    patched_classes = compile_helpers(classes, java_bin)
    output = SOURCE / "akdanmaku-1.0.3+1-lumy1.aar"
    output.write_bytes(replace_entry(inputs["upstream.aar"].read_bytes(), "classes.jar", patched_classes))
    print(f"{output.name}: sha256={hashlib.sha256(output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
