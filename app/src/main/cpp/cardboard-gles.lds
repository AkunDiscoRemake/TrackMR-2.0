/* Cardboard C API and QR JNI, without disabled Unity/Vulkan entry points.
 * NDK 27 rejects nonexistent literal symbols in upstream's all-backends export list.
 */
VERS_1.0 {
  global:
    Cardboard*;
    *nativeIncrementDeviceParamsChangedCount;
    *JNIEnv*;
    *JavaVM*;
  local:
    *;
};
