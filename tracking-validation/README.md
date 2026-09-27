# Native Android hand pipeline regression

CI-only Android APK, not shipped and not a replacement UI. Compiles the production `HandTracker`, `HandInputImage` and camera contracts by source inclusion. Uses a real Android ImageReader/ImageWriter and real arm64 MediaPipe JNI through the Google APIs API35 emulator ARM translation layer (x86_64 host). No fake model, landmarks, camera feed in production, or Robolectric in this test.

Fixture `src/androidTest/assets/hand.jpg`: unmodified `test_image.jpg` from Google AI Edge MediaPipe samples, revision `c2518ec444c3a3a99689e5d31eddadc240c83a0c`, path `examples/hand_landmarker/android/app/src/androidTest/assets/test_image.jpg` (Apache-2.0 repository; see project LICENSE/THIRD_PARTY). SHA-256 `7584b748aa0c57a8cce3acd9e40149f5d4d7317f7db47c8a5a5f4a8fba9090ec`.

Source: https://github.com/google-ai-edge/mediapipe-samples/blob/c2518ec444c3a3a99689e5d31eddadc240c83a0c/examples/hand_landmarker/android/app/src/androidTest/assets/test_image.jpg

Test covers YUV conversion, real strides, all 4 sensor rotations, repeated inference, filtering and lifecycle. It does NOT validate physical camera HAL, HMD optics, real hand gestures, physical arm64 performance or the user's phone. Run `./gradlew :tracking-validation:connectedDebugAndroidTest` with a supported Android device/emulator after `scripts/bootstrap.sh`.

API30 ARM translation aborted in `intrinsics_impl_x86_64.cc:86` (`524288 == 0`) while executing MediaPipe with either input representation. This is recorded as an emulator limitation, not proof of a camera/phone failure or a passing inference test. API35 passed on source `d008b6c`, Actions `36284687600`: 24 positive YUV frames, 4 negative black frames, all four rotations, zero test failures/errors/skips. The production self-test also passed and is asserted not to publish reference landmarks to `latest`.
