/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.internal.calltree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class LatestValuePublisherTest {
    @Test
    void slowSubscriberReceivesOnlyLatestSnapshotOnNextDemand() {
        LatestValuePublisher<Integer> publisher = directPublisher();
        TestSubscriber<Integer> subscriber = new TestSubscriber<>();
        publisher.subscribe(subscriber);

        subscriber.request(1);
        publisher.submit(1);
        publisher.submit(2);
        publisher.submit(3);
        subscriber.request(1);

        assertEquals(List.of(1, 3), subscriber.values);
    }

    @Test
    void lateSubscriberReceivesFinalValueBeforeCompletion() {
        LatestValuePublisher<String> publisher = directPublisher();
        publisher.submit("final");
        publisher.close();
        TestSubscriber<String> subscriber = new TestSubscriber<>();

        publisher.subscribe(subscriber);
        subscriber.request(1);

        assertEquals(List.of("final"), subscriber.values);
        assertTrue(subscriber.completed.get());
    }

    @Test
    void reentrantSubmitIsNotLost() {
        LatestValuePublisher<Integer> publisher = directPublisher();
        List<Integer> values = new ArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(2);
            }

            @Override
            public void onNext(Integer item) {
                values.add(item);
                if (item == 1) {
                    publisher.submit(2);
                }
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        publisher.submit(1);

        assertEquals(List.of(1, 2), values);
    }

    @Test
    void throwingSubscriberDoesNotBlockOtherSubscribers() {
        LatestValuePublisher<Integer> publisher = directPublisher();
        publisher.subscribe(new ThrowingOnNextSubscriber());
        TestSubscriber<Integer> healthy = new TestSubscriber<>();
        publisher.subscribe(healthy);
        healthy.request(1);

        publisher.submit(1);

        assertEquals(List.of(1), healthy.values);
    }

    @Test
    void throwingCompletionDoesNotBlockOtherSubscribers() {
        LatestValuePublisher<Integer> publisher = directPublisher();
        publisher.subscribe(new ThrowingOnCompleteSubscriber());
        TestSubscriber<Integer> healthy = new TestSubscriber<>();
        publisher.subscribe(healthy);
        healthy.request(1);
        publisher.submit(1);

        publisher.close();

        assertTrue(healthy.completed.get());
    }

    @Test
    void blockingSubscriberDoesNotBlockSubmitOrOtherSubscriber() throws Exception {
        LatestValuePublisher<Integer> publisher = new LatestValuePublisher<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(2);
            }

            @Override
            public void onNext(Integer item) {
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });
        TestSubscriber<Integer> healthy = new TestSubscriber<>();
        publisher.subscribe(healthy);
        healthy.request(1);

        long started = System.nanoTime();
        publisher.submit(1);
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        publisher.submit(2);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        awaitValues(healthy, 1);
        assertTrue(elapsedMillis < 500, "submit must not wait for subscriber callback");
        assertEquals(1, healthy.values.size());
        assertTrue(healthy.values.get(0) == 1 || healthy.values.get(0) == 2);
        release.countDown();
    }

    private static <T> LatestValuePublisher<T> directPublisher() {
        return new LatestValuePublisher<>(Runnable::run);
    }

    private static void awaitValues(TestSubscriber<?> subscriber, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (subscriber.values.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private static final class ThrowingOnNextSubscriber implements Flow.Subscriber<Integer> {
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(1);
        }

        @Override
        public void onNext(Integer item) {
            throw new IllegalStateException("subscriber failure");
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }
    }

    private static final class ThrowingOnCompleteSubscriber implements Flow.Subscriber<Integer> {
        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(1);
        }

        @Override
        public void onNext(Integer item) {
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
            throw new IllegalStateException("completion failure");
        }
    }

    private static final class TestSubscriber<T> implements Flow.Subscriber<T> {
        private final List<T> values = new ArrayList<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean completed = new AtomicBoolean();

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription.set(value);
        }

        @Override
        public void onNext(T item) {
            values.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
            completed.set(true);
        }

        void request(long demand) {
            subscription.get().request(demand);
        }
    }
}
