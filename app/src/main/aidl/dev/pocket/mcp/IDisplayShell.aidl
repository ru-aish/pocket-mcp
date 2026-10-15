package dev.pocket.mcp;
interface IDisplayShell {
    int createDisplay(int width, int height, int dpi);
    void removeDisplay(int displayId);
    boolean launchActivity(int displayId, String componentName);
    boolean injectKey(int displayId, int keyCode);
    String health();
    void destroy() = 16777114;
}
