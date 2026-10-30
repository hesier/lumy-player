#ifndef LUMY_LEGACY_RM_IO_H
#define LUMY_LEGACY_RM_IO_H

#include <jni.h>
#include <cerrno>
#include <cstdio>
#include <vector>

extern "C" {
#include "libavformat/avformat.h"
#include "libavutil/error.h"
#include "libavutil/mem.h"
}

namespace lumy_rm {
constexpr int kIoBufferSize = 32768;
constexpr int64_t kTimeUnset = INT64_C(-9223372036854775807);

inline void ThrowIo(JNIEnv* env, const char* operation, int error) {
  if (env->ExceptionCheck()) return;
  char detail[AV_ERROR_MAX_STRING_SIZE];
  av_strerror(error, detail, sizeof(detail));
  char message[256];
  snprintf(message, sizeof(message), "RealMedia %s: %s", operation, detail);
  jclass type = env->FindClass("java/io/IOException");
  if (type) env->ThrowNew(type, message);
  env->DeleteLocalRef(type);
}

struct AudioClock {
  int64_t anchor_us = AV_NOPTS_VALUE;
  int64_t samples = 0;
};

struct Context {
  JNIEnv* env = nullptr;
  jobject owner = nullptr;
  jobject input = nullptr;
  jbyteArray buffer = nullptr;
  jmethodID read = nullptr;
  jmethodID seek = nullptr;
  jmethodID packet = nullptr;
  AVFormatContext* format = nullptr;
  AVIOContext* io = nullptr;
  AVPacket* sample = nullptr;
  int seek_stream = -1;
  std::vector<AudioClock> audio_clocks;
  std::vector<int> header_index_counts;

  ~Context() {
    av_packet_free(&sample);
    avformat_close_input(&format);
    if (io) av_freep(&io->buffer);
    avio_context_free(&io);
    if (owner) env->DeleteGlobalRef(owner);
    if (input) env->DeleteGlobalRef(input);
    if (buffer) env->DeleteGlobalRef(buffer);
  }
};

inline int Read(void* opaque, uint8_t* data, int size) {
  auto* context = static_cast<Context*>(opaque);
  JNIEnv* env = context->env;
  if (env->ExceptionCheck()) return AVERROR(EIO);
  int count = env->CallIntMethod(context->input, context->read, context->buffer,
                                 0, FFMIN(size, kIoBufferSize));
  if (env->ExceptionCheck()) return AVERROR(EIO);
  if (count == -1) return AVERROR_EOF;
  if (count <= 0) return AVERROR(EIO);
  env->GetByteArrayRegion(context->buffer, 0, count, reinterpret_cast<jbyte*>(data));
  return env->ExceptionCheck() ? AVERROR(EIO) : count;
}

inline int64_t Seek(void* opaque, int64_t offset, int whence) {
  auto* context = static_cast<Context*>(opaque);
  JNIEnv* env = context->env;
  if (env->ExceptionCheck()) return AVERROR(EIO);
  int64_t result = env->CallLongMethod(context->input, context->seek,
                                      static_cast<jlong>(offset), whence);
  if (env->ExceptionCheck()) return AVERROR(EIO);
  return result < 0 ? AVERROR(ENOSYS) : result;
}

inline int Interrupted(void* opaque) {
  return static_cast<Context*>(opaque)->env->ExceptionCheck();
}

inline bool InitializeIo(Context* context, jobject owner, jobject input) {
  JNIEnv* env = context->env;
  context->owner = env->NewGlobalRef(owner);
  context->input = env->NewGlobalRef(input);
  jbyteArray buffer = env->NewByteArray(kIoBufferSize);
  context->buffer = static_cast<jbyteArray>(env->NewGlobalRef(buffer));
  env->DeleteLocalRef(buffer);
  if (env->ExceptionCheck()) return false;
  jclass input_class = env->GetObjectClass(input);
  context->read = env->GetMethodID(input_class, "read", "([BII)I");
  context->seek = env->GetMethodID(input_class, "seek", "(JI)J");
  env->DeleteLocalRef(input_class);
  if (env->ExceptionCheck()) return false;
  auto* bytes = static_cast<uint8_t*>(av_malloc(kIoBufferSize));
  if (!bytes) return false;
  context->io = avio_alloc_context(bytes, kIoBufferSize, 0, context, Read, nullptr, Seek);
  if (!context->io) av_free(bytes);
  return context->io != nullptr;
}
}  // namespace lumy_rm
#endif
