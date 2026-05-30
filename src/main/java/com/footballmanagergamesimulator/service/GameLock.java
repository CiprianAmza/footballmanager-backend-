package com.footballmanagergamesimulator.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Single process-wide lock serializing the calendar advance / season transition
 * against user squad-mutating endpoints (e.g. youth promotion). Both touch the
 * same TEAM rows; without sharing one monitor across the two Tomcat request
 * threads, H2's MVCC row locks raced and threw lock-timeout during the
 * off-season transition. Single-user game — simplest correct serialization.
 */
@Component
public class GameLock {

    private final ReentrantLock lock = new ReentrantLock();

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }
}
