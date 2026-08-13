/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.internal.calltree;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/** 只保留最新值、遵守 demand、关闭后仍向晚订阅者回放最终值的 Publisher。 */
public final class LatestValuePublisher<T> implements Flow.Publisher<T>, AutoCloseable {
    private final CopyOnWriteArrayList<LatestSubscription<T>> subscriptions = new CopyOnWriteArrayList<>();
    private volatile Versioned<T> latest;
    private volatile boolean closed;
    private long version;

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        LatestSubscription<T> subscription = new LatestSubscription<>(subscriber, this);
        subscriptions.add(subscription);
        subscriber.onSubscribe(subscription);
        subscription.drain();
    }

    public void submit(T value) {
        synchronized (this) {
            if (closed) {
                return;
            }
            latest = new Versioned<>(++version, Objects.requireNonNull(value, "value"));
        }
        subscriptions.forEach(LatestSubscription::drain);
    }

    public T latest() {
        Versioned<T> value = latest;
        return value == null ? null : value.value();
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

    private static final class LatestSubscription<T> implements Flow.Subscription {
        private final Flow.Subscriber<? super T> subscriber;
        private final LatestValuePublisher<T> owner;
        private long demand;
        private long deliveredVersion;
        private boolean cancelled;
        private boolean completed;
        private final AtomicInteger drainWork = new AtomicInteger();

        private LatestSubscription(Flow.Subscriber<? super T> subscriber, LatestValuePublisher<T> owner) {
            this.subscriber = subscriber;
            this.owner = owner;
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
                    } else if (owner.closed && (value == null || deliveredVersion >= value.version())) {
                        completed = true;
                        completeNow = true;
                    }
                }
                if (next != null) {
                    try {
                        subscriber.onNext(next);
                    } catch (RuntimeException failure) {
                        cancel();
                        drainWork.addAndGet(-missed);
                        return;
                    }
                } else if (completeNow) {
                    owner.remove(this);
                    try {
                        subscriber.onComplete();
                    } catch (RuntimeException ignored) {
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
