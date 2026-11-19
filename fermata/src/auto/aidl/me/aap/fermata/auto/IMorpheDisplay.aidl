package me.aap.fermata.auto;

import android.view.Surface;
import android.view.MotionEvent;

/**
 * Runs in the Shizuku user service process, with the shell's permissions: creates a virtual
 * display, starts apps on it and injects input events into it. See MorpheDisplayService.
 */
interface IMorpheDisplay {
    // Called by Shizuku to stop the service (transaction code 16777115)
    void destroy() = 16777114;

    // Creates the display rendering to the surface, or moves the existing one to it. Returns its id.
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;

    void releaseDisplay() = 2;

    // Runs "am start --display <id>" with the given arguments. Returns null or the error.
    String startActivity(in String[] args) = 3;

    oneway void injectMotionEvent(in MotionEvent event) = 4;

    oneway void injectKeyEvent(int action, int keyCode) = 5;
}
