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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class LatestValuePublisherTest {
    @Test
    void slowSubscriberReceivesOnlyLatestSnapshotOnNextDemand() {
        LatestValuePublisher<Integer> publisher = new LatestValuePublisher<>();
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
        LatestValuePublisher<String> publisher = new LatestValuePublisher<>();
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
        LatestValuePublisher<Integer> publisher = new LatestValuePublisher<>();
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
        LatestValuePublisher<Integer> publisher = new LatestValuePublisher<>();
        publisher.subscribe(new Flow.Subscriber<>() {
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
        });
        TestSubscriber<Integer> healthy = new TestSubscriber<>();
        publisher.subscribe(healthy);
        healthy.request(1);

        publisher.submit(1);

        assertEquals(List.of(1), healthy.values);
    }

    @Test
    void throwingCompletionDoesNotBlockOtherSubscribers() {
        LatestValuePublisher<Integer> publisher = new LatestValuePublisher<>();
        publisher.subscribe(new Flow.Subscriber<>() {
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
        });
        TestSubscriber<Integer> healthy = new TestSubscriber<>();
        publisher.subscribe(healthy);
        healthy.request(1);
        publisher.submit(1);

        publisher.close();

        assertTrue(healthy.completed.get());
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
