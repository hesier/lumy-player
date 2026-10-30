#include "legacy_video.h"
#include "legacy_video_surface.h"

#define VIDEO_JNI(return_type, name) \
  extern "C" JNIEXPORT return_type JNICALL \
  Java_com_hesier_lumy_ui_player_legacy_NativeVideoDecoder_##name

VIDEO_JNI(jboolean, nativeIsAvailable)(JNIEnv*, jobject) {
  return avcodec_find_decoder(AV_CODEC_ID_H264) != nullptr &&
         avcodec_find_decoder(AV_CODEC_ID_RV40) != nullptr;
}

VIDEO_JNI(jlong, create)(JNIEnv* env, jobject, jstring codec_name, jbyteArray extra,
    jint width, jint height, jfloat pixel_ratio, jint color_space, jint color_range) {
  if (!SupportedSize(width, height) || codec_name == nullptr || extra == nullptr) {
    ThrowInitializationError(env, AVERROR(EINVAL));
    return 0;
  }
  const char* name = env->GetStringUTFChars(codec_name, nullptr);
  if (name == nullptr) return 0;
  AVCodecID id = AV_CODEC_ID_NONE;
  if (strcmp(name, "h264") == 0) id = AV_CODEC_ID_H264;
  if (strcmp(name, "rv40") == 0) id = AV_CODEC_ID_RV40;
  env->ReleaseStringUTFChars(codec_name, name);
  const AVCodec* decoder = avcodec_find_decoder(id);
  const int extra_size = env->GetArrayLength(extra);
  if (decoder == nullptr || extra_size > kMaxExtraBytes) {
    ThrowInitializationError(env, AVERROR_DECODER_NOT_FOUND);
    return 0;
  }
  VideoContext* context = new (std::nothrow) VideoContext;
  if (context == nullptr) {
    ThrowInitializationError(env, AVERROR(ENOMEM));
    return 0;
  }
  context->codec = avcodec_alloc_context3(decoder);
  context->frame = av_frame_alloc();
  context->packet = av_packet_alloc();
  int result = AVERROR(ENOMEM);
  if (context->codec != nullptr && context->frame != nullptr && context->packet != nullptr) {
    AVCodecContext* codec = context->codec;
    codec->width = width;
    codec->height = height;
    codec->pkt_timebase = AVRational{1, 1000000};
    codec->thread_count = 2;
    codec->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    codec->get_buffer2 = AllocateFrame;
    codec->max_pixels = static_cast<int64_t>(kMaxLongSide) * 1088;
    context->pixel_ratio = pixel_ratio;
    context->color_space = color_space;
    context->color_range = color_range;
    if (extra_size > 0) {
      codec->extradata = static_cast<uint8_t*>(av_mallocz(extra_size + AV_INPUT_BUFFER_PADDING_SIZE));
      if (codec->extradata != nullptr) {
        codec->extradata_size = extra_size;
        env->GetByteArrayRegion(extra, 0, extra_size, reinterpret_cast<jbyte*>(codec->extradata));
      }
    }
    if ((extra_size == 0 || codec->extradata != nullptr) && !env->ExceptionCheck()) {
      result = avcodec_open2(codec, decoder, nullptr);
    }
  }
  if (result >= 0) {
    jclass output = env->FindClass("com/hesier/lumy/ui/player/legacy/SoftwareVideoOutputBuffer");
    if (output != nullptr) {
      context->init_rgba = env->GetMethodID(output, "initRgba", "(JIIF)Ljava/nio/ByteBuffer;");
      env->DeleteLocalRef(output);
    }
    if (context->init_rgba == nullptr) result = AVERROR(EINVAL);
  }
  if (result < 0 || env->ExceptionCheck()) {
    delete context;
    if (!env->ExceptionCheck()) ThrowInitializationError(env, result);
    return 0;
  }
  return static_cast<jlong>(reinterpret_cast<intptr_t>(context));
}

VIDEO_JNI(jint, send)(JNIEnv* env, jobject, jlong handle, jobject data,
    jint offset, jint size, jlong time_us, jboolean end_of_stream) {
  VideoContext* context = Context(handle);
  if (context == nullptr) return AVERROR(EINVAL);
  if (end_of_stream) return avcodec_send_packet(context->codec, nullptr);
  if (data == nullptr || offset < 0 || size <= 0 || size > kMaxPacketBytes) return AVERROR_INVALIDDATA;
  uint8_t* source = static_cast<uint8_t*>(env->GetDirectBufferAddress(data));
  if (source == nullptr || static_cast<int64_t>(offset) + size > env->GetDirectBufferCapacity(data)) {
    return AVERROR(EINVAL);
  }
  av_packet_unref(context->packet);
  // av_new_packet supplies FFmpeg's mandatory zeroed SIMD overread padding.
  int result = av_new_packet(context->packet, size);
  if (result < 0) return result;
  memcpy(context->packet->data, source + offset, size);
  context->packet->pts = time_us;
  context->packet->dts = AV_NOPTS_VALUE;
  context->packet->time_base = AVRational{1, 1000000};
  result = avcodec_send_packet(context->codec, context->packet);
  av_packet_unref(context->packet);
  return result;
}

VIDEO_JNI(jint, receive)(JNIEnv* env, jobject, jlong handle, jobject output) {
  VideoContext* context = Context(handle);
  if (context == nullptr || output == nullptr) return AVERROR(EINVAL);
  int result = avcodec_receive_frame(context->codec, context->frame);
  if (result == AVERROR(EAGAIN)) return 0;
  if (result == AVERROR_EOF) return 2;
  if (result < 0) return result;
  result = ConvertFrame(env, context, output);
  av_frame_unref(context->frame);
  return result;
}

VIDEO_JNI(void, flush)(JNIEnv*, jobject, jlong handle) {
  VideoContext* context = Context(handle);
  if (context == nullptr) return;
  avcodec_flush_buffers(context->codec);
  av_packet_unref(context->packet);
  av_frame_unref(context->frame);
}

VIDEO_JNI(void, close)(JNIEnv*, jobject, jlong handle) {
  delete Context(handle);
}

VIDEO_JNI(jlong, createRenderer)(JNIEnv* env, jobject, jobject surface) {
  VideoSurface* renderer = new (std::nothrow) VideoSurface;
  if (renderer == nullptr) {
    ThrowInitializationError(env, AVERROR(ENOMEM));
    return 0;
  }
  if (surface == nullptr || !InitializeVideoSurface(env, surface, renderer)) {
    __android_log_print(ANDROID_LOG_ERROR, "LumySoftwareVideo", "EGL initialization: 0x%x", eglGetError());
    delete renderer;
    if (!env->ExceptionCheck()) ThrowInitializationError(env, AVERROR(EIO));
    return 0;
  }
  return static_cast<jlong>(reinterpret_cast<intptr_t>(renderer));
}

VIDEO_JNI(jint, render)(JNIEnv* env, jobject, jlong handle, jobject data, jint width, jint height) {
  VideoSurface* renderer = reinterpret_cast<VideoSurface*>(static_cast<intptr_t>(handle));
  if (renderer == nullptr || !SupportedSize(width, height) || data == nullptr) return AVERROR(EINVAL);
  uint8_t* pixels = static_cast<uint8_t*>(env->GetDirectBufferAddress(data));
  const int64_t bytes = static_cast<int64_t>(width) * height * kBytesPerPixel;
  if (pixels == nullptr || env->GetDirectBufferCapacity(data) < bytes) return AVERROR(EINVAL);
  return RenderVideoTexture(renderer, pixels, width, height);
}

VIDEO_JNI(void, releaseRenderer)(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<VideoSurface*>(static_cast<intptr_t>(handle));
}

VIDEO_JNI(jstring, errorString)(JNIEnv* env, jobject, jint error) {
  char text[AV_ERROR_MAX_STRING_SIZE];
  av_strerror(error, text, sizeof(text));
  return env->NewStringUTF(text);
}
