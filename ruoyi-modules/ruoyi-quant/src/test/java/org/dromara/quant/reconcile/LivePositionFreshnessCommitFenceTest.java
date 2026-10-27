package org.dromara.quant.reconcile;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LivePositionFreshnessCommitFenceTest {

    @Test
    void staleGenerationMustNotRunSnapshotCommit() {
        var freshness = new LivePositionFreshness();
        freshness.invalidate(7L);
        long oldGeneration = freshness.generation(7L);
        freshness.invalidate(7L);
        var committed = new AtomicBoolean();

        boolean accepted = freshness.runIfGenerationUnchanged(7L, oldGeneration, () -> committed.set(true));

        assertFalse(accepted);
        assertFalse(committed.get());
    }

    @Test
    void eventInvalidationWaitsUntilSnapshotCommitFinishes() throws Exception {
        var freshness = new LivePositionFreshness();
        freshness.invalidate(8L);
        long generation = freshness.generation(8L);
        var actionEntered = new CountDownLatch(1);
        var finishAction = new CountDownLatch(1);
        var eventStarted = new CountDownLatch(1);
        var eventThread = new AtomicReference<Thread>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var commit = executor.submit(() -> freshness.runIfGenerationUnchanged(8L, generation, () -> {
                actionEntered.countDown();
                try {
                    if (!finishAction.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to finish simulated transaction");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }));

            assertTrue(actionEntered.await(5, TimeUnit.SECONDS));
            var event = executor.submit(() -> {
                eventThread.set(Thread.currentThread());
                eventStarted.countDown();
                freshness.invalidate(8L);
                return freshness.generation(8L);
            });
            assertTrue(eventStarted.await(5, TimeUnit.SECONDS));
            long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (eventThread.get().getState() != Thread.State.BLOCKED && System.nanoTime() < waitUntil) {
                Thread.sleep(5);
            }
            assertEquals(Thread.State.BLOCKED, eventThread.get().getState(),
                "invalidation must block on the generation fence while the snapshot commit is active");

            finishAction.countDown();
            assertTrue(commit.get(5, TimeUnit.SECONDS));
            assertEquals(generation + 1, event.get(5, TimeUnit.SECONDS));
        } finally {
            finishAction.countDown();
        }
    }
}
