// Generates the small HEIC files the unit tests decode.
//
// The pictures are synthetic, so the tests know what every pixel should be:
// a filled circle on a transparent background. In frame i of an animation the
// circle is centred at (kFirstX + i * kStepX, kCentreY) and coloured
// (255 - 20 i, 60 + 20 i, 40).
//
// The files copy how an iPhone stores a sticker: the transparency is a
// separate monochrome HEVC image, the picture size is not a multiple of the
// coding block size, and the "outline" file carries the same XMP note an
// iPhone writes when the sender picks the Outline effect.
//
// Needs an HEVC encoder, so it is built only on request:
//   see tools/make-test-media/README.md

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

#include "libheif/heif.h"
#include "libheif/heif_sequences.h"

namespace {

const int kWidth = 150;
const int kHeight = 110;
const int kRadius = 25;
const int kFirstX = 30;
const int kStepX = 12;
const int kCentreY = 55;
const int kFrames = 8;
const uint32_t kTimescale = 1000;
const uint32_t kFrameTicks = 80;  // 80 ms

const char kOutlineXmp[] =
    "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"XMP Core 6.0.0\"> "
    "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"> "
    "<rdf:Description rdf:about=\"\" xmlns:stickerEffect=\"http://ns.apple.com/Stickers/1.0/\" "
    "stickerEffect:type=\"stroke\"/> </rdf:RDF> </x:xmpmeta>";

void check(heif_error err, const char* what) {
  if (err.code != heif_error_Ok) {
    std::fprintf(stderr, "%s failed: %s\n", what, err.message ? err.message : "?");
    std::exit(1);
  }
}

// One frame: the circle for `frameIndex`, with or without transparency.
heif_image* makeFrame(int frameIndex, bool withAlpha) {
  heif_image* image = nullptr;
  check(heif_image_create(kWidth, kHeight, heif_colorspace_RGB,
                          withAlpha ? heif_chroma_interleaved_RGBA : heif_chroma_interleaved_RGB,
                          &image),
        "heif_image_create");
  check(heif_image_add_plane(image, heif_channel_interleaved, kWidth, kHeight, 8),
        "heif_image_add_plane");
  int stride = 0;
  uint8_t* pixels = heif_image_get_plane(image, heif_channel_interleaved, &stride);
  const int channels = withAlpha ? 4 : 3;
  const double cx = kFirstX + frameIndex * kStepX;
  const double cy = kCentreY;
  const uint8_t r = static_cast<uint8_t>(255 - 20 * frameIndex);
  const uint8_t g = static_cast<uint8_t>(60 + 20 * frameIndex);
  const uint8_t b = 40;
  for (int y = 0; y < kHeight; ++y) {
    uint8_t* row = pixels + static_cast<size_t>(y) * stride;
    for (int x = 0; x < kWidth; ++x) {
      const double d = std::sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
      // 1 inside the circle, 0 outside, a one-pixel ramp at the edge.
      double coverage = kRadius + 0.5 - d;
      if (coverage < 0) coverage = 0;
      if (coverage > 1) coverage = 1;
      uint8_t* p = row + x * channels;
      if (withAlpha) {
        p[0] = coverage > 0 ? r : 0;
        p[1] = coverage > 0 ? g : 0;
        p[2] = coverage > 0 ? b : 0;
        p[3] = static_cast<uint8_t>(coverage * 255 + 0.5);
      } else {
        // Opaque files put the circle on white.
        p[0] = static_cast<uint8_t>(r * coverage + 255 * (1 - coverage) + 0.5);
        p[1] = static_cast<uint8_t>(g * coverage + 255 * (1 - coverage) + 0.5);
        p[2] = static_cast<uint8_t>(b * coverage + 255 * (1 - coverage) + 0.5);
      }
    }
  }
  return image;
}

heif_encoder* makeEncoder(heif_context* ctx) {
  heif_encoder* encoder = nullptr;
  check(heif_context_get_encoder_for_format(ctx, heif_compression_HEVC, &encoder),
        "heif_context_get_encoder_for_format (is an HEVC encoder built in?)");
  check(heif_encoder_set_lossy_quality(encoder, 90), "heif_encoder_set_lossy_quality");
  return encoder;
}

void addStill(heif_context* ctx, heif_encoder* encoder, int frameIndex, bool withAlpha,
              const char* xmp) {
  heif_image* image = makeFrame(frameIndex, withAlpha);
  heif_encoding_options* options = heif_encoding_options_alloc();
  options->save_alpha_channel = withAlpha ? 1 : 0;
  heif_image_handle* handle = nullptr;
  check(heif_context_encode_image(ctx, image, encoder, options, &handle), "heif_context_encode_image");
  if (xmp) {
    check(heif_context_add_XMP_metadata(ctx, handle, xmp, static_cast<int>(std::strlen(xmp))),
          "heif_context_add_XMP_metadata");
  }
  heif_image_handle_release(handle);
  heif_encoding_options_free(options);
  heif_image_release(image);
}

void writeStill(const std::string& path, bool withAlpha, const char* xmp) {
  heif_context* ctx = heif_context_alloc();
  heif_encoder* encoder = makeEncoder(ctx);
  addStill(ctx, encoder, 0, withAlpha, xmp);
  heif_encoder_release(encoder);
  check(heif_context_write_to_file(ctx, path.c_str()), "heif_context_write_to_file");
  heif_context_free(ctx);
  std::printf("wrote %s\n", path.c_str());
}

void writeSequence(const std::string& path, bool withAlpha, heif_sequence_gop_structure gop,
                   uint32_t repetitions) {
  heif_context* ctx = heif_context_alloc();
  heif_encoder* encoder = makeEncoder(ctx);

  heif_track_options* trackOptions = heif_track_options_alloc();
  heif_track_options_set_timescale(trackOptions, kTimescale);
  heif_context_set_sequence_timescale(ctx, kTimescale);
  heif_context_set_number_of_sequence_repetitions(ctx, repetitions);

  heif_sequence_encoding_options* options = heif_sequence_encoding_options_alloc();
  options->gop_structure = gop;
  options->save_alpha_channel = withAlpha ? 1 : 0;

  heif_track* track = nullptr;
  check(heif_context_add_visual_sequence_track(ctx, kWidth, kHeight, heif_track_type_image_sequence,
                                               trackOptions, options, &track),
        "heif_context_add_visual_sequence_track");
  heif_track_options_release(trackOptions);

  for (int i = 0; i < kFrames; ++i) {
    heif_image* frame = makeFrame(i, withAlpha);
    heif_image_set_duration(frame, kFrameTicks);
    check(heif_track_encode_sequence_image(track, frame, encoder, options),
          "heif_track_encode_sequence_image");
    heif_image_release(frame);
  }
  check(heif_track_encode_end_of_sequence(track, encoder), "heif_track_encode_end_of_sequence");
  heif_track_release(track);
  heif_sequence_encoding_options_release(options);

  // Like an iPhone's animated sticker, the file also holds the first frame as
  // a plain still, for programs that cannot play the animation.
  addStill(ctx, encoder, 0, withAlpha, nullptr);

  // The encoder has to outlive the write: libheif finishes the tracks then.
  check(heif_context_write_to_file(ctx, path.c_str()), "heif_context_write_to_file");
  heif_encoder_release(encoder);
  heif_context_free(ctx);
  std::printf("wrote %s\n", path.c_str());
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 2) {
    std::fprintf(stderr, "usage: %s <output directory>\n", argv[0]);
    return 2;
  }
  const std::string dir = std::string(argv[1]) + "/";
  heif_init(nullptr);

  writeStill(dir + "still_alpha.heic", /*withAlpha=*/true, nullptr);
  writeStill(dir + "still_outline.heic", /*withAlpha=*/true, kOutlineXmp);
  writeStill(dir + "still_opaque.heic", /*withAlpha=*/false, nullptr);
  writeSequence(dir + "anim_alpha.heics", /*withAlpha=*/true, heif_sequence_gop_structure_intra_only,
                heif_sequence_maximum_number_of_repetitions);
  writeSequence(dir + "anim_inter.heics", /*withAlpha=*/false, heif_sequence_gop_structure_lowdelay,
                1);
  return 0;
}
