package org.jellyfin.androidtv.ui.playback;

import android.view.KeyEvent;

/** Consumes the whole Back press that keeps a segment playing, including its release. */
final class SegmentCountdownBackKeyHandler {
    enum Result { PASS, CONSUMED, CANCEL }
    private long cancelDownTime = Long.MIN_VALUE;
    private int cancelKey;

    Result onKey(int key, int action, int repeats, long downTime, boolean countdownVisible) {
        if (key != KeyEvent.KEYCODE_BACK && key != KeyEvent.KEYCODE_BUTTON_B && key != KeyEvent.KEYCODE_ESCAPE) return Result.PASS;
        if (downTime == cancelDownTime && key == cancelKey) return Result.CONSUMED;
        if (action != KeyEvent.ACTION_DOWN || repeats != 0 || !countdownVisible) return Result.PASS;
        cancelDownTime = downTime;
        cancelKey = key;
        return Result.CANCEL;
    }

    void reset() {
        cancelDownTime = Long.MIN_VALUE;
    }
}
