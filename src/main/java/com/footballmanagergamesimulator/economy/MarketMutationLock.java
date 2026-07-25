package com.footballmanagergamesimulator.economy;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Single in-process mutation lane for the finite market supply and price state.
 *
 * <p>The game is single-node and market writes are comparatively rare. A fair,
 * reentrant lock keeps daily pricing, bootstrap/cap-table reconciliation, trading,
 * takeovers and treasury ownership checks from loading the same versioned
 * {@link MarketInstrument} concurrently. Database row locks remain the authority;
 * this lane prevents stale first-level-cache instances before those locks are taken.
 */
@Component
public final class MarketMutationLock {
    private final ReentrantLock lock = new ReentrantLock(true);

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }
}
