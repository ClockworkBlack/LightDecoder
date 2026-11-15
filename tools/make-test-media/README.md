# Test-media generator

Generates the synthetic HEIC files the unit tests decode
(`app/src/test/resources/media/`). The files are committed, so you only need
this if you want to change what the tests decode.

The pictures are deliberately simple — a filled circle on a transparent
background — so the tests know what every decoded pixel should be. They copy how
an iPhone stores a sticker: transparency as a separate monochrome HEVC image, a
picture size that is not a multiple of the coding block size, and (for
`still_outline.heic`) the same `stickerEffect:type="stroke"` XMP note an iPhone
writes for the Outline effect.

## Building it

It needs an HEVC *encoder*, which the app itself does not bundle (the app only
decodes). Install [kvazaar](https://github.com/ultravideo/kvazaar) where CMake
can find it, then:

```sh
cmake -S app/src/main/cpp -B build/media-tool \
  -DLD_HOST_BUILD=ON -DLD_TEST_MEDIA_TOOL=ON \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH=/path/to/kvazaar/prefix
cmake --build build/media-tool --target make_test_media
./build/media-tool/make_test_media app/src/test/resources/media
```
