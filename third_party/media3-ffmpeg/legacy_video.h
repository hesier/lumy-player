#ifndef LUMY_LEGACY_VIDEO_H
#define LUMY_LEGACY_VIDEO_H

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <new>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>
}

namespace {
constexpr int kMaxLongSide = 1920;
constexpr int kMaxShortSide = 1080;
constexpr int kMaxPacketBytes = 16 * 1024 * 1024;
constexpr int kMaxExtraBytes = 1024 * 1024;
constexpr int kBytesPerPixel = 4;

struct VideoContext {
  AVCodecContext* codec = nullptr;
  AVFrame* frame = nullptr;
  AVPacket* packet = nullptr;
  SwsContext* scaler = nullptr;
  float pixel_ratio = 1;
  int color_space = -1;
  int color_range = -1;
  jmethodID init_rgba = nullptr;

  ~VideoContext() {
    avcodec_free_context(&codec);
    av_frame_free(&frame);
    av_packet_free(&packet);
    sws_freeContext(scaler);
  }
};

bool SupportedSize(int width, int height, int short_side = kMaxShortSide) {
  return width > 0 && height > 0 && width <= kMaxLongSide &&
         height <= kMaxLongSide && (width <= short_side || height <= short_side);
}

int AllocateFrame(AVCodecContext* context, AVFrame* frame, int flags) {
  // H.264 may allocate 1088 coded rows for a cropped 1080-line picture.
  if (!SupportedSize(frame->width, frame->height, 1088)) return AVERROR(EINVAL);
  return avcodec_default_get_buffer2(context, frame, flags);
}

VideoContext* Context(jlong handle) {
  return reinterpret_cast<VideoContext*>(static_cast<intptr_t>(handle));
}

void ThrowInitializationError(JNIEnv* env, int error) {
  char text[AV_ERROR_MAX_STRING_SIZE];
  av_strerror(error, text, sizeof(text));
  jclass exception = env->FindClass("java/lang/IllegalStateException");
  if (exception != nullptr) env->ThrowNew(exception, text);
}

int SourceColorspace(const AVFrame* frame, int fallback) {
  switch (frame->colorspace) {
    case AVCOL_SPC_BT709: return SWS_CS_ITU709;
    case AVCOL_SPC_BT2020_NCL:
    case AVCOL_SPC_BT2020_CL: return SWS_CS_BT2020;
    case AVCOL_SPC_FCC: return SWS_CS_FCC;
    case AVCOL_SPC_SMPTE240M: return SWS_CS_SMPTE240M;
    case AVCOL_SPC_BT470BG:
    case AVCOL_SPC_SMPTE170M: return SWS_CS_ITU601;
    default:
      // Values match Media3 C.COLOR_SPACE_*.
      if (fallback == 1) return SWS_CS_ITU709;
      if (fallback == 2) return SWS_CS_ITU601;
      if (fallback == 6) return SWS_CS_BT2020;
      return frame->height > 576 ? SWS_CS_ITU709 : SWS_CS_ITU601;
  }
}

bool FullRange(const AVFrame* frame, int fallback) {
  if (frame->color_range != AVCOL_RANGE_UNSPECIFIED) return frame->color_range == AVCOL_RANGE_JPEG;
  const AVPixFmtDescriptor* descriptor = av_pix_fmt_desc_get(static_cast<AVPixelFormat>(frame->format));
  if (descriptor != nullptr && (descriptor->flags & AV_PIX_FMT_FLAG_RGB)) return true;
  return fallback == 1;
}

int ConvertFrame(JNIEnv* env, VideoContext* context, jobject output) {
  AVFrame* frame = context->frame;
  if (!SupportedSize(frame->width, frame->height)) return AVERROR(EINVAL);
  if (frame->color_trc == AVCOL_TRC_SMPTE2084 || frame->color_trc == AVCOL_TRC_ARIB_STD_B67) {
    return AVERROR(ENOSYS);
  }
  if (frame->best_effort_timestamp == AV_NOPTS_VALUE) return AVERROR_INVALIDDATA;
  float ratio = context->pixel_ratio;
  if (frame->sample_aspect_ratio.num > 0 && frame->sample_aspect_ratio.den > 0) {
    ratio = static_cast<float>(av_q2d(frame->sample_aspect_ratio));
  }
  if (!std::isfinite(ratio) || ratio <= 0) ratio = 1;
  jobject buffer = env->CallObjectMethod(output, context->init_rgba,
      static_cast<jlong>(frame->best_effort_timestamp), frame->width, frame->height, ratio);
  if (env->ExceptionCheck() || buffer == nullptr) return AVERROR(ENOMEM);
  uint8_t* pixels = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
  const int stride = frame->width * kBytesPerPixel;
  if (pixels == nullptr || env->GetDirectBufferCapacity(buffer) < static_cast<int64_t>(stride) * frame->height) {
    env->DeleteLocalRef(buffer);
    return AVERROR(EINVAL);
  }
  context->scaler = sws_getCachedContext(context->scaler, frame->width, frame->height,
      static_cast<AVPixelFormat>(frame->format), frame->width, frame->height, AV_PIX_FMT_RGBA,
      SWS_BILINEAR, nullptr, nullptr, nullptr);
  if (context->scaler == nullptr) {
    env->DeleteLocalRef(buffer);
    return AVERROR(ENOMEM);
  }
  const int* coefficients = sws_getCoefficients(SourceColorspace(frame, context->color_space));
  int result = sws_setColorspaceDetails(context->scaler, coefficients,
      FullRange(frame, context->color_range), coefficients, 1, 0, 1 << 16, 1 << 16);
  if (result >= 0) {
    uint8_t* planes[] = {pixels, nullptr, nullptr, nullptr};
    int strides[] = {stride, 0, 0, 0};
    result = sws_scale(context->scaler, frame->data, frame->linesize, 0, frame->height, planes, strides);
    result = result == frame->height ? 1 : AVERROR_INVALIDDATA;
  }
  env->DeleteLocalRef(buffer);
  return result;
}
}  // namespace

#endif  // LUMY_LEGACY_VIDEO_H
