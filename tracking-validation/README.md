# Native Android hand pipeline regression

CI-only Android APK, not shipped and not a replacement UI. Compiles the production `HandTracker`, `HandInputImage` and camera contracts by source inclusion. Uses a real Android ImageReader/ImageWriter and real arm64 MediaPipe JNI through the Google APIs API35 emulator ARM translation layer (x86_64 host). No fake model, landmarks, camera feed in production, or Robolectric in this test.

Fixture `src/androidTest/assets/hand.jpg`: unmodified `test_image.jpg` from Google AI Edge MediaPipe samples, revision `c2518ec444c3a3a99689e5d31eddadc240c83a0c`, path `examples/hand_landmarker/android/app/src/androidTest/assets/test_image.jpg` (Apache-2.0 repository; see project LICENSE/THIRD_PARTY). SHA-256 `7584b748aa0c57a8cce3acd9e40149f5d4d7317f7db47c8a5a5f4a8fba9090ec`.

Source: https://github.com/google-ai-edge/mediapipe-samples/blob/c2518ec444c3a3a99689e5d31eddadc240c83a0c/examples/hand_landmarker/android/app/src/androidTest/assets/test_image.jpg

Test covers YUV conversion, real strides, all 4 sensor rotations, repeated inference, filtering and lifecycle. It does NOT validate physical camera HAL, HMD optics, real hand gestures, physical arm64 performance or the user's phone. Run `./gradlew :tracking-validation:connectedDebugAndroidTest` with a supported Android device/emulator after `scripts/bootstrap.sh`.

API30 ARM translation aborted in `intrinsics_impl_x86_64.cc:86` (`524288 == 0`) while executing MediaPipe with either input representation. This is recorded as an emulator limitation, not proof of a camera/phone failure or a passing inference test. API35 passed on source `d008b6c`, Actions `36284687600`: 24 positive YUV frames, 4 negative black frames, all four rotations, zero test failures/errors/skips. The production self-test also passed and is asserted not to publish reference landmarks to `latest`.

## Alpha07: delivery policy and GLES pixels

The native test logs actual processing/source age and checks the production delivery policy. The translated emulator can take over 1.5 seconds for a result: that result must stay EXPIRED, not become actionable to make CI green. Separate JVM tests exercise continuous pinch consumption at 100/150/200/280 ms inference.

An Android EGL pbuffer draws real filtered landmarks using `render/include/trackmr/hand_overlay.hpp`, also used by the shipped renderer. `glReadPixels` requires green/amber skeleton pixels and none for a missing hand. This tests actual GL execution, not just shader compilation, but **not VrActivity, full Cardboard distortion/projection, or physical hand-to-window interaction**. Native probe code is only packaged in the validation APK, not the user app. See [alpha07 notes](../docs/ALPHA07-HANDS.md) for CI outcome and limitations.

Alpha07 source `6069ee7`, Actions `36286888783`: both jobs passed. Instrumentation 64.224 s, 0 failures/errors/skips; real green/amber GLES pixels and all 28 YUV frames checked. All 24 positive emulated processing times were 1,930–2,010 ms and therefore EXPIRED: **the GL geometry probe is separate from the freshness gate, not proof of end-to-end realtime interaction**.
