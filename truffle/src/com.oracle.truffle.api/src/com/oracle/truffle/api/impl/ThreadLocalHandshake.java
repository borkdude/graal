/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.impl;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;

public abstract class ThreadLocalHandshake {

    /*
     * This map contains all state objects for all threads accessible for other threads. Since the
     * thread needs to be weak and synchronized it is less efficient to access and is only used when
     * accessing the state of other threads.
     */
    private static final Map<Thread, TruffleSafepointImpl> SAFEPOINTS = Collections.synchronizedMap(new WeakHashMap<>());

    protected ThreadLocalHandshake() {
    }

    public abstract void poll(Node enclosingNode);

    public abstract TruffleSafepointImpl getCurrent();

    /**
     * If this method is invoked the thread must be guaranteed to be polled. If the thread dies and
     * {@link #poll(Node)} was not invoked then an {@link IllegalStateException} is thrown;
     *
     * @param threads
     * @param run
     */
    @TruffleBoundary
    public final <T extends Consumer<Node>> Future<Void> runThreadLocal(Thread[] threads, T onThread, Consumer<T> onDone, boolean sideEffecting) {
        Handshake<T> handshake = new Handshake<>(onThread, onDone, sideEffecting, threads.length);
        for (int i = 0; i < threads.length; i++) {
            Thread t = threads[i];
            if (!t.isAlive()) {
                throw new IllegalStateException("Thread no longer alive with pending handshake.");
            }
            getThreadState(t).putHandshake(t, handshake);
        }
        return handshake;
    }

    public void ensureThreadInitialized() {
    }

    protected abstract void setFastPending(Thread t);

    @TruffleBoundary
    protected final void processHandshake(Node node) {
        Throwable ex = null;
        TruffleSafepointImpl s = getCurrent();
        HandshakeEntry handshake = null;
        if (s.fastPendingSet) {
            handshake = s.takeHandshake();
        }
        if (handshake != null) {
            ex = combineThrowable(ex, handshake.process(node));
            if (ex != null) {
                throw sneakyThrow(ex);
            }
        }
    }

    protected abstract void clearFastPending();

    private static Throwable combineThrowable(Throwable current, Throwable t) {
        if (current == null) {
            return t;
        }
        if (t instanceof ThreadDeath) {
            t.addSuppressed(current);
            return t;
        } else {
            current.addSuppressed(t);
            return current;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable ex) throws T {
        throw (T) ex;
    }

    static final class Handshake<T extends Consumer<Node>> implements Future<Void> {

        private final boolean sideEffecting;
        private final CountDownLatch remainingThreads;
        private volatile boolean cancelled;
        private final T action;
        private final Consumer<T> onDone;

        @SuppressWarnings("unchecked")
        Handshake(T action, Consumer<T> onDone, boolean sideEffecting, int numberOfThreads) {
            this.action = action;
            this.onDone = onDone;
            this.sideEffecting = sideEffecting;
            this.remainingThreads = new CountDownLatch(numberOfThreads);
        }

        boolean isSideEffecting() {
            return sideEffecting;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        void perform(Node node) {
            try {
                if (!cancelled) {
                    action.accept(node);
                }
            } finally {
                remainingThreads.countDown();
                if (remainingThreads.getCount() == 0L) {
                    onDone.accept(action);
                }
            }
        }

        @SuppressWarnings("unchecked")
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (remainingThreads.getCount() > 0) {
                cancelled = true;
                return true;
            } else {
                return false;
            }
        }

        @Override
        public Void get() throws InterruptedException, ExecutionException {
            this.remainingThreads.await();
            return null;
        }

        public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            if (!this.remainingThreads.await(timeout, unit)) {
                throw new TimeoutException("Timeout for waiting for thread local action exceeded.");
            }
            return null;
        }

        public boolean isDone() {
            return cancelled || this.remainingThreads.getCount() == 0;
        }
    }

    static final class HandshakeEntry {

        final Handshake<?> handshake;
        final HandshakeEntry next;

        private volatile HandshakeEntry prev;

        HandshakeEntry(Handshake<?> handshake, HandshakeEntry next) {
            this.handshake = handshake;
            if (next != null) {
                next.prev = this;
            }
            this.next = next;
        }

        Throwable process(Node enclosingNode) {
            /*
             * Retain schedule order and process next first. Schedule order is important for events
             * that perform synchronization between multiple threads to avoid deadlocks.
             *
             * We use a prev pointer to avoid arbitrary deep recursive stacks processing handshakes.
             */
            HandshakeEntry current = this;
            while (current.next != null) {
                current = current.next;
            }

            assert current != null;
            Throwable ex = null;
            while (current != null) {
                try {
                    current.handshake.perform(enclosingNode);
                } catch (Throwable e) {
                    ex = combineThrowable(ex, e);
                } finally {
                    current = current.prev;
                }
            }
            return ex;
        }
    }

    protected final TruffleSafepointImpl getThreadState(Thread thread) {
        return SAFEPOINTS.computeIfAbsent(thread, (t) -> new TruffleSafepointImpl(this));
    }

    protected static final class TruffleSafepointImpl extends TruffleSafepoint {

        private final ReentrantLock lock = new ReentrantLock();
        private final ThreadLocalHandshake impl;
        private volatile boolean fastPendingSet;
        private boolean sideEffectsEnabled = true;
        private Interrupter blockedAction;
        private boolean interrupted;
        private HandshakeEntry sideEffectHandshakes;
        private HandshakeEntry allHandshakes;

        TruffleSafepointImpl(ThreadLocalHandshake handshake) {
            super(DefaultRuntimeAccessor.ENGINE);
            this.impl = handshake;
        }

        void putHandshake(Thread t, Handshake<?> handshake) {
            lock.lock();
            try {
                if (!handshake.sideEffecting) {
                    sideEffectHandshakes = new HandshakeEntry(handshake, sideEffectHandshakes);
                }
                allHandshakes = new HandshakeEntry(handshake, allHandshakes);

                if (isPending() && !fastPendingSet) {
                    fastPendingSet = true;
                    setFastPendingAndInterrupt(t);
                }
            } finally {
                lock.unlock();
            }
        }

        private void setFastPendingAndInterrupt(Thread t) {
            assert lock.isHeldByCurrentThread();
            impl.setFastPending(t);
            Interrupter action = this.blockedAction;
            if (action != null) {
                action.interrupt(t);
                interrupted = true;
            }
        }

        HandshakeEntry takeHandshake() {
            lock.lock();
            try {
                if (isPending()) {
                    assert fastPendingSet : "invalid state";

                    fastPendingSet = false;
                    impl.clearFastPending();

                    HandshakeEntry taken;
                    if (sideEffectsEnabled) {
                        taken = this.allHandshakes;
                        this.allHandshakes = null;
                    } else {
                        taken = this.sideEffectHandshakes;
                    }
                    this.sideEffectHandshakes = null;

                    if (this.interrupted) {
                        this.interrupted = false;
                        this.blockedAction.resetInterrupted();
                    }
                    return taken;
                }
                return null;
            } finally {
                lock.unlock();
            }
        }

        @Override
        @TruffleBoundary
        public Interrupter setBlocked(Interrupter interruptable) {
            assert impl.getCurrent() == this : "Cannot be used from a different thread.";
            lock.lock();
            try {
                Interrupter prev = this.blockedAction;
                if (interruptable != null && isPending()) {
                    interruptable.interrupt(Thread.currentThread());
                    interrupted = true;
                }
                this.blockedAction = interruptable;
                if (prev != null && interrupted) {
                    prev.resetInterrupted();
                    interrupted = false;
                }
                return prev;
            } finally {
                lock.unlock();
            }
        }

        private boolean isPending() {
            return (sideEffectsEnabled && allHandshakes != null) || (!sideEffectsEnabled && sideEffectHandshakes != null);
        }

        @Override
        @TruffleBoundary
        public boolean setAllowSideEffects(boolean enabled) {
            assert impl.getCurrent() == this : "Cannot be used from a different thread.";
            lock.lock();
            try {
                boolean prev = this.sideEffectsEnabled;
                this.sideEffectsEnabled = enabled;
                updateFastPending();
                return prev;
            } finally {
                lock.unlock();
            }
        }

        private void updateFastPending() {
            if (isPending()) {
                if (!fastPendingSet) {
                    fastPendingSet = true;
                    setFastPendingAndInterrupt(Thread.currentThread());
                }
            } else {
                if (fastPendingSet) {
                    fastPendingSet = false;
                    impl.clearFastPending();
                }
            }

        }
    }
}