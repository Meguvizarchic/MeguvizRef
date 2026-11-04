package io.github.jichuo1.meguviz.ref.runtime.resolve;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.Function;

/** Bounded synchronous single-flight. User work runs outside this monitor; waiters never cancel owners. */
public final class QueryCoordinator<K, V> {
    private final Map<K, Flight<V>> flights = new HashMap<>();
    private final Map<Thread, Thread> waits = new HashMap<>();
    private final int capacity;
    private boolean closed;
    public QueryCoordinator(int capacity) { if (capacity < 1) throw new IllegalArgumentException("capacity"); this.capacity = capacity; }
    public V execute(K key, Supplier<V> work, long timeoutMillis) throws InterruptedException, TimeoutException {
        return execute(key, work, timeoutMillis, null);
    }
    /** The handler runs only when this caller's wait is interrupted, never for owner work. */
    public V execute(K key, Supplier<V> work, long timeoutMillis, Function<InterruptedException, V> waiterInterrupted) throws InterruptedException, TimeoutException {
        Flight<V> flight; CompletableFuture<V> future = null;
        Thread current = Thread.currentThread();
        synchronized (this) {
            if (closed) throw new CancellationException("scope closed");
            flight = flights.get(key);
            if (flight == null) {
                if (flights.size() >= capacity) throw new RejectedExecutionException("single-flight capacity");
                flight = new Flight<>(current); flights.put(key, flight);
            } else {
                Thread dependency = flight.owner;
                Set<Thread> seen = new HashSet<>();
                while (dependency != null && seen.add(dependency)) {
                    if (dependency == current) throw new IllegalStateException("recursive or cyclic query");
                    dependency = waits.get(dependency);
                }
                waits.put(current, flight.owner);
                // The first waiter creates the future; a flight nobody waits for never allocates one.
                if (flight.future == null) flight.future = new CompletableFuture<>();
                future = flight.future;
            }
        }
        if (future == null) {
            V result;
            try { result = work.get(); }
            catch (Throwable failure) { CompletableFuture<V> waiting = finish(key, flight); if (waiting != null) waiting.completeExceptionally(failure); throw failure; }
            CompletableFuture<V> waiting = finish(key, flight);
            if (waiting != null) waiting.complete(result);
            return result;
        }
        try { return future.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            if (waiterInterrupted == null) throw interrupted;
            return waiterInterrupted.apply(interrupted);
        }
        catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } finally { synchronized (this) { waits.remove(current); } }
    }
    /**
     * Owner completion: removing the flight and reading its future under the monitor means every waiter that attached
     * gets this outcome, and a later caller starts a new flight.
     */
    private CompletableFuture<V> finish(K key, Flight<V> flight) {
        synchronized (this) {
            if (flights.get(key) == flight) flights.remove(key);
            return flight.future;
        }
    }
    public synchronized void close() {
        closed = true;
        for (Flight<V> flight : flights.values()) if (flight.future != null) flight.future.completeExceptionally(new CancellationException("scope closed"));
        flights.clear(); waits.clear();
    }
    private static final class Flight<V> {
        final Thread owner;
        /** Guarded by the coordinator monitor; created by the first waiter. */
        CompletableFuture<V> future;
        Flight(Thread owner) { this.owner = owner; }
    }
}
