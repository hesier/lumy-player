#!/usr/bin/env python3
"""Rebuild the pinned FFmpeg AAR with Lumy's TrueHD initialization fix."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
WORK = ROOT / '.local' / 'native-ffmpeg'
NDK_VERSION = '26.1.10909125'
MEDIA_COMMIT = 'b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9'
FFMPEG_COMMIT = 'd388c347d41e4eb516dec05910551c5461e65615'
AAR_NAME = 'media3-ffmpeg-decoder-1.8.0+1'
DECODERS = ('flac', 'alac', 'pcm_mulaw', 'pcm_alaw', 'mp3', 'aac',
            'ac3', 'eac3', 'dca', 'mlp', 'truehd')
ABIS = {
    'armeabi-v7a': ('arm', 'armv7-a', 'armv7a-linux-androideabi21',
                    '--extra-cflags=-march=armv7-a -mfloat-abi=softfp',
                    '--extra-ldflags=-Wl,--fix-cortex-a8'),
    'arm64-v8a': ('aarch64', 'armv8-a', 'aarch64-linux-android21'),
}


def run(args, cwd=None):
    env = os.environ.copy()
    # Host headers and libraries must never leak into the Android cross build.
    for name in ('CC', 'CXX', 'CPP', 'AS', 'LD', 'AR', 'NM', 'RANLIB', 'STRIP',
                 'CFLAGS', 'CXXFLAGS', 'CPPFLAGS', 'LDFLAGS', 'CPATH',
                 'C_INCLUDE_PATH', 'CPLUS_INCLUDE_PATH', 'LIBRARY_PATH',
                 'PKG_CONFIG_PATH', 'PKG_CONFIG_LIBDIR', 'SDKROOT',
                 'MACOSX_DEPLOYMENT_TARGET', 'MAKEFLAGS'):
        env.pop(name, None)
    env.update(LC_ALL='C', TZ='UTC', SOURCE_DATE_EPOCH='0')
    subprocess.run([str(a) for a in args], cwd=cwd, env=env, check=True)


def download(name, url, digest):
    path = WORK / name
    if not path.exists():
        temporary = path.with_suffix(path.suffix + '.download')
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as out:
            shutil.copyfileobj(response, out)
        temporary.replace(path)
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != digest:
        raise RuntimeError(f'Checksum mismatch for {name}: {actual}')
    return path


def sources():
    archive = download('ffmpeg.tar.gz',
                       f'https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/{FFMPEG_COMMIT}',
                       'f89916923b8b935d1f0aaf8b77d21cb0c507c270d66c00d0f256a62cc7ff22ec')
    source = WORK / f'FFmpeg-{FFMPEG_COMMIT}'
    if not source.exists():
        with tarfile.open(archive) as tar:
            tar.extractall(WORK, filter='data')
    original = download('ffmpeg_jni.original.cc',
                        f'https://raw.githubusercontent.com/androidx/media/{MEDIA_COMMIT}/'
                        'libraries/decoder_ffmpeg/src/main/jni/ffmpeg_jni.cc',
                        'd60f46f794883a16cb200f454b004b224d9ec230ff73cb3aa9ee6a8c913fa407')
    patched = WORK / 'ffmpeg_jni.cc'
    shutil.copyfile(original, patched)
    run(['patch', '-F0', patched, HERE / 'truehd-layout.patch'])
    return source, patched


def build_abi(abi, settings, *, toolchain, source, jni, jobs):
    arch, cpu, target, *extra = settings
    build = WORK / abi
    build.mkdir(exist_ok=True)
    tools = toolchain / 'bin'
    options = [source / 'configure', '--target-os=android', '--enable-cross-compile',
               '--enable-static', '--disable-shared', '--disable-doc', '--disable-programs',
               '--disable-everything', '--disable-avdevice', '--disable-avformat',
               '--disable-swscale', '--disable-postproc', '--disable-avfilter',
               '--disable-symver', '--enable-swresample', '--extra-ldexeflags=-pie',
               '--disable-v4l2-m2m', '--disable-vulkan', f'--arch={arch}', f'--cpu={cpu}',
               f'--cc={tools / (target + "-clang")}',
               f'--cxx={tools / (target + "-clang++")}',
               f'--nm={tools / "llvm-nm"}', f'--ar={tools / "llvm-ar"}',
               f'--ranlib={tools / "llvm-ranlib"}', f'--strip={tools / "llvm-strip"}',
               *extra, *(f'--enable-decoder={name}' for name in DECODERS)]
    run(options, build)
    run(['make', f'-j{jobs}'], build)
    output = build / 'libffmpegJNI.so'
    run([tools / (target + '-clang++'), '-shared', '-std=c++11', '-O3', '-DNDEBUG',
         '-fPIC', '-ffunction-sections', '-fdata-sections', '-static-libstdc++',
         '-Wl,--gc-sections', '-Wl,-z,max-page-size=16384', '-Wl,-soname,libffmpegJNI.so',
         '-Wl,-Bsymbolic', '-I', build, '-I', source, jni,
         build / 'libswresample/libswresample.a', build / 'libavcodec/libavcodec.a',
         build / 'libavutil/libavutil.a', '-landroid', '-llog', '-lm', '-lz', '-o', output])
    run([tools / 'llvm-strip', '--strip-unneeded', output])
    return output


def package(libraries):
    upstream = download(AAR_NAME + '.aar',
                        'https://repo.maven.apache.org/maven2/org/jellyfin/media3/'
                        f'media3-ffmpeg-decoder/1.8.0+1/{AAR_NAME}.aar',
                        '75404bcf0143c31c88c8904d2384652940af97a0178efd399d7682494615fbff')
    output = HERE / (AAR_NAME + '-lumy1.aar')
    temporary = output.with_suffix('.aar.tmp')
    with zipfile.ZipFile(upstream) as original, zipfile.ZipFile(temporary, 'w') as patched:
        entries = {n: original.read(n) for n in original.namelist()
                   if not n.startswith('jni/') and not n.endswith('/')}
        entries.update({f'jni/{abi}/libffmpegJNI.so': path.read_bytes()
                        for abi, path in libraries.items()})
        for name, data in sorted(entries.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            patched.writestr(entry, data, compresslevel=9)
    temporary.replace(output)
    print(f'Built {output.relative_to(ROOT)}')
    print(f'SHA256: {hashlib.sha256(output.read_bytes()).hexdigest()}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, default=os.environ.get('ANDROID_NDK_HOME'))
    parser.add_argument('--jobs', type=int, default=min(os.cpu_count() or 4, 8))
    args = parser.parse_args()
    if args.ndk is None:
        parser.error(f'Pass --ndk or set ANDROID_NDK_HOME to NDK {NDK_VERSION}')
    ndk = args.ndk.resolve()
    if f'Pkg.Revision = {NDK_VERSION}' not in (ndk / 'source.properties').read_text():
        parser.error(f'This rebuild requires NDK {NDK_VERSION}')
    hosts = list((ndk / 'toolchains/llvm/prebuilt').glob('*'))
    if len(hosts) != 1:
        parser.error('Expected one NDK host toolchain')
    WORK.mkdir(parents=True, exist_ok=True)
    source, jni = sources()
    libraries = {abi: build_abi(abi, settings, toolchain=hosts[0], source=source,
                                jni=jni, jobs=args.jobs)
                 for abi, settings in ABIS.items()}
    package(libraries)


if __name__ == '__main__':
    main()
