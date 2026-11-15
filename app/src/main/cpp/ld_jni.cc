// JNI bridge for com.lightdecoder.heif.NativeDecoder.
//
// A decode session is a native Session object whose address is handed to Java
// as a long handle. Java is responsible for calling close() exactly once.
//
// The Android-only pieces (filling an android.graphics.Bitmap) are compiled out
// of the host build, where the same library is loaded by the JVM unit tests.

#include <jni.h>

#include <memory>
#include <string>

#include "ld_core.h"

#ifdef __ANDROID__
#include <android/bitmap.h>
#include <android/log.h>
#define LD_LOG_TAG "LightDecoder"
#endif

namespace {

struct Session {
  std::unique_ptr<ld::Decoder> decoder;
  std::string lastError;
};

Session* asSession(jlong handle) { return reinterpret_cast<Session*>(handle); }

jstring toJString(JNIEnv* env, const std::string& s) {
  return env->NewStringUTF(s.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativeVersions(JNIEnv* env, jclass) {
  return toJString(env, ld::versions());
}

JNIEXPORT jlong JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativeOpen(JNIEnv* env, jclass, jbyteArray data) {
  if (data == nullptr) return 0;
  const jsize len = env->GetArrayLength(data);
  if (len <= 0) return 0;

  auto session = std::make_unique<Session>();
  // GetByteArrayElements may copy; for files up to our size cap this is fine.
  jbyte* bytes = env->GetByteArrayElements(data, nullptr);
  if (!bytes) return 0;
  session->decoder = ld::Decoder::open(reinterpret_cast<const uint8_t*>(bytes),
                                       static_cast<size_t>(len), session->lastError);
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

  if (!session->decoder) {
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_WARN, LD_LOG_TAG, "open failed: %s",
                        session->lastError.c_str());
#endif
    return 0;
  }
  return reinterpret_cast<jlong>(session.release());
}

JNIEXPORT void JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativeClose(JNIEnv*, jclass, jlong handle) {
  delete asSession(handle);
}

JNIEXPORT jstring JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativeDescribe(JNIEnv* env, jclass, jlong handle) {
  Session* s = asSession(handle);
  if (!s || !s->decoder) return toJString(env, "");
  return toJString(env, s->decoder->describe());
}

JNIEXPORT jstring JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativeLastError(JNIEnv* env, jclass, jlong handle) {
  Session* s = asSession(handle);
  if (!s) return toJString(env, "no session");
  return toJString(env, s->lastError);
}

JNIEXPORT jint JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativeImageCount(JNIEnv*, jclass, jlong handle) {
  Session* s = asSession(handle);
  return (s && s->decoder) ? s->decoder->imageCount() : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativeHasSequence(JNIEnv*, jclass, jlong handle) {
  Session* s = asSession(handle);
  return (s && s->decoder && s->decoder->hasSequence()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativePrepareStill(JNIEnv*, jclass, jlong handle,
                                                            jint index) {
  Session* s = asSession(handle);
  if (!s || !s->decoder) return JNI_FALSE;
  return s->decoder->prepareStill(index, s->lastError) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativePrepareNextFrame(JNIEnv*, jclass, jlong handle) {
  Session* s = asSession(handle);
  if (!s || !s->decoder) return -1;
  return s->decoder->prepareNextFrame(s->lastError);
}

JNIEXPORT jboolean JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativeRewind(JNIEnv*, jclass, jlong handle) {
  Session* s = asSession(handle);
  if (!s || !s->decoder) return JNI_FALSE;
  return s->decoder->rewind(s->lastError) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativePreparedWidth(JNIEnv*, jclass, jlong handle) {
  Session* s = asSession(handle);
  return (s && s->decoder) ? s->decoder->preparedWidth() : 0;
}

JNIEXPORT jint JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativePreparedHeight(JNIEnv*, jclass, jlong handle) {
  Session* s = asSession(handle);
  return (s && s->decoder) ? s->decoder->preparedHeight() : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativePreparedHasAlpha(JNIEnv*, jclass, jlong handle) {
  Session* s = asSession(handle);
  return (s && s->decoder && s->decoder->preparedHasAlpha()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativePreparedDurationMs(JNIEnv*, jclass, jlong handle) {
  Session* s = asSession(handle);
  return (s && s->decoder) ? static_cast<jint>(s->decoder->preparedDurationMs()) : 0;
}

#ifdef __ANDROID__

// Copies the prepared frame into an ARGB_8888 Bitmap of matching size. Android
// Bitmaps store premultiplied alpha, so we premultiply on the way in.
JNIEXPORT jboolean JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativeFillBitmap(JNIEnv* env, jclass, jlong handle,
                                                          jobject bitmap) {
  Session* s = asSession(handle);
  if (!s || !s->decoder || !s->decoder->hasPrepared()) return JNI_FALSE;

  AndroidBitmapInfo info;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
    s->lastError = "cannot read bitmap info";
    return JNI_FALSE;
  }
  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
    s->lastError = "bitmap is not RGBA_8888";
    return JNI_FALSE;
  }
  if (static_cast<int>(info.width) != s->decoder->preparedWidth() ||
      static_cast<int>(info.height) != s->decoder->preparedHeight()) {
    s->lastError = "bitmap size does not match decoded frame";
    return JNI_FALSE;
  }

  void* pixels = nullptr;
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
    s->lastError = "cannot lock bitmap pixels";
    return JNI_FALSE;
  }
  const bool ok = s->decoder->copyPrepared(
      static_cast<uint8_t*>(pixels), static_cast<size_t>(info.stride) * info.height, info.stride,
      /*premultiplied=*/true, s->lastError);
  AndroidBitmap_unlockPixels(env, bitmap);
  return ok ? JNI_TRUE : JNI_FALSE;
}

#else

// Host build: no android/bitmap.h. Provide a stub so the symbol exists; the JVM
// tests exercise pixels through nativeCopyRGBA below instead.
JNIEXPORT jboolean JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativeFillBitmap(JNIEnv*, jclass, jlong, jobject) {
  return JNI_FALSE;
}

#endif  // __ANDROID__

// Copies the prepared frame as straight (non-premultiplied) RGBA into a Java
// byte[]. Used by the host unit tests, and handy on Android for hashing.
JNIEXPORT jbyteArray JNICALL
Java_com_lightdecoder_heif_NativeDecoder_nativeCopyRGBA(JNIEnv* env, jclass, jlong handle) {
  Session* s = asSession(handle);
  if (!s || !s->decoder || !s->decoder->hasPrepared()) return nullptr;
  const int w = s->decoder->preparedWidth();
  const int h = s->decoder->preparedHeight();
  const size_t stride = static_cast<size_t>(w) * 4;
  const size_t total = stride * static_cast<size_t>(h);

  jbyteArray out = env->NewByteArray(static_cast<jsize>(total));
  if (!out) return nullptr;
  jbyte* dst = env->GetByteArrayElements(out, nullptr);
  if (!dst) return nullptr;
  const bool ok = s->decoder->copyPrepared(reinterpret_cast<uint8_t*>(dst), total, stride,
                                           /*premultiplied=*/false, s->lastError);
  env->ReleaseByteArrayElements(out, dst, ok ? 0 : JNI_ABORT);
  return ok ? out : nullptr;
}

}  // extern "C"
