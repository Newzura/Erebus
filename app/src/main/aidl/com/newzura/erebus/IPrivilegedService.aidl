package com.newzura.erebus;

import android.view.MotionEvent;

interface IPrivilegedService {
    int applyDisplayOverride(int w, int h, int dpi);
    int[] displayGeometry();
    int setDisplayPowerMode(int mode);
    boolean injectMotionEvent(in MotionEvent event);
    boolean injectKeyCode(int keyCode);
    int displaySizeStatus();
    int displayPowerModeStatus();
    boolean isTouchInjectionSupported();
    void attachClient(IBinder client);
    void destroy();
}
