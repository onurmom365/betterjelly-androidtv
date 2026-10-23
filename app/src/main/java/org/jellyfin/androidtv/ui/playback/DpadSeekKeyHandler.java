package org.jellyfin.androidtv.ui.playback;

import android.view.KeyEvent;

/** Routes each D-pad seek gesture to a preview followed by one automatic commit. */
final class DpadSeekKeyHandler {
    enum Result { PASS, CONSUMED, REWIND, FORWARD, FINISH }

    private static final long NO_PRESS = Long.MIN_VALUE;
    private final long[] downTimes = {NO_PRESS, NO_PRESS};
    private final int[] repeatCounts = {0, 0};

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
            return Result.FINISH;
        }
        if (action != KeyEvent.ACTION_DOWN) return Result.PASS;
        if (tracked) {
            if (!allowSeek || repeatCount <= repeatCounts[direction]) return Result.CONSUMED;
            repeatCounts[direction] = repeatCount;
            return direction == 0 ? Result.REWIND : Result.FORWARD;
        }
        // Never take over a hold that began while navigating the visible controls.
        if (repeatCount != 0 || !allowSeek) return Result.PASS;

        downTimes[direction] = downTime;
        repeatCounts[direction] = 0;
        return direction == 0 ? Result.REWIND : Result.FORWARD;
    }

    void reset() {
        downTimes[0] = NO_PRESS;
        downTimes[1] = NO_PRESS;
    }
}
