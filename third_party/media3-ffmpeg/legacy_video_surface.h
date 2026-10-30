#ifndef LUMY_LEGACY_VIDEO_SURFACE_H
#define LUMY_LEGACY_VIDEO_SURFACE_H

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <mutex>

namespace {
// The process shares the default display with subtitle/UI EGL users. Keep one bounded
// initialization, and destroy only the surfaces/contexts owned by this renderer.
EGLDisplay VideoDisplay() {
  static std::mutex mutex;
  static EGLDisplay display = EGL_NO_DISPLAY;
  std::lock_guard<std::mutex> guard(mutex);
  if (display == EGL_NO_DISPLAY) {
    EGLDisplay candidate = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (candidate != EGL_NO_DISPLAY && eglInitialize(candidate, nullptr, nullptr)) display = candidate;
  }
  return display;
}

struct VideoEglBinding {
  EGLDisplay previous_display = eglGetCurrentDisplay();
  EGLContext previous_context = eglGetCurrentContext();
  EGLSurface previous_draw = eglGetCurrentSurface(EGL_DRAW);
  EGLSurface previous_read = eglGetCurrentSurface(EGL_READ);
  EGLDisplay bound_display = EGL_NO_DISPLAY;

  bool Bind(EGLDisplay display, EGLSurface surface, EGLContext context) {
    if (!eglMakeCurrent(display, surface, surface, context)) return false;
    bound_display = display;
    return true;
  }

  ~VideoEglBinding() {
    if (bound_display == EGL_NO_DISPLAY) return;
    if (previous_context == EGL_NO_CONTEXT) {
      eglMakeCurrent(bound_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    } else {
      eglMakeCurrent(previous_display, previous_draw, previous_read, previous_context);
    }
  }
};

struct VideoSurface {
  EGLDisplay display = EGL_NO_DISPLAY;
  EGLContext context = EGL_NO_CONTEXT;
  EGLSurface surface = EGL_NO_SURFACE;
  ANativeWindow* window = nullptr;
  GLuint program = 0;
  GLuint texture = 0;
  GLint position = -1;
  GLint coordinates = -1;
  int texture_width = 0;
  int texture_height = 0;

  ~VideoSurface() {
    if (display != EGL_NO_DISPLAY) {
      if (context != EGL_NO_CONTEXT && eglGetCurrentContext() == context) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
      }
      // eglDestroySurface disconnects the EGL producer so MediaCodec can reuse this Surface.
      if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
      if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
    }
    if (window != nullptr) ANativeWindow_release(window);
  }
};

GLuint CompileVideoShader(GLenum type, const char* source) {
  GLuint shader = glCreateShader(type);
  glShaderSource(shader, 1, &source, nullptr);
  glCompileShader(shader);
  GLint compiled = GL_FALSE;
  glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
  if (!compiled) {
    char log[512] = {};
    glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
    __android_log_print(ANDROID_LOG_ERROR, "LumySoftwareVideo", "Shader: %s", log);
    glDeleteShader(shader);
    return 0;
  }
  return shader;
}

bool InitializeVideoProgram(VideoSurface* renderer) {
  const char* vertex_source =
      "attribute vec2 position; attribute vec2 coordinates; varying vec2 uv;"
      "void main(){ gl_Position=vec4(position,0.,1.); uv=coordinates; }";
  const char* fragment_source =
      "precision mediump float; varying vec2 uv; uniform sampler2D frame;"
      "void main(){ gl_FragColor=texture2D(frame,uv); }";
  GLuint vertex = CompileVideoShader(GL_VERTEX_SHADER, vertex_source);
  GLuint fragment = CompileVideoShader(GL_FRAGMENT_SHADER, fragment_source);
  if (vertex == 0 || fragment == 0) {
    if (vertex != 0) glDeleteShader(vertex);
    if (fragment != 0) glDeleteShader(fragment);
    return false;
  }
  renderer->program = glCreateProgram();
  glAttachShader(renderer->program, vertex);
  glAttachShader(renderer->program, fragment);
  glLinkProgram(renderer->program);
  glDeleteShader(vertex);
  glDeleteShader(fragment);
  GLint linked = GL_FALSE;
  glGetProgramiv(renderer->program, GL_LINK_STATUS, &linked);
  if (!linked) return false;
  renderer->position = glGetAttribLocation(renderer->program, "position");
  renderer->coordinates = glGetAttribLocation(renderer->program, "coordinates");
  glUseProgram(renderer->program);
  glUniform1i(glGetUniformLocation(renderer->program, "frame"), 0);
  glGenTextures(1, &renderer->texture);
  glBindTexture(GL_TEXTURE_2D, renderer->texture);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  return renderer->position >= 0 && renderer->coordinates >= 0 && glGetError() == GL_NO_ERROR;
}

bool InitializeVideoSurface(JNIEnv* env, jobject surface, VideoSurface* renderer) {
  renderer->display = VideoDisplay();
  if (renderer->display == EGL_NO_DISPLAY || !eglBindAPI(EGL_OPENGL_ES_API)) return false;
  const EGLint config_attributes[] = {
      EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
      EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
  EGLConfig config;
  EGLint count = 0;
  if (!eglChooseConfig(renderer->display, config_attributes, &config, 1, &count) || count != 1) return false;
  renderer->window = ANativeWindow_fromSurface(env, surface);
  if (renderer->window == nullptr) return false;
  EGLint format;
  if (!eglGetConfigAttrib(renderer->display, config, EGL_NATIVE_VISUAL_ID, &format) ||
      ANativeWindow_setBuffersGeometry(renderer->window, 0, 0, format) != 0) return false;
  const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
  renderer->context = eglCreateContext(renderer->display, config, EGL_NO_CONTEXT, context_attributes);
  if (renderer->context == EGL_NO_CONTEXT) return false;
  renderer->surface = eglCreateWindowSurface(renderer->display, config, renderer->window, nullptr);
  if (renderer->surface == EGL_NO_SURFACE) return false;
  VideoEglBinding binding;
  return binding.Bind(renderer->display, renderer->surface, renderer->context) && InitializeVideoProgram(renderer);
}

int RenderVideoTexture(VideoSurface* renderer, const uint8_t* pixels, int width, int height) {
  VideoEglBinding binding;
  if (!binding.Bind(renderer->display, renderer->surface, renderer->context)) return AVERROR(EIO);
  EGLint surface_width, surface_height;
  if (!eglQuerySurface(renderer->display, renderer->surface, EGL_WIDTH, &surface_width) ||
      !eglQuerySurface(renderer->display, renderer->surface, EGL_HEIGHT, &surface_height)) return AVERROR(EIO);
  glViewport(0, 0, surface_width, surface_height);
  glUseProgram(renderer->program);
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, renderer->texture);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
  if (renderer->texture_width != width || renderer->texture_height != height) {
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    renderer->texture_width = width;
    renderer->texture_height = height;
  }
  glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
  const GLfloat positions[] = {-1, -1, 1, -1, -1, 1, 1, 1};
  // CPU row zero is the top row, while GL's default origin is at the bottom.
  const GLfloat coordinates[] = {0, 1, 1, 1, 0, 0, 1, 0};
  glVertexAttribPointer(renderer->position, 2, GL_FLOAT, GL_FALSE, 0, positions);
  glVertexAttribPointer(renderer->coordinates, 2, GL_FLOAT, GL_FALSE, 0, coordinates);
  glEnableVertexAttribArray(renderer->position);
  glEnableVertexAttribArray(renderer->coordinates);
  glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
  if (glGetError() != GL_NO_ERROR || !eglSwapBuffers(renderer->display, renderer->surface)) return AVERROR(EIO);
  return 0;
}
}  // namespace

#endif  // LUMY_LEGACY_VIDEO_SURFACE_H
