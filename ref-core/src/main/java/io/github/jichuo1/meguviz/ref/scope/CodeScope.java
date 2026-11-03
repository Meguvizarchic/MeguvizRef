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
    /** Sign bit of every admission word; set exactly once per word by close, under the lock. */
    private static final int CLOSING = Integer.MIN_VALUE;
    /** Bit 30 of the base word only: set under the lock right after {@link #cells} is published. */
    private static final int INFLATED = 1 << 30;
    /** In-flight count bits of an admission word. */
    private static final int COUNT = INFLATED - 1;
    /** Admission word id of {@link #admission}; cell ids are 1..{@link #CELLS}. */
    private static final int BASE = 0;
    /** Power of two at least the processor count (2..64), like Striped64's table bound. */
    private static final int CELLS = cellCount(Runtime.getRuntime().availableProcessors());
    /**
     * Cells sit 32 ints (128 bytes, HotSpot's default @Contended padding width) apart, with a leading and trailing pad,
     * so no two cells and no neighbouring object share a cache line or an adjacent-line prefetch pair.
     */
    private static final int STRIDE = 32;
    /**
     * Index of the drain hand-off counter inside the leading pad of {@link #cells}: admission words that still held
     * work at close, plus one for close itself. Written before the first CLOSING bit; the last releaser of each busy
     * word and close each take one share, and whoever takes the last closes the providers. Rejected callers never
     * touch it. Without cells the base word's last releaser alone is the hand-off, as before.
     */
    private static final int PENDING = 0;
    /** In-process identities: a per-load random prefix plus a sequence, without SecureRandom per scope. */
    private static final long IDENTITY_PREFIX = new SplittableRandom().nextLong();
    private static final AtomicLong IDENTITY_SEQUENCE = new AtomicLong();
    private final Object lock = new Object();
    private final AccessPolicy accessPolicy;
    private final int capacity;
    /**
     * Base admission word: in-flight resolutions/invocations in the low 30 bits, INFLATED in bit 30, CLOSING in the
     * sign bit. Plan calls CAS the count only while CLOSING and INFLATED are clear, so the uncontended path reads one
     * word; resolutions increment it under the lock while OPEN. Until a plan call's CAS on it fails, this is the only
     * admission word and close's getAndAdd is the single linearization point.
     */
    private final AtomicInteger admission = new AtomicInteger();
    /**
     * Padded per-thread admission cells (count and CLOSING, no INFLATED bit), created under the lock once a plan call's
     * CAS on the base word has failed twice in a row (observed contention) and never replaced. Once the base word
     * shows INFLATED, plan calls count in a cell chosen by thread id, after that read found the base word open. close
     * sets CLOSING on the base word first and then on every cell,
     * so a call that starts after any caller was rejected finds the base word closed: rejections never precede an
     * admission. A scope that never sees contention never allocates them.
     */
    private volatile AtomicIntegerArray cells;
    private ClassLoader loader;
    private State state = State.OPEN;
    private String identity = newIdentity();
    private long codeEpoch, nextSlot = 1, cacheHits;
    private boolean resolved;
    private final Map<QuerySpec, ResolutionResult> cache = new LinkedHashMap<>(16, .75f, true);
    /**
     * Lock-free view of the cache's most recently used entry. Set under the lock by a cache hit, cleared by every put
     * (the new entry becomes the tail) and with the cache, so whenever it is non-null it is the LRU tail and serving it
     * leaves the eviction order unchanged. A scope whose results are never hit allocates nothing for it.
     */
    private volatile Recent recent;
    /** Hits served from {@link #recent}, created with the first memo; diagnostics add its sum to cacheHits. */
    private LongAdder recentHits;
    private final Map<Member, Slot> memberSlots = new HashMap<>();
    /** Current identity, epoch and append-only slot table; replaced under the lock, read lock-free by plan calls. */
    private volatile Bindings bindings;
    /** Created on the first cache miss and dropped by advanceCodeEpoch; diagnostics treat null as empty. */
    private ReflectionResolver resolver;
    /** Keys are QuerySpec for token resolves and RawKey for resolveRaw; values are the matching result type. */
    private QueryCoordinator<Object, Object> coordinator;
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
    /** Default capacity 4096 with an explicit access policy. */
    public static CodeScope open(ClassLoader loader, AccessPolicy accessPolicy) { return new CodeScope(loader, accessPolicy, 4096); }
    public static CodeScope open(ClassLoader loader, AccessPolicy accessPolicy, int capacity) { return new CodeScope(loader, accessPolicy, capacity); }
    public AccessPolicy accessPolicy() { return accessPolicy; }
    public boolean usesLoader(ClassLoader candidate) { synchronized (lock) { return state == State.OPEN && loader == candidate; } }
    /**
     * One locked evaluation of {@code usesLoader(candidate) && identity().equals(expectedIdentity) && codeEpoch() == expectedEpoch}.
     * A null identity never matches.
     */
    public boolean isCurrent(ClassLoader candidate, String expectedIdentity, long expectedEpoch) {
        synchronized (lock) { return state == State.OPEN && loader == candidate && identity.equals(expectedIdentity) && codeEpoch == expectedEpoch; }
    }
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
        // A non-null memo was read before close/epoch cleared it, so this hit is ordered before that change.
        Recent last = recent;
        if (last != null && last.matches(query) && !Thread.currentThread().isInterrupted()) { last.hits.increment(); return last.result; }
        Context context;
        synchronized (lock) {
            if (state != State.OPEN) return failure(query, ResolutionStatus.STALE, "scope_closed");
            if (Thread.currentThread().isInterrupted()) return failure(query, ResolutionStatus.CANCELLED, "interrupted");
            resolved = true;
            ResolutionResult cached = cached(query);
            if (cached != null) { cacheHits++; return cached; }
            admission.incrementAndGet(); // OPEN under the lock, so CLOSING cannot be set concurrently
            context = context();
        }
        try { return (ResolutionResult) coordinate(context, query, query, () -> resolveUncached(query, context)); }
        finally { release(BASE); }
    }
    /**
     * Same lookup, statuses and receipt as {@link #resolve}, but returns raw members without creating bindings or
     * caching the result, so it never reports binding_capacity. A cached token result for an equal query is reused.
     * Each returned member is prepared like {@link #exportRaw}: under TRY_SUPPRESS_JAVA_CHECKS the first rejected
     * setAccessible throws IllegalAccessException. The members are an explicit ownership escape and survive close.
     */
    public RawResolution resolveRaw(QuerySpec query) throws IllegalAccessException {
        Objects.requireNonNull(query);
        Context context = null;
        RawResolution hit = null;
        synchronized (lock) {
            if (state != State.OPEN) return RawResolution.failed(failure(query, ResolutionStatus.STALE, "scope_closed"));
            if (Thread.currentThread().isInterrupted()) return RawResolution.failed(failure(query, ResolutionStatus.CANCELLED, "interrupted"));
            resolved = true;
            ResolutionResult cached = cached(query);
            admission.incrementAndGet(); // access preparation below still counts as in-flight work
            if (cached != null) { cacheHits++; hit = raw(cached); } else context = context();
        }
        try {
            RawResolution result = hit;
            if (result == null) {
                Context miss = context;
                Object value = coordinate(miss, new RawKey(query), query, () -> resolveUncachedRaw(query, miss));
                result = value instanceof RawResolution ? (RawResolution) value : RawResolution.failed((ResolutionResult) value);
            }
            for (Member member : result.members()) prepareAccess(member);
            return result;
        } finally { release(BASE); }
    }
    /** Caller holds the lock with the scope OPEN. */
    private Context context() {
        if (resolver == null) { resolver = new ReflectionResolver(capacity); coordinator = new QueryCoordinator<>(Math.min(capacity, 256)); }
        return new Context(loader, identity, codeEpoch, resolver, coordinator, providers, codeFingerprint);
    }
    /** Caller holds the lock; cached tokens always belong to the current bindings. */
    private RawResolution raw(ResolutionResult cached) {
        List<Member> members = new ArrayList<>(cached.members().size());
        for (ResolvedMember token : cached.members()) members.add(bindings.find(token).member);
        return new RawResolution(cached.status(), members, cached.receipt());
    }
    /** Single-flight miss shared by both result shapes; coordinator failures map to the same incomplete results. */
    private static Object coordinate(Context context, Object key, QuerySpec query, java.util.function.Supplier<Object> work) {
        try {
            return context.coordinator.execute(key, work, 2000, interrupted -> {
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
        }
    }
    private RawResolution resolveUncachedRaw(QuerySpec query, Context context) {
        synchronized (lock) {
            if (!current(context)) return RawResolution.failed(failure(query, ResolutionStatus.STALE, "scope_invalidated"));
            ResolutionResult cached = cached(query);
            if (cached != null) { cacheHits++; return raw(cached); }
        }
        ReflectionResolver.Outcome outcome = context.resolver.resolve(query, context.loader, context.providers, context.codeFingerprint);
        synchronized (lock) {
            // Nothing is published here; the lock orders the currency check against close and epoch changes.
            if (!current(context)) return RawResolution.failed(failure(query, ResolutionStatus.STALE, "scope_invalidated"));
            List<Member> members = new ArrayList<>(outcome.entries.size());
            List<MemberDescriptor> descriptors = new ArrayList<>(outcome.entries.size());
            for (ReflectionResolver.Entry entry : outcome.entries) { members.add(entry.member); descriptors.add(entry.descriptor); }
            return new RawResolution(outcome.status, members, new ResolutionReceipt(query.summary(), query.selection(), outcome.status, outcome.coverage, descriptors, outcome.reason, outcome.cause));
        }
    }
    private ResolutionResult resolveUncached(QuerySpec query, Context context) {
        synchronized (lock) {
            if (!current(context)) return failure(query, ResolutionStatus.STALE, "scope_invalidated");
            ResolutionResult cached = cached(query);
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
                cache.put(query, result); if (recent != null) recent = null;
                if (cache.size() > capacity) cache.remove(cache.keySet().iterator().next());
            }
            return result;
        }
    }
    /** Caller holds the lock. Every access-ordered hit moves the entry to the tail, so the memo follows it. */
    private ResolutionResult cached(QuerySpec query) {
        ResolutionResult cached = cache.get(query);
        if (cached != null) { Recent last = recent; if (last == null || last.result != cached) remember(query, cached); }
        return cached;
    }
    private void remember(QuerySpec query, ResolutionResult result) {
        if (recentHits == null) recentHits = new LongAdder();
        recent = new Recent(query, result, recentHits);
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
    /** {@code bindMethod(resolve(query).requireSingle())}: only a RESOLVED single result binds, otherwise ResolutionException. */
    public MethodPlan bindMethod(QuerySpec query) throws IllegalAccessException { return bindMethod(resolve(query).requireSingle()); }
    /** {@code bindField(resolve(query).requireSingle())}. */
    public FieldPlan bindField(QuerySpec query) throws IllegalAccessException { return bindField(resolve(query).requireSingle()); }
    /** {@code bindConstructor(resolve(query).requireSingle())}. */
    public ConstructorPlan bindConstructor(QuerySpec query) throws IllegalAccessException { return bindConstructor(resolve(query).requireSingle()); }
    private void prepare(ResolvedMember token, MemberKind kind) throws IllegalAccessException {
        if (token.descriptor().kind() != kind) throw new IllegalArgumentException("wrong member kind");
        Member member = bindings.find(token).member;
        int word = admit();
        try { prepareAccess(member); } finally { release(word); }
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
        Member member = bindings.find(token).member;
        int word = admit();
        try { prepareAccess(member); return member; } finally { release(word); }
    }
    /*
     * Lock-free admission. The token is checked against one bindings snapshot, then admit() counts the call in an
     * admission word whose CLOSING bit is clear. A snapshot read before admission cannot be a closed one: close sets
     * every CLOSING bit before replacing it. A call admitted with a snapshot read just before advanceCodeEpoch is
     * ordered before that epoch change.
     */
    public Object invokeMethod(ResolvedMember token, Object receiver, Object[] arguments) throws IllegalAccessException, InvocationTargetException {
        Member member = bindings.find(token).member;
        int word = admit();
        try { return ((Method) member).invoke(receiver, arguments); } finally { release(word); }
    }
    public Object getField(ResolvedMember token, Object receiver) throws IllegalAccessException {
        Member member = bindings.find(token).member;
        int word = admit();
        try { return ((Field) member).get(receiver); } finally { release(word); }
    }
    public void setField(ResolvedMember token, Object receiver, Object value) throws IllegalAccessException {
        Member member = bindings.find(token).member;
        int word = admit();
        try { ((Field) member).set(receiver, value); } finally { release(word); }
    }
    public Object construct(ResolvedMember token, Object[] arguments) throws IllegalAccessException, InvocationTargetException, InstantiationException {
        Member member = bindings.find(token).member;
        int word = admit();
        try { return ((Constructor<?>) member).newInstance(arguments); } finally { release(word); }
    }
    /** Uncontended path: one read and one CAS of the base word. Returns the admission word to release. */
    private int admit() {
        int value = admission.get();
        if ((value & (CLOSING | INFLATED)) == 0 && admission.compareAndSet(value, value + 1)) return BASE;
        return admitContended();
    }
    private int admitContended() {
        for (;;) {
            int value = admission.get();
            if (value < 0) throw stale();
            if ((value & INFLATED) != 0) return admitCell(cells);
            if (admission.compareAndSet(value, value + 1)) return BASE;
            inflate(); // a failed CAS is observed contention
        }
    }
    /**
     * Counts the call in this thread's cell. The caller has just read the base word open: close closes it before any
     * cell, so this call is linearized at that read (before close) even if its cell CAS lands later, and the cell's
     * own CLOSING bit then tells whether close has already taken that cell's count.
     */
    private int admitCell(AtomicIntegerArray table) {
        @SuppressWarnings("deprecation") // threadId() is JDK 19+; getId() is the same value and exists on Android
        int cell = (int) Thread.currentThread().getId() & (CELLS - 1);
        for (;;) {
            int index = (cell + 1) * STRIDE, value = table.get(index);
            if (value < 0) throw stale();
            if (table.compareAndSet(index, value, value + 1)) return cell + 1;
            cell = (cell + 1) & (CELLS - 1); // collided with another thread: probe the next cell
        }
    }
    /** Under the lock, so close sees either no cells or the final table; a closing scope never inflates. */
    private void inflate() {
        synchronized (lock) {
            // cells is written before INFLATED, so a caller that reads INFLATED also sees the table.
            if (cells == null && state == State.OPEN) { cells = new AtomicIntegerArray((CELLS + 2) * STRIDE); admission.getAndAdd(INFLATED); }
        }
    }
    private static StalePlanException stale() { return new StalePlanException("scope identity or epoch is stale"); }
    private static int cellCount(int processors) { return Math.min(64, Integer.highestOneBit(Math.max(1, processors - 1)) << 1); }
    public void advanceCodeEpoch(String reason) {
        if (reason == null || reason.isEmpty()) throw new IllegalArgumentException("invalidation reason");
        QueryCoordinator<Object, Object> oldCoordinator;
        synchronized (lock) {
            if (state != State.OPEN) throw new StalePlanException("scope closed");
            if (codeEpoch == Long.MAX_VALUE) { identity = newIdentity(); codeEpoch = 0; } else codeEpoch++;
            cache.clear(); if (recent != null) recent = null; memberSlots.clear(); bindings = new Bindings(identity, codeEpoch, nextSlot, capacity);
            if (resolver != null) resolver.clear();
            resolver = null; codeFingerprint = null;
            oldCoordinator = coordinator; coordinator = null;
        }
        if (oldCoordinator != null) oldCoordinator.close();
    }
    public Map<String, Long> diagnostics() {
        synchronized (lock) {
            Map<String, Long> values = new LinkedHashMap<>();
            values.put("cacheHits", cacheHits + (recentHits == null ? 0 : recentHits.sum())); values.put("cachedQueries", (long) cache.size()); values.put("bindings", (long) bindings.size);
            values.put("schemas", resolver == null ? 0L : (long) resolver.schemaSize()); values.put("parameterReads", resolver == null ? 0L : resolver.parameterReads());
            values.put("candidateChecks", resolver == null ? 0L : resolver.candidateChecks()); values.put("lookups", resolver == null ? 0L : resolver.lookups());
            values.put("inFlight", inFlight());
            return Collections.unmodifiableMap(values);
        }
    }
    /** Sum of all admission words; like LongAdder.sum(), exact when no call starts or ends during the read. */
    private long inFlight() {
        long total = admission.get() & COUNT;
        AtomicIntegerArray table = cells;
        if (table != null) for (int cell = 1; cell <= CELLS; cell++) total += table.get(cell * STRIDE) & COUNT;
        return total;
    }
    @Override public void close() {
        ProviderRegistry retired = null;
        QueryCoordinator<Object, Object> previous;
        synchronized (lock) {
            if (state != State.OPEN) return;
            AtomicIntegerArray table = cells;
            boolean drained;
            // Linearization point: the base word first, so no call can start after a rejection and still be admitted.
            if (table == null) drained = admission.getAndAdd(CLOSING) == 0;
            else {
                // Published to releasers by the CLOSING read-modify-writes below, which they observe before taking a share.
                table.lazySet(PENDING, CELLS + 2);
                int idle = (admission.getAndAdd(CLOSING) & COUNT) == 0 ? 1 : 0;
                for (int cell = 1; cell <= CELLS; cell++) if (table.getAndAdd(cell * STRIDE, CLOSING) == 0) idle++;
                // With every word idle no releaser can take a share, so close owns the hand-off without touching it.
                drained = idle == CELLS + 1 || table.addAndGet(PENDING, -(idle + 1)) == 0;
            }
            state = State.RETIRING; loader = null; bindings = Bindings.CLOSED; cache.clear(); if (recent != null) recent = null; memberSlots.clear(); codeFingerprint = null;
            if (resolver != null) resolver.clear();
            previous = coordinator;
            if (drained) { state = State.CLOSED; retired = providers; providers = null; }
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
    private void release(int word) {
        // Only the release that drains the last admitted work of a word after close sees CLOSING with a zero count.
        int value = word == BASE ? admission.decrementAndGet() : cells.decrementAndGet(word * STRIDE);
        if (value < 0 && (value & COUNT) == 0) drained();
    }
    /**
     * One admission word has drained after close; the last share of the hand-off closes the providers. Cells are
     * never created after close, so this sees the table close saw.
     */
    private void drained() {
        AtomicIntegerArray table = cells;
        if (table != null && table.decrementAndGet(PENDING) != 0) return;
        ProviderRegistry retired = null;
        synchronized (lock) {
            if (state == State.RETIRING) {
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
    private static final class Recent {
        final QuerySpec query;
        final ResolutionResult result;
        final LongAdder hits;
        Recent(QuerySpec query, ResolutionResult result, LongAdder hits) { this.query = query; this.result = result; this.hits = hits; }
        boolean matches(QuerySpec candidate) { return candidate == query || (candidate.hashCode() == query.hashCode() && candidate.equals(query)); }
    }
    /** Single-flight key for resolveRaw, distinct from the token flight of an equal query. */
    private static final class RawKey {
        final QuerySpec query;
        RawKey(QuerySpec query) { this.query = query; }
        @Override public boolean equals(Object other) { return other instanceof RawKey && query.equals(((RawKey) other).query); }
        @Override public int hashCode() { return ~query.hashCode(); }
    }
    private static final class Context {
        final ClassLoader loader;
        final String identity, codeFingerprint;
        final long epoch;
        final ReflectionResolver resolver;
        final QueryCoordinator<Object, Object> coordinator;
        final ProviderRegistry providers;
        Context(ClassLoader loader, String identity, long epoch, ReflectionResolver resolver, QueryCoordinator<Object, Object> coordinator, ProviderRegistry providers, String codeFingerprint) {
            this.loader = loader; this.identity = identity; this.epoch = epoch; this.resolver = resolver; this.coordinator = coordinator; this.providers = providers; this.codeFingerprint = codeFingerprint;
        }
    }
}
