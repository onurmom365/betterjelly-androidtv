package org.jellyfin.androidtv.ui.playback.overlay;

import java.util.Arrays;

/** One stable thumbnail grid for fine taps, larger held steps, and direction changes. */
final class DpadSeekNavigator {
    static final long TAP_STEP_MS = 5000;
    private final long backwardStep;
    private final long forwardStep;
    private final long gridStep;
    private final long[] positions;

    DpadSeekNavigator(long anchor, long duration, long backwardStep, long forwardStep) {
        duration = Math.max(0, duration);
        anchor = Math.max(0, Math.min(anchor, duration));
        this.backwardStep = Math.max(1000, backwardStep / 1000 * 1000);
        this.forwardStep = Math.max(1000, forwardStep / 1000 * 1000);
        gridStep = gcd(TAP_STEP_MS, gcd(this.backwardStep, this.forwardStep));
        int before = (int) ((anchor + gridStep - 1) / gridStep);
        int after = (int) ((duration - anchor + gridStep - 1) / gridStep);
        positions = new long[before + after + 1];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = Math.max(0, Math.min(duration, anchor + (long) (i - before) * gridStep));
        }
    }

    long[] getPositions() {
        return positions;
    }

    // Leanback performs one native step from this position. Jump to the neighbor of the
    // desired target so a held 30s step still makes only one thumbnail/UI update.
    long positionBeforeStep(long current, boolean forward, boolean held) {
        int found = Arrays.binarySearch(positions, current);
        int index = found >= 0 ? found : Math.max(0, Math.min(positions.length - 1, -found - (forward ? 2 : 1)));
        long step = held ? (forward ? forwardStep : backwardStep) : TAP_STEP_MS;
        long target = index + (forward ? 1 : -1) * (step / gridStep);
        int targetIndex = (int) Math.max(0, Math.min(positions.length - 1, target));
        int neighbor = forward ? Math.max(0, targetIndex - 1) : Math.min(positions.length - 1, targetIndex + 1);
        return positions[neighbor];
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }
}
