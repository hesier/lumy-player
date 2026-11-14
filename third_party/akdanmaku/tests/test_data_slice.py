"""Exercise the AAR's real DataSystem slice selection without an Android device."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

SOURCE = Path(__file__).resolve().parent
STUBS = {
    "android/util/Log.java": """package android.util;
public final class Log {
    public static int w(String tag, String message) { return 0; }
    public static int d(String tag, String message) { return 0; }
}
""",
    "com/kuaishou/akdanmaku/data/DanmakuItem.java": """package com.kuaishou.akdanmaku.data;
public final class DanmakuItem implements Comparable<DanmakuItem> {
    private final long time;
    public DanmakuItem(long time) { this.time = time; }
    public long getTimePosition() { return time; }
    public int compareTo(DanmakuItem other) { return Long.compare(time, other.time); }
    public String toString() { return Long.toString(time); }
}
""",
}


def cached_jar(group, name):
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    matches = sorted((cache / "caches/modules-2/files-2.1" / group / name).glob("*/*/*.jar"))
    if not matches:
        raise RuntimeError(f"Missing cached {group}:{name}; run :app:assembleDebug first")
    return matches[-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=Path, default=SOURCE.parent / "akdanmaku-1.0.3+1-lumy1.aar")
    args = parser.parse_args()
    java_bin = Path(os.environ["JAVA_HOME"]) / "bin"
    sdk = Path(os.environ["ANDROID_HOME"])
    dependencies = [cached_jar("org.jetbrains.kotlin", "kotlin-stdlib"),
                    cached_jar("com.badlogicgames.ashley", "ashley"),
                    cached_jar("com.badlogicgames.gdx", "gdx"),
                    sdk / "platforms/android-35/android.jar"]
    with tempfile.TemporaryDirectory(prefix="lumy-data-slice-") as directory:
        work = Path(directory)
        with zipfile.ZipFile(args.aar) as aar:
            (work / "engine.jar").write_bytes(aar.read("classes.jar"))
        sources = [SOURCE / "DataSliceTest.java"]
        for name, source in STUBS.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            sources.append(path)
        classpath = os.pathsep.join(map(str, [work / "engine.jar", *dependencies]))
        subprocess.run([str(java_bin / "javac"), "-cp", classpath, "-d", str(work),
                        *map(str, sources)], check=True)
        subprocess.run([str(java_bin / "java"), "-cp", str(work) + os.pathsep + classpath,
                        "DataSliceTest"], check=True)


if __name__ == "__main__":
    main()
