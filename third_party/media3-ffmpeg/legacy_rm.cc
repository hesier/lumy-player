#include "legacy_rm_io.h"

#include <algorithm>
#include <climits>
#include <memory>
#include <vector>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavutil/dict.h"
}

namespace lumy_rm {
constexpr int kMaxPacketSize = 16 * 1024 * 1024;
constexpr int kMaxIndexEntries = 100000;

static const char* Mime(AVCodecID codec) {
  switch (codec) {
    case AV_CODEC_ID_RV40: return "video/realvideo";
    case AV_CODEC_ID_COOK: return "audio/cook";
    case AV_CODEC_ID_AAC: return "audio/mp4a-latm";
    case AV_CODEC_ID_AC3: return "audio/ac3";
    case AV_CODEC_ID_MP3: return "audio/mpeg";
    default: return nullptr;
  }
}

static jstring Metadata(JNIEnv* env, AVStream* stream, const char* key) {
  const AVDictionaryEntry* entry = av_dict_get(stream->metadata, key, nullptr, 0);
  return entry ? env->NewStringUTF(entry->value) : nullptr;
}

static bool EmitTrack(Context* context, AVStream* stream, jmethodID method) {
  const AVCodecParameters* codec = stream->codecpar;
  const char* mime = Mime(codec->codec_id);
  if (!mime) return true;
  JNIEnv* env = context->env;
  jlong values[] = {codec->width > 0 ? codec->width : -1,
                   codec->height > 0 ? codec->height : -1,
                   codec->ch_layout.nb_channels > 0 ? codec->ch_layout.nb_channels : -1,
                   codec->sample_rate > 0 ? codec->sample_rate : -1,
                   codec->bit_rate > 0 ? std::min(codec->bit_rate, int64_t(INT_MAX)) : -1,
                   codec->block_align, (stream->disposition & AV_DISPOSITION_DEFAULT) ? 1 : 0};
  jlongArray parameters = env->NewLongArray(7);
  jbyteArray extra = env->NewByteArray(codec->extradata_size);
  jstring mime_string = env->NewStringUTF(mime);
  jstring language = Metadata(env, stream, "language");
  jstring label = Metadata(env, stream, "title");
  if (!env->ExceptionCheck()) {
    env->SetLongArrayRegion(parameters, 0, 7, values);
    if (codec->extradata_size > 0)
      env->SetByteArrayRegion(extra, 0, codec->extradata_size,
                             reinterpret_cast<const jbyte*>(codec->extradata));
    if (!env->ExceptionCheck())
      env->CallVoidMethod(context->owner, method, stream->index, mime_string,
                          parameters, extra, language, label);
  }
  env->DeleteLocalRef(parameters);
  env->DeleteLocalRef(extra);
  env->DeleteLocalRef(mime_string);
  env->DeleteLocalRef(language);
  env->DeleteLocalRef(label);
  return !env->ExceptionCheck();
}

static int64_t DurationUs(AVFormatContext* format) {
  int64_t duration = format->duration;
  for (unsigned i = 0; i < format->nb_streams; ++i) {
    AVStream* stream = format->streams[i];
    if (stream->duration != AV_NOPTS_VALUE)
      duration = std::max(duration, av_rescale_q(stream->duration, stream->time_base, AV_TIME_BASE_Q));
  }
  return duration == AV_NOPTS_VALUE ? kTimeUnset : duration;
}

static bool EmitPrepared(Context* context, jmethodID method) {
  AVStream* stream = context->seek_stream >= 0 ? context->format->streams[context->seek_stream] : nullptr;
  // Entries collected by the AAC probe only cover the beginning, not the complete seek range.
  int count = stream && context->header_index_counts[stream->index] > 0
                  ? avformat_index_get_entries_count(stream) : 0;
  int stride = std::max(1, (count + kMaxIndexEntries - 1) / kMaxIndexEntries);
  std::vector<jlong> positions, times;
  for (int i = 0; i < count; i += stride) {
    const AVIndexEntry* entry = avformat_index_get_entry(stream, i);
    if (entry->pos < 0 || !(entry->flags & AVINDEX_KEYFRAME)) continue;
    int64_t timestamp = av_rescale_q(entry->timestamp, stream->time_base, AV_TIME_BASE_Q);
    if (timestamp < 0 || (!times.empty() && timestamp <= times.back())) continue;
    positions.push_back(entry->pos);
    times.push_back(timestamp);
  }
  JNIEnv* env = context->env;
  jlongArray java_positions = env->NewLongArray(positions.size());
  jlongArray java_times = env->NewLongArray(times.size());
  if (!env->ExceptionCheck()) {
    env->SetLongArrayRegion(java_positions, 0, positions.size(), positions.data());
    env->SetLongArrayRegion(java_times, 0, times.size(), times.data());
    if (!env->ExceptionCheck())
      env->CallVoidMethod(context->owner, method, static_cast<jlong>(DurationUs(context->format)),
                          static_cast<jboolean>(context->io->seekable && context->seek_stream >= 0),
                          java_positions, java_times);
  }
  env->DeleteLocalRef(java_positions);
  env->DeleteLocalRef(java_times);
  return !env->ExceptionCheck();
}

static int OpenFormat(Context* context) {
  context->format = avformat_alloc_context();
  if (!context->format) return AVERROR(ENOMEM);
  context->format->pb = context->io;
  context->format->flags |= AVFMT_FLAG_CUSTOM_IO;
  context->format->interrupt_callback = {Interrupted, context};
  const AVInputFormat* demuxer = av_find_input_format("rm");
  if (!demuxer) return AVERROR_DEMUXER_NOT_FOUND;
  int result = avformat_open_input(&context->format, nullptr, demuxer, nullptr);
  if (result < 0 || context->env->ExceptionCheck()) return result;
  context->header_index_counts.clear();
  for (unsigned i = 0; i < context->format->nb_streams; ++i) {
    context->header_index_counts.push_back(avformat_index_get_entries_count(context->format->streams[i]));
  }
  // Seed FFmpeg's stream clocks/seeking state and raw AAC frame duration with a bounded probe.
  context->format->probesize = 256 * 1024;
  context->format->max_analyze_duration = AV_TIME_BASE;
  result = avformat_find_stream_info(context->format, nullptr);
  context->audio_clocks.assign(context->format->nb_streams, AudioClock());
  return result;
}

static bool EmitHeader(Context* context) {
  JNIEnv* env = context->env;
  jclass type = env->GetObjectClass(context->owner);
  jmethodID track = env->GetMethodID(type, "onTrack", "(ILjava/lang/String;[J[BLjava/lang/String;Ljava/lang/String;)V");
  jmethodID prepared = env->GetMethodID(type, "onPrepared", "(JZ[J[J)V");
  context->packet = env->GetMethodID(type, "onPacket", "(I[BJZ)V");
  env->DeleteLocalRef(type);
  if (env->ExceptionCheck()) return false;
  for (unsigned i = 0; i < context->format->nb_streams; ++i) {
    AVStream* stream = context->format->streams[i];
    if (stream->codecpar->codec_type == AVMEDIA_TYPE_VIDEO &&
        stream->codecpar->codec_id != AV_CODEC_ID_RV40) {
      ThrowIo(env, "unsupported video codec", AVERROR_DECODER_NOT_FOUND);
      return false;
    }
    if (!Mime(stream->codecpar->codec_id)) {
      stream->discard = AVDISCARD_ALL;
      continue;
    }
    if (context->seek_stream < 0 || stream->codecpar->codec_type == AVMEDIA_TYPE_VIDEO)
      context->seek_stream = stream->index;
    if (!EmitTrack(context, stream, track)) return false;
  }
  return EmitPrepared(context, prepared);
}

static int64_t PacketTimeUs(Context* context, AVStream* stream, AVPacket* packet) {
  int64_t timestamp = packet->pts != AV_NOPTS_VALUE ? packet->pts : packet->dts;
  if (timestamp != AV_NOPTS_VALUE)
    timestamp = av_rescale_q(timestamp, stream->time_base, AV_TIME_BASE_Q);
  const AVCodecParameters* codec = stream->codecpar;
  if (codec->codec_type == AVMEDIA_TYPE_AUDIO && codec->sample_rate > 0) {
    AudioClock& clock = context->audio_clocks[packet->stream_index];
    if (timestamp != AV_NOPTS_VALUE) {
      clock.anchor_us = timestamp;
      clock.samples = 0;
    } else if (clock.anchor_us != AV_NOPTS_VALUE) {
      timestamp = clock.anchor_us + av_rescale_q(clock.samples, AVRational{1, codec->sample_rate}, AV_TIME_BASE_Q);
    }
    int samples = av_get_audio_frame_duration2(const_cast<AVCodecParameters*>(codec), packet->size);
    if (samples <= 0 && packet->duration > 0)
      samples = av_rescale_q(packet->duration, stream->time_base, AVRational{1, codec->sample_rate});
    if (samples > 0) clock.samples += samples;
    else clock.anchor_us = AV_NOPTS_VALUE;
  }
  return timestamp;
}

static bool EmitPacket(Context* context) {
  AVPacket* packet = context->sample;
  AVStream* stream = context->format->streams[packet->stream_index];
  if (!Mime(stream->codecpar->codec_id)) return true;
  if (packet->size <= 0 || packet->size > kMaxPacketSize) {
    ThrowIo(context->env, "invalid packet size", AVERROR_INVALIDDATA);
    return false;
  }
  int64_t timestamp = PacketTimeUs(context, stream, packet);
  if (timestamp == AV_NOPTS_VALUE) {
    ThrowIo(context->env, "missing packet timestamp", AVERROR_INVALIDDATA);
    return false;
  }
  JNIEnv* env = context->env;
  jbyteArray bytes = env->NewByteArray(packet->size);
  if (bytes) {
    env->SetByteArrayRegion(bytes, 0, packet->size, reinterpret_cast<jbyte*>(packet->data));
    if (!env->ExceptionCheck())
      env->CallVoidMethod(context->owner, context->packet, packet->stream_index, bytes,
                          static_cast<jlong>(timestamp), static_cast<jboolean>(
                              (packet->flags & AV_PKT_FLAG_KEY) || stream->codecpar->codec_type == AVMEDIA_TYPE_AUDIO));
    env->DeleteLocalRef(bytes);
  }
  return !env->ExceptionCheck();
}
}  // namespace lumy_rm

using lumy_rm::Context;
#define RM_JNI(method) Java_com_hesier_lumy_ui_player_legacy_NativeRmDemuxer_##method

extern "C" JNIEXPORT jlong JNICALL RM_JNI(nativeOpen)(JNIEnv* env, jobject owner, jobject input) {
  std::unique_ptr<Context> context(new Context());
  context->env = env;
  if (!lumy_rm::InitializeIo(context.get(), owner, input)) {
    lumy_rm::ThrowIo(env, "allocate AVIO", AVERROR(ENOMEM));
    return 0;
  }
  context->sample = av_packet_alloc();
  if (!context->sample) {
    lumy_rm::ThrowIo(env, "allocate demuxer", AVERROR(ENOMEM));
    return 0;
  }
  int result = lumy_rm::OpenFormat(context.get());
  if (result < 0 || env->ExceptionCheck()) {
    lumy_rm::ThrowIo(env, "read header", result);
    return 0;
  }
  // Other RM tracks already carry their CSD and duration in the header.
  if (!lumy_rm::EmitHeader(context.get())) return 0;
  return reinterpret_cast<jlong>(context.release());
}

extern "C" JNIEXPORT jint JNICALL RM_JNI(nativeRead)(JNIEnv* env, jobject, jlong handle) {
  auto* context = reinterpret_cast<Context*>(handle);
  context->env = env;
  av_packet_unref(context->sample);
  int result = av_read_frame(context->format, context->sample);
  if (env->ExceptionCheck()) return 0;
  if (result == AVERROR_EOF) return -1;
  if (result == AVERROR(EAGAIN)) return 0;
  if (result < 0) {
    lumy_rm::ThrowIo(env, "read packet", result);
    return 0;
  }
  lumy_rm::EmitPacket(context);
  av_packet_unref(context->sample);
  return 0;
}

extern "C" JNIEXPORT void JNICALL RM_JNI(nativeSeek)(JNIEnv* env, jobject, jlong handle, jlong time_us) {
  auto* context = reinterpret_cast<Context*>(handle);
  context->env = env;
  av_packet_unref(context->sample);
  // RM read_seek does not reset Cook's partially filled deinterleaving groups.
  avformat_close_input(&context->format);
  context->io->error = 0;
  context->io->eof_reached = 0;
  int64_t position = avio_seek(context->io, 0, SEEK_SET);
  int result = position < 0 ? static_cast<int>(position) : lumy_rm::OpenFormat(context);
  if (result < 0 || env->ExceptionCheck()) {
    lumy_rm::ThrowIo(env, "reopen for seek", result);
    return;
  }
  if (time_us == 0) return;
  AVStream* stream = context->format->streams[context->seek_stream];
  int64_t timestamp = av_rescale_q(time_us, AV_TIME_BASE_Q, stream->time_base);
  result = av_seek_frame(context->format, context->seek_stream, timestamp, AVSEEK_FLAG_BACKWARD);
  if (result < 0) lumy_rm::ThrowIo(env, "seek", result);
}

extern "C" JNIEXPORT void JNICALL RM_JNI(nativeRelease)(JNIEnv* env, jobject, jlong handle) {
  auto* context = reinterpret_cast<Context*>(handle);
  if (!context) return;
  context->env = env;
  delete context;
}
