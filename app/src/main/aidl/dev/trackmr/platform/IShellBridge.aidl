package dev.trackmr.platform;
interface IShellBridge {
    int uid();
    boolean handPointer(int displayId, int action, float u, float v, float scroll);
    String launchOnDisplay(String component, int displayId);
    String resizeDisplay(int displayId, int width, int height);
    String tap(int displayId, int x, int y);
    String swipe(int displayId, int x, int y, int endX, int endY);
    String back(int displayId);
    String text(int displayId, String value);
    void destroy();
}
