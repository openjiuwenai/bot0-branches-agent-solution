/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.internal.calltree;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 只保留最新值、遵守 demand、关闭后仍向晚订阅者回放最终值的 Publisher。
 *
 * @since 2026-07-27
 */
public final class LatestValuePublisher<T extends Object> implements Flow.Publisher<T>, AutoCloseable {
    private static final int MAX_DISPATCH_THREADS = 32;
    private static final Executor DISPATCHER = new ThreadPoolExecutor(0, MAX_DISPATCH_THREADS,
            30L, TimeUnit.SECONDS, new SynchronousQueue<>(), runnable -> {
                Thread thread = Executors.defaultThreadFactory().newThread(runnable);
                thread.setName("agent-client-calltree-dispatch");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private final CopyOnWriteArrayList<LatestSubscription<T>> subscriptions = new CopyOnWriteArrayList<>();
    private final Executor dispatcher;
    private volatile Versioned<T> latest;
    private volatile boolean closed;
    private long version;

    /**
     * 构造最新值发布者，使用默认分发器。
     */
    public LatestValuePublisher() {
        this(DISPATCHER);
    }

    LatestValuePublisher(Executor dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        LatestSubscription<T> subscription = new LatestSubscription<>(subscriber, this, dispatcher);
        subscriptions.add(subscription);
        subscriber.onSubscribe(subscription);
        subscription.drain();
    }

    /**
     * 提交最新值，通知所有订阅者。
     *
     * @param value 最新值
     */
    public void submit(T value) {
        synchronized (this) {
            if (closed) {
                return;
            }
            latest = new Versioned<>(++version, Objects.requireNonNull(value, "value"));
        }
        subscriptions.forEach(LatestSubscription::drain);
    }

    /**
     * 返回最新提交的值。
     *
     * @return 最新值，若从未提交过则为空
     */
    public Optional<T> latest() {
        Versioned<T> value = latest;
        return Optional.ofNullable(value == null ? null : value.value());
    }

    private Versioned<T> versioned() {
        return latest;
    }

    private void remove(LatestSubscription<T> subscription) {
        subscriptions.remove(subscription);
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        subscriptions.forEach(LatestSubscription::drain);
    }

    private record Versioned<T>(long version, T value) {
    }

    private static final class LatestSubscription<T extends Object> implements Flow.Subscription {
        private final Flow.Subscriber<? super T> subscriber;
        private final LatestValuePublisher<T> owner;
        private final Executor dispatcher;
        private long demand;
        private long deliveredVersion;
        private boolean cancelled;
        private boolean completed;
        private final AtomicInteger drainWork = new AtomicInteger();

        private LatestSubscription(Flow.Subscriber<? super T> subscriber, LatestValuePublisher<T> owner,
                Executor dispatcher) {
            this.subscriber = subscriber;
            this.owner = owner;
            this.dispatcher = dispatcher;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                synchronized (this) {
                    if (cancelled || completed) {
                        return;
                    }
                    cancelled = true;
                }
                owner.remove(this);
                subscriber.onError(new IllegalArgumentException("non-positive subscription request"));
                return;
            }
            synchronized (this) {
                if (cancelled || completed) {
                    return;
                }
                demand = demand > Long.MAX_VALUE - n ? Long.MAX_VALUE : demand + n;
            }
            drain();
        }

        @Override
        public synchronized void cancel() {
            cancelled = true;
            owner.remove(this);
        }

        private void drain() {
            if (drainWork.getAndIncrement() != 0) {
                return;
            }
            try {
                dispatcher.execute(this::runDrain);
            } catch (RejectedExecutionException overloaded) {
                drainWork.set(0);
                synchronized (this) {
                    cancelled = true;
                }
                owner.remove(this);
            }
        }

        private void runDrain() {
            int missed = 1;
            for (;;) {
                Versioned<T> value = owner.versioned();
                T next = null;
                boolean completeNow = false;
                synchronized (this) {
                    if (cancelled || completed) {
                        drainWork.addAndGet(-missed);
                        return;
                    }
                    if (demand > 0 && value != null && value.version() > deliveredVersion) {
                        demand--;
                        deliveredVersion = value.version();
                        next = value.value();
                    }
                    if (next == null && owner.closed && (value == null
                            || deliveredVersion >= value.version())) {
                        completed = true;
                        completeNow = true;
                    }
                }
                if (next != null) {
                    try {
                        subscriber.onNext(next);
                    } catch (IllegalStateException | IllegalArgumentException | NullPointerException
                            | UnsupportedOperationException failure) {
                        cancel();
                        drainWork.addAndGet(-missed);
                        return;
                    }
                } else if (completeNow) {
                    owner.remove(this);
                    try {
                        subscriber.onComplete();
                    } catch (IllegalStateException | IllegalArgumentException
                            | UnsupportedOperationException ignored) {
                        // Subscriber callbacks are isolated; one broken consumer must not block others.
                    }
                    drainWork.addAndGet(-missed);
                    return;
                } else {
                    missed = drainWork.addAndGet(-missed);
                    if (missed == 0) {
                        return;
                    }
                }
            }
        }
    }
}
