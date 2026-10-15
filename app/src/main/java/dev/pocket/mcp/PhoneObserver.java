package dev.pocket.mcp;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import dev.pocket.android.AndroidScreenBackend;
import java.util.concurrent.ConcurrentHashMap;

public final class PhoneObserver extends AccessibilityService {
    static volatile PhoneObserver connected;
    private AndroidScreenBackend backend;
    private final ConcurrentHashMap<Integer,AndroidScreenBackend> virtualBackends=new ConcurrentHashMap<>();
    @Override protected void onServiceConnected() {backend=new AndroidScreenBackend(this);connected=this;}
    AndroidScreenBackend backend() {return backend;}
    AndroidScreenBackend backend(int displayId) {
        if(displayId<=0) throw new IllegalArgumentException("Virtual display id must be positive");
        return virtualBackends.computeIfAbsent(displayId,id->new AndroidScreenBackend(this,id));
    }
    void release(int displayId) {virtualBackends.remove(displayId);}
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {virtualBackends.clear();if(connected==this) connected=null;super.onDestroy();}
}
