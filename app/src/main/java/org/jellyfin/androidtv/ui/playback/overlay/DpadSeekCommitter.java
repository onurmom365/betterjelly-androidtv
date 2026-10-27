package org.jellyfin.androidtv.ui.playback.overlay;

/** Gives deliberate taps time to inspect thumbnails, without slowing release after a hold. */
final class DpadSeekCommitter {
    static final long TAP_QUIET_PERIOD_MS = 1500;
    static final long HOLD_QUIET_PERIOD_MS = 250;

    interface Scheduler {
        void postDelayed(Runnable task, long delayMs);
        void removeCallbacks(Runnable task);
    }

    private final Scheduler scheduler;
    private Runnable pendingFinish;
    private final Runnable finishTask = this::flush;
    private boolean held;

    DpadSeekCommitter(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    void cancel() {
        scheduler.removeCallbacks(finishTask);
        pendingFinish = null;
    }

    void onKeyDown(int repeatCount) {
        cancel();
        held = repeatCount > 0;
    }

    void finishAfterPause(Runnable finish) {
        cancel();
        pendingFinish = finish;
        scheduler.postDelayed(finishTask, held ? HOLD_QUIET_PERIOD_MS : TAP_QUIET_PERIOD_MS);
    }

    void flush() {
        Runnable finish = pendingFinish;
        cancel();
        if (finish != null) finish.run();
    }
}
