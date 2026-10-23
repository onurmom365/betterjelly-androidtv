package org.jellyfin.androidtv.ui.playback;

import android.view.KeyEvent;

/** Keeps a hidden-controls seek press from also reaching Leanback on repeat or release. */
final class DpadSeekKeyHandler {
    enum Result { PASS, CONSUMED, REWIND, FORWARD }

    private static final long NO_PRESS = Long.MIN_VALUE;
    private final long[] downTimes = {NO_PRESS, NO_PRESS};

    Result onKey(int keyCode, int action, int repeatCount, long downTime, boolean allowSeek) {
        final int direction;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            direction = 0;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            direction = 1;
        } else {
            return Result.PASS;
        }

        boolean tracked = downTimes[direction] == downTime;
        if (action == KeyEvent.ACTION_UP) {
            if (!tracked) return Result.PASS;
            downTimes[direction] = NO_PRESS;
            return Result.CONSUMED;
        }
        if (action != KeyEvent.ACTION_DOWN) return Result.PASS;
        if (tracked) return Result.CONSUMED;
        // Never take over a hold that began while navigating the visible controls.
        if (repeatCount != 0 || !allowSeek) return Result.PASS;

        downTimes[direction] = downTime;
        return direction == 0 ? Result.REWIND : Result.FORWARD;
    }

    void reset() {
        downTimes[0] = NO_PRESS;
        downTimes[1] = NO_PRESS;
    }
}
