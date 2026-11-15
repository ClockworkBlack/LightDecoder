// Command-line probe used to verify the decoder on the build machine.
//
//   ldprobe <file.heic>                 print what the file contains
//   ldprobe <file.heic> <out_prefix>    also write decoded frames as PPM/PAM
//
// PAM is used when the image has alpha (it stores RGBA), PPM when it does not.
// Both are trivially comparable against a reference without any image library.

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "ld_core.h"

namespace {

std::vector<uint8_t> readFile(const char* path, std::string& error) {
  FILE* f = std::fopen(path, "rb");
  if (!f) {
    error = "cannot open file";
    return {};
  }
  std::fseek(f, 0, SEEK_END);
  long size = std::ftell(f);
  std::fseek(f, 0, SEEK_SET);
  std::vector<uint8_t> buf;
  if (size > 0) {
    buf.resize(static_cast<size_t>(size));
    if (std::fread(buf.data(), 1, buf.size(), f) != buf.size()) {
      error = "short read";
      buf.clear();
    }
  }
  std::fclose(f);
  return buf;
}

bool writeFrame(const std::string& path, const ld::Decoder& dec, std::string& error) {
  const int w = dec.preparedWidth();
  const int h = dec.preparedHeight();
  const bool alpha = dec.preparedHasAlpha();
  const size_t stride = static_cast<size_t>(w) * 4;
  std::vector<uint8_t> rgba(stride * h);
  // Non-premultiplied, so the probe's output is directly comparable to a
  // straight-alpha reference.
  if (!dec.copyPrepared(rgba.data(), rgba.size(), stride, /*premultiplied=*/false, error)) {
    return false;
  }
  FILE* f = std::fopen(path.c_str(), "wb");
  if (!f) {
    error = "cannot write output";
    return false;
  }
  if (alpha) {
    std::fprintf(f, "P7\nWIDTH %d\nHEIGHT %d\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n", w, h);
    std::fwrite(rgba.data(), 1, rgba.size(), f);
  } else {
    std::fprintf(f, "P6\n%d %d\n255\n", w, h);
    for (int i = 0; i < w * h; ++i) {
      std::fwrite(&rgba[i * 4], 1, 3, f);
    }
  }
  std::fclose(f);
  return true;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 2) {
    std::fprintf(stderr, "usage: %s <file.heic> [out_prefix]\n", argv[0]);
    return 2;
  }
  std::fprintf(stderr, "%s\n", ld::versions().c_str());

  std::string error;
  std::vector<uint8_t> buf = readFile(argv[1], error);
  if (buf.empty()) {
    std::fprintf(stderr, "read error: %s\n", error.c_str());
    return 1;
  }

  auto dec = ld::Decoder::open(buf.data(), buf.size(), error);
  if (!dec) {
    std::fprintf(stderr, "open error: %s\n", error.c_str());
    return 1;
  }
  std::fputs(dec->describe().c_str(), stdout);

  const char* prefix = argc >= 3 ? argv[2] : nullptr;

  if (dec->hasSequence()) {
    int idx = 0;
    while (true) {
      int r = dec->prepareNextFrame(error);
      if (r < 0) {
        std::fprintf(stderr, "frame error: %s\n", error.c_str());
        return 1;
      }
      if (r == 0) break;
      std::printf("frame=%d width=%d height=%d alpha=%d duration_ms=%u\n", idx,
                  dec->preparedWidth(), dec->preparedHeight(), dec->preparedHasAlpha() ? 1 : 0,
                  dec->preparedDurationMs());
      if (prefix) {
        char path[1024];
        std::snprintf(path, sizeof(path), "%s_frame%03d.%s", prefix, idx,
                      dec->preparedHasAlpha() ? "pam" : "ppm");
        if (!writeFrame(path, *dec, error)) {
          std::fprintf(stderr, "write error: %s\n", error.c_str());
          return 1;
        }
      }
      ++idx;
    }
    std::printf("total_frames=%d\n", idx);

    // Start over and count again: looping an animation depends on this.
    if (!dec->rewind(error)) {
      std::fprintf(stderr, "rewind error: %s\n", error.c_str());
      return 1;
    }
    int again = 0;
    while (true) {
      int r = dec->prepareNextFrame(error);
      if (r < 0) {
        std::fprintf(stderr, "frame error after rewind: %s\n", error.c_str());
        return 1;
      }
      if (r == 0) break;
      ++again;
    }
    std::printf("frames_after_rewind=%d\n", again);
  } else {
    if (!dec->prepareStill(0, error)) {
      std::fprintf(stderr, "still error: %s\n", error.c_str());
      return 1;
    }
    std::printf("still width=%d height=%d alpha=%d\n", dec->preparedWidth(),
                dec->preparedHeight(), dec->preparedHasAlpha() ? 1 : 0);
    if (prefix) {
      char path[1024];
      std::snprintf(path, sizeof(path), "%s.%s", prefix, dec->preparedHasAlpha() ? "pam" : "ppm");
      if (!writeFrame(path, *dec, error)) {
        std::fprintf(stderr, "write error: %s\n", error.c_str());
        return 1;
      }
    }
  }
  return 0;
}
