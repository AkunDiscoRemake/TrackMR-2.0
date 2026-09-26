package dev.trackmr.platform;
interface IShellBridge {
    int uid();
    String launchOnDisplay(String component, int displayId);
    String resizeDisplay(int displayId, int width, int height);
    void destroy();
}
