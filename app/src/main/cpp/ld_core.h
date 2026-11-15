// LightDecoder native core: HEIF/HEIC stills and image sequences to 8-bit RGBA.
//
// This layer has no JNI or Android dependencies, so the same code is exercised
// by the command-line probe and by the JVM unit tests on the build machine.

#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

struct heif_context;
struct heif_image;
struct heif_track;

namespace ld {

// Hard ceilings for what one file may ask of the phone. Files come from other
// people's messages, so they are treated as untrusted input.
constexpr uint64_t kMaxFileBytes = 256ull * 1024 * 1024;
constexpr uint64_t kMaxImagePixels = 50ull * 1000 * 1000;
constexpr uint64_t kMaxDecoderMemoryBytes = 768ull * 1024 * 1024;
constexpr uint32_t kMaxSequenceFrames = 4000;

// One opened file. Not thread-safe: use a Decoder from one thread at a time.
class Decoder {
 public:
  // Copies `data`. Returns null and fills `error` if the file cannot be read.
  static std::unique_ptr<Decoder> open(const uint8_t* data, size_t size, std::string& error);

  ~Decoder();
  Decoder(const Decoder&) = delete;
  Decoder& operator=(const Decoder&) = delete;

  // "key=value" lines describing the file. Keys are listed in ld_core.cc.
  const std::string& describe() const { return description_; }

  // Number of top-level still images. Index 0 is the primary image.
  int imageCount() const { return static_cast<int>(imageIds_.size()); }
  bool hasSequence() const { return track_ != nullptr; }

  // Decodes still image `index` and keeps it as the prepared image.
  bool prepareStill(int index, std::string& error);

  // Decodes the next frame of the sequence and keeps it as the prepared image.
  // Returns 1 when a frame was prepared, 0 at the end of the sequence and -1
  // on error.
  int prepareNextFrame(std::string& error);

  // Starts the sequence again from its first frame.
  bool rewind(std::string& error);

  bool hasPrepared() const { return prepared_ != nullptr; }
  int preparedWidth() const { return preparedWidth_; }
  int preparedHeight() const { return preparedHeight_; }
  bool preparedHasAlpha() const { return preparedHasAlpha_; }
  uint32_t preparedDurationMs() const { return preparedDurationMs_; }

  // Copies the prepared image as R,G,B,A bytes, one row every `dstStride`
  // bytes. With `premultiplied`, colour is multiplied by alpha, which is what
  // android.graphics.Bitmap stores.
  bool copyPrepared(uint8_t* dst, size_t dstSize, size_t dstStride, bool premultiplied,
                    std::string& error) const;

  void releasePrepared();

 private:
  Decoder() = default;
  bool load(std::string& error);
  void unload();
  void buildDescription();
  bool adoptPrepared(heif_image* image, bool hasAlpha, uint32_t durationMs, int maxWidth,
                     int maxHeight, std::string& error);

  std::vector<uint8_t> data_;
  heif_context* ctx_ = nullptr;
  heif_track* track_ = nullptr;
  std::vector<uint32_t> imageIds_;
  std::string description_;
  uint32_t framesDecoded_ = 0;
  int sequenceWidth_ = 0;
  int sequenceHeight_ = 0;

  heif_image* prepared_ = nullptr;
  int preparedWidth_ = 0;
  int preparedHeight_ = 0;
  bool preparedHasAlpha_ = false;
  bool preparedIsPremultiplied_ = false;
  uint32_t preparedDurationMs_ = 0;
};

// "libheif 1.23.7; libde265 HEVC decoder, version 1.1.3"
std::string versions();

}  // namespace ld
