package io.github.jichuo1.meguviz.ref.scope;

import io.github.jichuo1.meguviz.ref.api.descriptor.*;
import io.github.jichuo1.meguviz.ref.api.evidence.*;
import io.github.jichuo1.meguviz.ref.api.query.*;
import io.github.jichuo1.meguviz.ref.api.result.*;
import io.github.jichuo1.meguviz.ref.runtime.invoke.*;
import io.github.jichuo1.meguviz.ref.runtime.resolve.*;
import io.github.jichuo1.meguviz.ref.spi.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Synchronous code lifetime. close rejects new work without waiting for in-flight target methods. */
public final class CodeScope implements AutoCloseable {
    public enum State { OPEN, RETIRING, CLOSED }
    /** Sign bit of {@link #admission}; set exactly once by close, under the lock. */
    private static final int CLOSING = Integer.MIN_VALUE;
    /** In-process identities: a per-load random prefix plus a sequence, without SecureRandom per scope. */
    private static final long IDENTITY_PREFIX = new SplittableRandom().nextLong();
    private static final AtomicLong IDENTITY_SEQUENCE = new AtomicLong();
    private final Object lock = new Object();
    private final AccessPolicy accessPolicy;
    private final int capacity;
    /**
     * In-flight resolutions/invocations in the low bits, CLOSING in the sign bit. Admission CASes the count only while
     * CLOSING is clear, so close's getAndAdd is the single linearization point: work admitted before it is counted and
     * drained, later work is rejected without touching the count.
     */
    private final AtomicInteger admission = new AtomicInteger();
    private ClassLoader loader;
    private State state = State.OPEN;
    private String identity = newIdentity();
    private long codeEpoch, nextSlot = 1, cacheHits;
    private boolean resolved;
    private final Map<QuerySpec, ResolutionResult> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<Member, Slot> memberSlots = new HashMap<>();
    /** Current identity, epoch and append-only slot table; replaced under the lock, read lock-free by plan calls. */
    private volatile Bindings bindings;
    /** Created on the first cache miss and dropped by advanceCodeEpoch; diagnostics treat null as empty. */
    private ReflectionResolver resolver;
    private QueryCoordinator<QuerySpec, ResolutionResult> coordinator;
    private ProviderRegistry providers;
    private String codeFingerprint;
    private volatile String closeFailureType;
    private CodeScope(ClassLoader loader, AccessPolicy accessPolicy, int capacity) {
        if (capacity < 1 || capacity > 1000000) throw new IllegalArgumentException("capacity");
        this.loader = loader; this.accessPolicy = Objects.requireNonNull(accessPolicy); this.capacity = capacity;
        bindings = new Bindings(identity, 0, nextSlot, capacity);
    }
    private static String newIdentity() { return new UUID(IDENTITY_PREFIX, IDENTITY_SEQUENCE.incrementAndGet()).toString(); }
    public static CodeScope open(ClassLoader loader) { return new CodeScope(loader, AccessPolicy.PRESERVE_JAVA_ACCESS, 4096); }
    public static CodeScope open(ClassLoader loader, AccessPolicy accessPolicy, int capacity) { return new CodeScope(loader, accessPolicy, capacity); }
    public AccessPolicy accessPolicy() { return accessPolicy; }
    public boolean usesLoader(ClassLoader candidate) { synchronized (lock) { return state == State.OPEN && loader == candidate; } }
    public State state() { synchronized (lock) { return state; } }
    public long codeEpoch() { synchronized (lock) { return codeEpoch; } }
    public String identity() { synchronized (lock) { return identity; } }
    /** Provider teardown failures remain observable without replacing an in-flight target's exception. */
    public String closeFailureType() { return closeFailureType; }
    /** Registry ownership transfers once, before resolution; create a new scope to install a new snapshot. */
    public void installProviders(ProviderRegistry registry, CodeIdentity codeIdentity) {
        Objects.requireNonNull(registry); Objects.requireNonNull(codeIdentity);
        if (codeIdentity.strength() != CodeIdentity.Strength.STRONG) throw new IllegalArgumentException("provider requires strong code identity");
        synchronized (lock) {
            if (state != State.OPEN || resolved || providers != null) throw new IllegalStateException("provider snapshot already frozen");
            registry.claim(); providers = registry; codeFingerprint = codeIdentity.fingerprint();
        }
    }
    public ResolutionResult resolve(QuerySpec query) {
        Objects.requireNonNull(query);
        Context context;
        synchronized (lock) {
            if (state != State.OPEN) return failure(query, ResolutionStatus.STALE, "scope_closed");
            if (Thread.currentThread().isInterrupted()) return failure(query, ResolutionStatus.CANCELLED, "interrupted");
            resolved = true;
            ResolutionResult cached = cache.get(query);
            if (cached != null) { cacheHits++; return cached; }
            admission.incrementAndGet(); // OPEN under the lock, so CLOSING cannot be set concurrently
            if (resolver == null) { resolver = new ReflectionResolver(capacity); coordinator = new QueryCoordinator<>(Math.min(capacity, 256)); }
            context = new Context(loader, identity, codeEpoch, resolver, coordinator, providers, codeFingerprint);
        }
        try {
            return context.coordinator.execute(query, () -> resolveUncached(query, context), 2000, interrupted -> {
                Thread.currentThread().interrupt();
                return failure(query, ResolutionStatus.CANCELLED, "wait_interrupted", ResolutionReceipt.InterruptionOrigin.SINGLE_FLIGHT_WAITER);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return failure(query, ResolutionStatus.CANCELLED, "wait_interrupted");
        } catch (TimeoutException e) { return failure(query, ResolutionStatus.PARTIAL, "single_flight_timeout"); }
        catch (CancellationException e) { return failure(query, ResolutionStatus.STALE, "scope_invalidated"); }
        catch (RejectedExecutionException e) { return failure(query, ResolutionStatus.PARTIAL, "single_flight_capacity"); }
        catch (IllegalStateException e) {
            if ("recursive or cyclic query".equals(e.getMessage())) return failure(query, ResolutionStatus.PARTIAL, "recursive_or_cyclic_query");
            throw e;
        } finally { release(); }
    }
    private ResolutionResult resolveUncached(QuerySpec query, Context context) {
        synchronized (lock) {
            if (!current(context)) return failure(query, ResolutionStatus.STALE, "scope_invalidated");
            ResolutionResult cached = cache.get(query);
            if (cached != null) { cacheHits++; return cached; }
        }
        ReflectionResolver.Outcome outcome = context.resolver.resolve(query, context.loader, context.providers, context.codeFingerprint);
        synchronized (lock) {
            if (!current(context)) return failure(query, ResolutionStatus.STALE, "scope_invalidated");
            // Resolver outcomes are distinct by Member: structural uses visited, provider uses seen, and exact has one entry.
            int missing = 0;
            for (ReflectionResolver.Entry entry : outcome.entries) if (!memberSlots.containsKey(entry.member)) missing++;
            Bindings table = bindings;
            if ((long) table.size + missing > capacity) {
                if (outcome.status == ResolutionStatus.CANCELLED) {
                    ResolutionReceipt receipt = new ResolutionReceipt(query.summary(), query.selection(), outcome.status, outcome.coverage, Collections.emptyList(), outcome.reason, outcome.cause);
                    return new ResolutionResult(outcome.status, Collections.emptyList(), receipt);
                }
                return failure(query, ResolutionStatus.PARTIAL, "binding_capacity");
            }
            List<ResolvedMember> members = new ArrayList<>(outcome.entries.size());
            List<MemberDescriptor> descriptors = new ArrayList<>(outcome.entries.size());
            for (ReflectionResolver.Entry entry : outcome.entries) {
                Slot slot = memberSlots.get(entry.member);
                if (slot == null) { slot = new Slot(nextSlot++, entry.member, entry.descriptor); memberSlots.put(entry.member, slot); table.append(slot); }
                // Tokens share the slot's (equal) descriptor instance so plan calls usually pass the identity check.
                members.add(new ResolvedMember(identity, codeEpoch, slot.number, slot.descriptor)); descriptors.add(entry.descriptor);
            }
            ResolutionReceipt receipt = new ResolutionReceipt(query.summary(), query.selection(), outcome.status, outcome.coverage, descriptors, outcome.reason, outcome.cause);
            ResolutionResult result = new ResolutionResult(outcome.status, members, receipt);
            if (outcome.coverage.complete() && (outcome.status == ResolutionStatus.RESOLVED || outcome.status == ResolutionStatus.ALL_MATCHES)) {
                cache.put(query, result);
                if (cache.size() > capacity) cache.remove(cache.keySet().iterator().next());
            }
            return result;
        }
    }
    private boolean current(Context context) { return state == State.OPEN && identity.equals(context.identity) && codeEpoch == context.epoch; }
    private static ResolutionResult failure(QuerySpec query, ResolutionStatus status, String reason) {
        return failure(query, status, reason, ResolutionReceipt.InterruptionOrigin.UNKNOWN);
    }
    private static ResolutionResult failure(QuerySpec query, ResolutionStatus status, String reason, ResolutionReceipt.InterruptionOrigin origin) {
        Coverage coverage = new Coverage("java-visible:" + query.owner() + ":" + query.hierarchy(), false, 0, reason);
        return new ResolutionResult(status, Collections.emptyList(), new ResolutionReceipt(query.summary(), query.selection(), status, coverage, Collections.emptyList(), reason, null, origin));
    }
    public MethodPlan bindMethod(ResolvedMember member) throws IllegalAccessException { prepare(member, MemberKind.METHOD); return new MethodPlan(this, member); }
    public FieldPlan bindField(ResolvedMember member) throws IllegalAccessException { prepare(member, MemberKind.FIELD); return new FieldPlan(this, member); }
    public ConstructorPlan bindConstructor(ResolvedMember member) throws IllegalAccessException { prepare(member, MemberKind.CONSTRUCTOR); return new ConstructorPlan(this, member); }
    private void prepare(ResolvedMember token, MemberKind kind) throws IllegalAccessException {
        if (token.descriptor().kind() != kind) throw new IllegalArgumentException("wrong member kind");
        Member member = acquire(token);
        try { prepareAccess(member); } finally { release(); }
    }
    private void prepareAccess(Member member) throws IllegalAccessException {
        if (accessPolicy != AccessPolicy.TRY_SUPPRESS_JAVA_CHECKS) return;
        try { ((AccessibleObject) member).setAccessible(true); }
        catch (RuntimeException e) {
            IllegalAccessException denied = new IllegalAccessException("public access suppression rejected: " + member.getName()); denied.initCause(e); throw denied;
        }
    }
    /** Deliberate ownership escape: this raw reference can outlive close and retain its defining loader. */
    public Member exportRaw(ResolvedMember token) throws IllegalAccessException {
        Member member = acquire(token);
        try { prepareAccess(member); return member; } finally { release(); }
    }
    /**
     * Lock-free admission. The token is checked against one bindings snapshot, then the in-flight count is CASed only
     * while CLOSING is clear. A snapshot read before the CAS cannot be a closed one: close sets CLOSING before replacing
     * it. A call admitted with a snapshot read just before advanceCodeEpoch is ordered before that epoch change.
     */
    private Member acquire(ResolvedMember token) {
        Slot slot = bindings.find(token);
        for (;;) {
            int value = admission.get();
            if (value < 0) throw new StalePlanException("scope identity or epoch is stale");
            if (admission.compareAndSet(value, value + 1)) return slot.member;
        }
    }
    public Object invokeMethod(ResolvedMember token, Object receiver, Object[] arguments) throws IllegalAccessException, InvocationTargetException {
        Member member = acquire(token);
        try { return ((Method) member).invoke(receiver, arguments); } finally { release(); }
    }
    public Object getField(ResolvedMember token, Object receiver) throws IllegalAccessException {
        Member member = acquire(token);
        try { return ((Field) member).get(receiver); } finally { release(); }
    }
    public void setField(ResolvedMember token, Object receiver, Object value) throws IllegalAccessException {
        Member member = acquire(token);
        try { ((Field) member).set(receiver, value); } finally { release(); }
    }
    public Object construct(ResolvedMember token, Object[] arguments) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        Member member = acquire(token);
        try { return ((Constructor<?>) member).newInstance(arguments); } finally { release(); }
    }
    public void advanceCodeEpoch(String reason) {
        if (reason == null || reason.isEmpty()) throw new IllegalArgumentException("invalidation reason");
        QueryCoordinator<QuerySpec, ResolutionResult> oldCoordinator;
        synchronized (lock) {
            if (state != State.OPEN) throw new StalePlanException("scope closed");
            if (codeEpoch == Long.MAX_VALUE) { identity = newIdentity(); codeEpoch = 0; } else codeEpoch++;
            cache.clear(); memberSlots.clear(); bindings = new Bindings(identity, codeEpoch, nextSlot, capacity);
            if (resolver != null) resolver.clear();
            resolver = null; codeFingerprint = null;
            oldCoordinator = coordinator; coordinator = null;
        }
        if (oldCoordinator != null) oldCoordinator.close();
    }
    public Map<String, Long> diagnostics() {
        synchronized (lock) {
            Map<String, Long> values = new LinkedHashMap<>();
            values.put("cacheHits", cacheHits); values.put("cachedQueries", (long) cache.size()); values.put("bindings", (long) bindings.size);
            values.put("schemas", resolver == null ? 0L : (long) resolver.schemaSize()); values.put("parameterReads", resolver == null ? 0L : resolver.parameterReads());
            values.put("candidateChecks", resolver == null ? 0L : resolver.candidateChecks()); values.put("lookups", resolver == null ? 0L : resolver.lookups());
            values.put("inFlight", (long) (admission.get() & Integer.MAX_VALUE));
            return Collections.unmodifiableMap(values);
        }
    }
    @Override public void close() {
        ProviderRegistry retired = null;
        QueryCoordinator<QuerySpec, ResolutionResult> previous;
        synchronized (lock) {
            if (state != State.OPEN) return;
            int inFlight = admission.getAndAdd(CLOSING); // linearization point; OPEN implies CLOSING was clear
            state = State.RETIRING; loader = null; bindings = Bindings.CLOSED; cache.clear(); memberSlots.clear(); codeFingerprint = null;
            if (resolver != null) resolver.clear();
            previous = coordinator;
            if (inFlight == 0) { state = State.CLOSED; retired = providers; providers = null; }
        }
        if (previous != null) previous.close();
        if (retired != null) {
            try { retired.close(); }
            catch (Throwable failure) {
                closeFailureType = failure.getClass().getName();
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw rethrow(failure);
            }
        }
    }
    private void release() {
        // Only the release that drains the last admitted work after close observes exactly CLOSING.
        if (admission.decrementAndGet() != CLOSING) return;
        ProviderRegistry retired = null;
        synchronized (lock) {
            if (state == State.RETIRING && admission.get() == CLOSING) {
                state = State.CLOSED; retired = providers; providers = null;
                if (resolver != null) resolver.clear();
            }
        }
        if (retired != null) {
            try { retired.close(); }
            catch (Throwable failure) {
                closeFailureType = failure.getClass().getName();
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                if (failure instanceof Error) throw (Error) failure;
            }
        }
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable> RuntimeException rethrow(Throwable failure) throws T { throw (T) failure; }
    private static final class Slot {
        final long number;
        final Member member;
        final MemberDescriptor descriptor;
        Slot(long number, Member member, MemberDescriptor descriptor) { this.number = number; this.member = member; this.descriptor = descriptor; }
    }
    /** Slot numbers stay globally increasing; this epoch's slot {@code base + i} lives at index {@code i}. */
    private static final class Bindings {
        static final Bindings CLOSED = new Bindings(null, 0, 0, 0);
        private static final Slot[] EMPTY = new Slot[0];
        final String identity;
        final long epoch, base;
        private final int capacity;
        // Writers hold the scope lock: element, then table (on growth), then size. Readers read size, then table.
        private volatile Slot[] table = EMPTY;
        volatile int size;
        Bindings(String identity, long epoch, long base, int capacity) { this.identity = identity; this.epoch = epoch; this.base = base; this.capacity = capacity; }
        void append(Slot slot) {
            Slot[] current = table; int count = size;
            if (count == current.length) {
                current = Arrays.copyOf(current, (int) Math.min(capacity, Math.max(8L, 2L * count)));
                current[count] = slot; table = current;
            } else current[count] = slot;
            size = count + 1;
        }
        Slot find(ResolvedMember token) {
            if (identity == null || !identity.equals(token.scopeId()) || epoch != token.codeEpoch()) throw new StalePlanException("scope identity or epoch is stale");
            long index = token.slot() - base;
            int count = size;
            if (index < 0 || index >= count) throw new StalePlanException("binding no longer exists");
            Slot slot = table[(int) index];
            // A public token is not a security capability, but malformed tokens must not select another slot.
            MemberDescriptor descriptor = token.descriptor();
            if (descriptor != slot.descriptor && !descriptor.equals(slot.descriptor)) throw new StalePlanException("binding descriptor mismatch");
            return slot;
        }
    }
    private static final class Context {
        final ClassLoader loader;
        final String identity, codeFingerprint;
        final long epoch;
        final ReflectionResolver resolver;
        final QueryCoordinator<QuerySpec, ResolutionResult> coordinator;
        final ProviderRegistry providers;
        Context(ClassLoader loader, String identity, long epoch, ReflectionResolver resolver, QueryCoordinator<QuerySpec, ResolutionResult> coordinator, ProviderRegistry providers, String codeFingerprint) {
            this.loader = loader; this.identity = identity; this.epoch = epoch; this.resolver = resolver; this.coordinator = coordinator; this.providers = providers; this.codeFingerprint = codeFingerprint;
        }
    }
}
