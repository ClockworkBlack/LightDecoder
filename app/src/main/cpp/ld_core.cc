#include "ld_core.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <mutex>

#include "libheif/heif.h"
#include "libheif/heif_sequences.h"

// Keys written by Decoder::describe(), one "key=value" per line:
//
//   mime            what libheif makes of the file header, e.g. image/heic
//   brands          ftyp brands, comma separated
//   bytes           file size
//   still_images    number of top-level still images
//   width, height   primary still, after rotation and cropping
//   alpha           1 if the primary still has transparency
//   premultiplied   1 if its colour is stored multiplied by alpha
//   luma_bits       bit depth of the primary still
//   aux_images      auxiliary images attached to it (alpha plane, depth map)
//   color           "nclx:<primaries>/<transfer>/<matrix>/<full|limited>",
//                   "icc:<bytes>" or "none"
//   sequence        1 if the file holds an animation
//   seq_width, seq_height, seq_alpha, seq_duration_ms, seq_handler
//   seq_repeat      plays requested by the file; 4294967295 means forever

namespace ld {
namespace {

// libheif must not be shut down from a static destructor (its own globals may
// already be gone by then), so it is initialised once and left alone.
void ensureInit() {
  static std::once_flag once;
  std::call_once(once, [] { heif_init(nullptr); });
}

void applyLimits(heif_context* ctx) {
  heif_security_limits* limits = heif_context_get_security_limits(ctx);
  if (!limits) return;
  limits->max_image_size_pixels = kMaxImagePixels;
  if (limits->version >= 2) {
    limits->max_total_memory = kMaxDecoderMemoryBytes;
  }
  if (limits->version >= 3) {
    limits->max_sequence_frames = kMaxSequenceFrames;
  }
}

heif_decoding_options* makeOptions() {
  heif_decoding_options* options = heif_decoding_options_alloc();
  if (!options) return nullptr;
  if (options->version >= 2) {
    options->convert_hdr_to_8bit = 1;
  }
  if (options->version >= 8) {
    // Play the media timeline once. Files may ask for endless repetition, and
    // libheif would then never report the end of the sequence.
    options->ignore_sequence_editlist = 1;
  }
  return options;
}

uint32_t ticksToMs(uint64_t ticks, uint32_t timescale) {
  if (timescale == 0) return 0;
  const uint64_t ms = (ticks * 1000 + timescale / 2) / timescale;
  return ms > 0xFFFFFFFFull ? 0xFFFFFFFFu : static_cast<uint32_t>(ms);
}

std::string fourcc(uint32_t v) {
  std::string s(4, ' ');
  for (int i = 0; i < 4; ++i) {
    const char c = static_cast<char>((v >> (24 - 8 * i)) & 0xFF);
    s[i] = (c >= 32 && c < 127 && c != '=' && c != ',') ? c : '?';
  }
  return s;
}

void addLine(std::string& out, const char* key, const std::string& value) {
  out += key;
  out += '=';
  out += value;
  out += '\n';
}

void addLine(std::string& out, const char* key, long long value) {
  addLine(out, key, std::to_string(value));
}

std::string errorText(const heif_error& err, const char* fallback) {
  return (err.message && err.message[0]) ? err.message : fallback;
}

inline uint8_t mul255(uint32_t c, uint32_t a) {
  const uint32_t t = c * a + 128;
  return static_cast<uint8_t>((t + (t >> 8)) >> 8);
}

}  // namespace

std::string versions() {
  ensureInit();
  std::string out = "libheif ";
  out += heif_get_version();
  const heif_decoder_descriptor* decoders[4];
  const int n = heif_get_decoder_descriptors(heif_compression_HEVC, decoders, 4);
  for (int i = 0; i < n && i < 4; ++i) {
    const char* name = heif_decoder_descriptor_get_name(decoders[i]);
    if (name) {
      out += "; ";
      out += name;
    }
  }
  return out;
}

std::unique_ptr<Decoder> Decoder::open(const uint8_t* data, size_t size, std::string& error) {
  ensureInit();
  if (data == nullptr || size == 0) {
    error = "The file is empty";
    return nullptr;
  }
  if (size > kMaxFileBytes) {
    error = "The file is too large";
    return nullptr;
  }
  std::unique_ptr<Decoder> decoder(new Decoder());
  decoder->data_.assign(data, data + size);
  if (!decoder->load(error)) {
    return nullptr;
  }
  return decoder;
}

Decoder::~Decoder() { unload(); }

void Decoder::unload() {
  releasePrepared();
  if (track_) {
    heif_track_release(track_);
    track_ = nullptr;
  }
  if (ctx_) {
    heif_context_free(ctx_);
    ctx_ = nullptr;
  }
  imageIds_.clear();
  framesDecoded_ = 0;
}

bool Decoder::load(std::string& error) {
  ctx_ = heif_context_alloc();
  if (!ctx_) {
    error = "Out of memory";
    return false;
  }
  applyLimits(ctx_);

  // data_ outlives ctx_, so libheif can read from it without a second copy.
  heif_error err =
      heif_context_read_from_memory_without_copy(ctx_, data_.data(), data_.size(), nullptr);
  if (err.code != heif_error_Ok) {
    error = errorText(err, "Not a readable HEIF file");
    unload();
    return false;
  }

  const int topLevel = heif_context_get_number_of_top_level_images(ctx_);
  if (topLevel > 0) {
    imageIds_.resize(static_cast<size_t>(topLevel));
    const int got =
        heif_context_get_list_of_top_level_image_IDs(ctx_, imageIds_.data(), topLevel);
    imageIds_.resize(static_cast<size_t>(std::max(got, 0)));

    // The primary image goes first, so index 0 is always the one to show.
    heif_item_id primary = 0;
    if (heif_context_get_primary_image_ID(ctx_, &primary).code == heif_error_Ok) {
      auto it = std::find(imageIds_.begin(), imageIds_.end(), primary);
      if (it != imageIds_.end()) {
        std::rotate(imageIds_.begin(), it, it + 1);
      }
    }
  }

  sequenceWidth_ = 0;
  sequenceHeight_ = 0;
  if (heif_context_has_sequence(ctx_)) {
    track_ = heif_context_get_track(ctx_, 0);  // 0 = first visual track
    if (track_) {
      uint16_t w = 0, h = 0;
      if (heif_track_get_image_resolution(track_, &w, &h).code == heif_error_Ok) {
        sequenceWidth_ = w;
        sequenceHeight_ = h;
      }
    }
  }

  if (imageIds_.empty() && track_ == nullptr) {
    error = "The file contains no image";
    unload();
    return false;
  }

  buildDescription();
  return true;
}

void Decoder::buildDescription() {
  std::string out;
  const int headerLen = static_cast<int>(std::min<size_t>(data_.size(), 4096));

  const char* mime = heif_get_file_mime_type(data_.data(), headerLen);
  addLine(out, "mime", (mime && mime[0]) ? mime : "unknown");

  heif_brand2* brands = nullptr;
  int brandCount = 0;
  if (heif_list_compatible_brands(data_.data(), headerLen, &brands, &brandCount).code ==
          heif_error_Ok &&
      brands) {
    std::string list = fourcc(heif_read_main_brand(data_.data(), headerLen));
    for (int i = 0; i < brandCount && i < 32; ++i) {
      const std::string b = fourcc(brands[i]);
      if (list.find(b) == std::string::npos) {
        list += ',';
        list += b;
      }
    }
    heif_free_list_of_compatible_brands(brands);
    addLine(out, "brands", list);
  }

  addLine(out, "bytes", static_cast<long long>(data_.size()));
  addLine(out, "still_images", imageCount());

  if (!imageIds_.empty()) {
    heif_image_handle* handle = nullptr;
    if (heif_context_get_image_handle(ctx_, imageIds_[0], &handle).code == heif_error_Ok &&
        handle) {
      addLine(out, "width", heif_image_handle_get_width(handle));
      addLine(out, "height", heif_image_handle_get_height(handle));
      addLine(out, "alpha", heif_image_handle_has_alpha_channel(handle) ? 1 : 0);
      addLine(out, "premultiplied", heif_image_handle_is_premultiplied_alpha(handle) ? 1 : 0);
      addLine(out, "luma_bits", heif_image_handle_get_luma_bits_per_pixel(handle));
      // 0 = no filter: the alpha plane and depth maps are counted too.
      addLine(out, "aux_images", heif_image_handle_get_number_of_auxiliary_images(handle, 0));

      const heif_color_profile_type type = heif_image_handle_get_color_profile_type(handle);
      if (type == heif_color_profile_type_nclx) {
        heif_color_profile_nclx* nclx = nullptr;
        if (heif_image_handle_get_nclx_color_profile(handle, &nclx).code == heif_error_Ok &&
            nclx) {
          char buf[64];
          snprintf(buf, sizeof(buf), "nclx:%d/%d/%d/%s", static_cast<int>(nclx->color_primaries),
                   static_cast<int>(nclx->transfer_characteristics),
                   static_cast<int>(nclx->matrix_coefficients),
                   nclx->full_range_flag ? "full" : "limited");
          addLine(out, "color", buf);
          heif_nclx_color_profile_free(nclx);
        } else {
          addLine(out, "color", "nclx");
        }
      } else if (type == heif_color_profile_type_rICC || type == heif_color_profile_type_prof) {
        addLine(out, "color",
                "icc:" + std::to_string(heif_image_handle_get_raw_color_profile_size(handle)));
      } else {
        addLine(out, "color", "none");
      }
      heif_image_handle_release(handle);
    }
  }

  if (track_) {
    addLine(out, "sequence", 1);
    addLine(out, "seq_width", sequenceWidth_);
    addLine(out, "seq_height", sequenceHeight_);
    addLine(out, "seq_alpha", heif_track_has_alpha_channel(track_) ? 1 : 0);
    // A file that loops forever stores an all-ones "unknown" duration.
    const uint64_t durationTicks = heif_context_get_sequence_duration(ctx_);
    const bool durationKnown = durationTicks < 0xFFFFFFFFull;
    addLine(out, "seq_duration_ms",
            durationKnown ? ticksToMs(durationTicks, heif_context_get_sequence_timescale(ctx_)) : 0);
    addLine(out, "seq_repeat", heif_track_get_number_of_repetitions(track_));
    addLine(out, "seq_handler", fourcc(heif_track_get_track_handler_type(track_)));
  } else {
    addLine(out, "sequence", 0);
  }

  description_ = std::move(out);
}

bool Decoder::adoptPrepared(heif_image* image, bool hasAlpha, uint32_t durationMs, int maxWidth,
                            int maxHeight, std::string& error) {
  releasePrepared();
  prepared_ = image;
  preparedHasAlpha_ = hasAlpha;
  preparedIsPremultiplied_ = hasAlpha && heif_image_is_premultiplied_alpha(image) != 0;
  preparedDurationMs_ = durationMs;
  preparedWidth_ = heif_image_get_width(image, heif_channel_interleaved);
  preparedHeight_ = heif_image_get_height(image, heif_channel_interleaved);
  // HEVC codes whole blocks, so a decoded frame can be a few pixels larger
  // than the picture. The extra columns and rows are at the right and bottom.
  if (maxWidth > 0 && preparedWidth_ > maxWidth) preparedWidth_ = maxWidth;
  if (maxHeight > 0 && preparedHeight_ > maxHeight) preparedHeight_ = maxHeight;
  if (preparedWidth_ <= 0 || preparedHeight_ <= 0 ||
      static_cast<uint64_t>(preparedWidth_) * static_cast<uint64_t>(preparedHeight_) >
          kMaxImagePixels) {
    error = "The decoded image has an unusable size";
    releasePrepared();
    return false;
  }
  return true;
}

bool Decoder::prepareStill(int index, std::string& error) {
  if (!ctx_ || index < 0 || index >= imageCount()) {
    error = "There is no still image at that position";
    return false;
  }
  heif_image_handle* handle = nullptr;
  heif_error err = heif_context_get_image_handle(ctx_, imageIds_[index], &handle);
  if (err.code != heif_error_Ok || !handle) {
    error = errorText(err, "Cannot open the image");
    return false;
  }
  const bool hasAlpha = heif_image_handle_has_alpha_channel(handle) != 0;

  heif_decoding_options* options = makeOptions();
  heif_image* image = nullptr;
  err = heif_decode_image(handle, &image, heif_colorspace_RGB,
                          hasAlpha ? heif_chroma_interleaved_RGBA : heif_chroma_interleaved_RGB,
                          options);
  // The message belongs to the handle, so copy it before releasing that.
  const std::string message = errorText(err, "Cannot decode the image");
  heif_decoding_options_free(options);
  heif_image_handle_release(handle);

  if (err.code != heif_error_Ok || !image) {
    error = message;
    if (image) heif_image_release(image);
    return false;
  }
  return adoptPrepared(image, hasAlpha, 0, 0, 0, error);
}

int Decoder::prepareNextFrame(std::string& error) {
  if (!track_) {
    error = "The file has no animation";
    return -1;
  }
  if (framesDecoded_ >= kMaxSequenceFrames) {
    return 0;
  }
  const bool hasAlpha = heif_track_has_alpha_channel(track_) != 0;

  heif_decoding_options* options = makeOptions();
  heif_image* image = nullptr;
  heif_error err = heif_track_decode_next_image(
      track_, &image, heif_colorspace_RGB,
      hasAlpha ? heif_chroma_interleaved_RGBA : heif_chroma_interleaved_RGB, options);
  const std::string message = errorText(err, "Cannot decode the frame");
  heif_decoding_options_free(options);

  if (err.code == heif_error_End_of_sequence) {
    if (image) heif_image_release(image);
    return 0;
  }
  if (err.code != heif_error_Ok || !image) {
    error = message;
    if (image) heif_image_release(image);
    return -1;
  }

  uint32_t durationMs = ticksToMs(heif_image_get_duration(image), heif_track_get_timescale(track_));
  if (durationMs == 0) durationMs = 100;  // unspecified: 10 frames per second
  ++framesDecoded_;
  return adoptPrepared(image, hasAlpha, durationMs, sequenceWidth_, sequenceHeight_, error) ? 1 : -1;
}

bool Decoder::rewind(std::string& error) {
  if (!track_) {
    error = "The file has no animation";
    return false;
  }
  // A libheif track only moves forward, and every heif_track handle shares the
  // same position, so starting over means reading the file again.
  unload();
  return load(error);
}

bool Decoder::copyPrepared(uint8_t* dst, size_t dstSize, size_t dstStride, bool premultiplied,
                           std::string& error) const {
  if (!prepared_) {
    error = "Nothing has been decoded yet";
    return false;
  }
  const size_t w = static_cast<size_t>(preparedWidth_);
  const size_t h = static_cast<size_t>(preparedHeight_);
  if (dst == nullptr || dstStride < w * 4 || dstSize < dstStride * (h - 1) + w * 4) {
    error = "The pixel buffer is too small";
    return false;
  }

  int srcStride = 0;
  const uint8_t* src = heif_image_get_plane_readonly(prepared_, heif_channel_interleaved, &srcStride);
  const size_t channels = preparedHasAlpha_ ? 4 : 3;
  if (!src || srcStride <= 0 || static_cast<size_t>(srcStride) < w * channels) {
    error = "The decoded image has no pixel data";
    return false;
  }

  for (size_t y = 0; y < h; ++y) {
    const uint8_t* s = src + y * static_cast<size_t>(srcStride);
    uint8_t* d = dst + y * dstStride;
    if (!preparedHasAlpha_) {
      for (size_t x = 0; x < w; ++x, s += 3, d += 4) {
        d[0] = s[0];
        d[1] = s[1];
        d[2] = s[2];
        d[3] = 255;
      }
    } else if (premultiplied == preparedIsPremultiplied_) {
      std::memcpy(d, s, w * 4);
    } else if (premultiplied) {
      for (size_t x = 0; x < w; ++x, s += 4, d += 4) {
        const uint32_t a = s[3];
        d[0] = mul255(s[0], a);
        d[1] = mul255(s[1], a);
        d[2] = mul255(s[2], a);
        d[3] = static_cast<uint8_t>(a);
      }
    } else {
      for (size_t x = 0; x < w; ++x, s += 4, d += 4) {
        const uint32_t a = s[3];
        if (a == 0) {
          d[0] = d[1] = d[2] = d[3] = 0;
        } else {
          d[0] = static_cast<uint8_t>(std::min<uint32_t>(255, (s[0] * 255u + a / 2) / a));
          d[1] = static_cast<uint8_t>(std::min<uint32_t>(255, (s[1] * 255u + a / 2) / a));
          d[2] = static_cast<uint8_t>(std::min<uint32_t>(255, (s[2] * 255u + a / 2) / a));
          d[3] = static_cast<uint8_t>(a);
        }
      }
    }
  }
  return true;
}

void Decoder::releasePrepared() {
  if (prepared_) {
    heif_image_release(prepared_);
    prepared_ = nullptr;
  }
  preparedWidth_ = 0;
  preparedHeight_ = 0;
  preparedHasAlpha_ = false;
  preparedIsPremultiplied_ = false;
  preparedDurationMs_ = 0;
}

}  // namespace ld
