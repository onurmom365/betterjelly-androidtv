package org.jellyfin.androidtv.ui.playback.overlay;

/** Keeps quick taps in one native seek session, with no confirmation button required. */
final class DpadSeekCommitter {
    static final long QUIET_PERIOD_MS = 250;

    interface Scheduler {
        void postDelayed(Runnable task, long delayMs);
        void removeCallbacks(Runnable task);
    }

    private final Scheduler scheduler;
    private Runnable pendingFinish;
    private final Runnable finishTask = this::flush;

    DpadSeekCommitter(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    void cancel() {
        scheduler.removeCallbacks(finishTask);
        pendingFinish = null;
    }

    void finishAfterPause(Runnable finish) {
        cancel();
        pendingFinish = finish;
        scheduler.postDelayed(finishTask, QUIET_PERIOD_MS);
    }

    void flush() {
        Runnable finish = pendingFinish;
        cancel();
        if (finish != null) finish.run();
    }
}
