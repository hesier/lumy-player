package com.kuaishou.akdanmaku;

import android.os.Message;
import com.kuaishou.akdanmaku.data.DanmakuItem;
import com.kuaishou.akdanmaku.data.ItemState;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/** Debug probe cache backpressure; each manager has an independent budget. */
public final class LumyCacheBudget {
    private static final int BATCH_LIMIT = 8;
    private static final int QUEUE_LIMIT = 64;
    private static final Map<Object, State> STATES = new WeakHashMap<>();

    private LumyCacheBudget() {}

    private static State state(Object manager) {
        synchronized (STATES) {
            State state = STATES.get(manager);
            if (state == null) {
                state = new State();
                STATES.put(manager, state);
            }
            return state;
        }
    }

    public static void beginBatch(Object manager) {
        State state = state(manager);
        synchronized (state) {
            state.admitted = 0;
            state.rejectionSignaled = false;
        }
    }

    public static boolean admit(Object manager, DanmakuItem item, int kind) {
        State state = state(manager);
        Runnable ready = null;
        synchronized (state) {
            if (!state.closed && state.admitted < BATCH_LIMIT && state.jobs.size() < QUEUE_LIMIT) {
                state.jobs.addLast(new Job(item, kind));
                state.admitted++;
                state.peak = Math.max(state.peak, state.jobs.size());
                return true;
            }
            state.rejected++;
            restore(item, kind);
            if (!state.closed && !state.rejectionSignaled) {
                state.rejectionSignaled = true;
                ready = state.ready;
            }
        }
        notifyReady(ready);
        return false;
    }

    public static void taskStarted(Object manager, Message message) {
        if (message.what != 0 && message.what != 1) return;
        State state = state(manager);
        synchronized (state) {
            // CacheHandler is serial; match the oldest admitted task of this kind.
            Iterator<Job> jobs = state.jobs.iterator();
            while (jobs.hasNext()) {
                Job job = jobs.next();
                if (job.kind != message.what) continue;
                jobs.remove();
                state.active = job;
                state.startedNanos = System.nanoTime();
                break;
            }
        }
    }

    public static void taskFinished(Object manager) {
        State state = state(manager);
        Runnable ready = null;
        synchronized (state) {
            Job job = state.active;
            if (job == null) return;
            state.active = null;
            state.completed++;
            state.totalNanos += Math.max(0L, System.nanoTime() - state.startedNanos);
            ItemState actual = job.item.getState();
            if (actual == (job.kind == 0 ? ItemState.Measured : ItemState.Rendered)) {
                state.readyDirty = true;
            }
            restore(job.item, job.kind);
            if (!state.closed && state.jobs.isEmpty() && state.readyDirty) {
                state.readyDirty = false;
                ready = state.ready;
            }
        }
        notifyReady(ready);
    }

    public static void cancel(Object manager) {
        State state = state(manager);
        synchronized (state) {
            cancelQueued(state);
        }
    }

    public static void close(Object manager) {
        State state = state(manager);
        synchronized (state) {
            state.closed = true;
            state.ready = null;
            cancelQueued(state);
        }
    }

    public static void onReady(Object manager, Runnable callback) {
        State state = state(manager);
        synchronized (state) {
            if (!state.closed) state.ready = callback;
        }
    }

    /** [queued, peak queued, rejected, completed, worker elapsed nanos]. */
    public static long[] snapshot(Object manager) {
        State state = state(manager);
        synchronized (state) {
            return new long[] {state.jobs.size(), state.peak, state.rejected,
                    state.completed, state.totalNanos};
        }
    }

    private static void cancelQueued(State state) {
        for (Job job : state.jobs) restore(job.item, job.kind);
        state.jobs.clear();
        state.readyDirty = false;
    }

    private static void restore(DanmakuItem item, int kind) {
        ItemState transientState = kind == 0 ? ItemState.Measuring : ItemState.Rendering;
        if (item.getState() == transientState) {
            item.setState(kind == 0 ? ItemState.Uninitialized : ItemState.Measured);
        }
    }

    private static void notifyReady(Runnable ready) {
        if (ready != null) ready.run();
    }

    private static final class Job {
        final DanmakuItem item;
        final int kind;

        Job(DanmakuItem item, int kind) {
            this.item = item;
            this.kind = kind;
        }
    }

    private static final class State {
        final ArrayDeque<Job> jobs = new ArrayDeque<>();
        Job active;
        Runnable ready;
        int admitted;
        long peak;
        long rejected;
        long completed;
        long totalNanos;
        long startedNanos;
        boolean closed;
        boolean readyDirty;
        boolean rejectionSignaled;
    }
}
