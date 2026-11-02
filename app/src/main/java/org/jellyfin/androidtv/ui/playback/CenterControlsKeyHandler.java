package org.jellyfin.androidtv.ui.playback;

import android.view.KeyEvent;

/** Owns the complete press that reveals controls so its release cannot activate a button. */
final class CenterControlsKeyHandler {
    static final long IDLE_TIMEOUT_MS = 3000;
    enum Result { PASS, CONSUMED, REVEAL }

    private long revealDownTime = Long.MIN_VALUE;
    private int revealKey;

    Result onKey(int key, int action, int repeats, long downTime, boolean allowReveal) {
        if (key != KeyEvent.KEYCODE_DPAD_CENTER && key != KeyEvent.KEYCODE_ENTER) return Result.PASS;
        if (downTime == revealDownTime && key == revealKey) return Result.CONSUMED;
        if (action != KeyEvent.ACTION_DOWN || repeats != 0 || !allowReveal) return Result.PASS;
        revealDownTime = downTime;
        revealKey = key;
        return Result.REVEAL;
    }

    void reset() {
        revealDownTime = Long.MIN_VALUE;
    }
}
