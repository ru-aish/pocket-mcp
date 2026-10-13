package dev.pocket.mcp;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import dev.pocket.android.AndroidScreenBackend;

public final class PhoneObserver extends AccessibilityService {
    static volatile PhoneObserver connected;
    private AndroidScreenBackend backend;
    @Override protected void onServiceConnected() {backend=new AndroidScreenBackend(this);connected=this;}
    AndroidScreenBackend backend() {return backend;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
    @Override public void onDestroy() {if(connected==this) connected=null;super.onDestroy();}
}
