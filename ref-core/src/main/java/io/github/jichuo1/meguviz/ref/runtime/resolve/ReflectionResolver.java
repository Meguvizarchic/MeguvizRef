package io.github.jichuo1.meguviz.ref.runtime.resolve;

import io.github.jichuo1.meguviz.ref.api.descriptor.*;
import io.github.jichuo1.meguviz.ref.api.query.*;
import io.github.jichuo1.meguviz.ref.api.result.*;
import io.github.jichuo1.meguviz.ref.spi.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Public reflection only. Candidate enumeration order is preserved for explicit FIRST. */
public final class ReflectionResolver {
    private final int schemaCapacity;
    private final Map<Member, MethodSchema> schemas = new LinkedHashMap<>(16, .75f, true);
    /**
     * Structural member tables by (Class identity, kind, public/declared), kept in first-enumeration order. LRU by
     * table; the retained Member references (an empty table counts as one) never exceed the scope capacity, and a
     * larger table is used once without being retained. Failed enumerations are never cached.
     */
    private final Map<TableKey, Member[]> tables = new LinkedHashMap<>(16, .75f, true);
    private int tableMembers;
    /**
     * The most recently used retained table (the LRU tail), set under this monitor by every table lookup or insert and
     * cleared with the tables. Serving it without touching the map leaves the eviction order unchanged, so a batch of
     * queries on one owner reuses the table without a key allocation or map lookup.
     */
    private volatile LastTable lastTable;
    /**
     * Positive cache of {@code Class.forName(name, false, loader)} for owner names and exact-descriptor types, keyed by
     * the initiating loader's identity and then the name (owner binary names and descriptor strings never collide:
     * owners cannot contain ';' or '['). HotSpot records a successful lookup in that loader's dictionary and ART in its
     * class table, so a later call with the same loader returns the same Class; a hit therefore equals a repeated
     * call, and one loader can never receive a Class looked up through another. Failures are never cached. Used from
     * the resolver's second lookup on, so a scope that resolves once allocates nothing for it. At most
     * {@code schemaCapacity} entries; dropped with the resolver (epoch, close).
     */
    private IdentityHashMap<ClassLoader, Map<String, Class<?>>> loaded;
    private int loadedTypes;
    /** Last coverage universe built by any resolver; immutable, so a racy read sees a complete value or another one. */
    private static Universe lastUniverse;
    private final AtomicLong parameterReads = new AtomicLong(), candidateChecks = new AtomicLong(), lookups = new AtomicLong();
    public ReflectionResolver(int schemaCapacity) { this.schemaCapacity = schemaCapacity; }
    public long parameterReads() { return parameterReads.get(); }
    public long candidateChecks() { return candidateChecks.get(); }
    public long lookups() { return lookups.get(); }
    public synchronized int schemaSize() { return schemas.size(); }
    public synchronized void clear() { schemas.clear(); tables.clear(); tableMembers = 0; lastTable = null; loaded = null; loadedTypes = 0; }
    private synchronized Class<?> loadedType(ClassLoader loader, String name) {
        Map<String, Class<?>> names = loaded == null ? null : loaded.get(loader);
        return names == null ? null : names.get(name);
    }
    private synchronized void rememberType(ClassLoader loader, String name, Class<?> type) {
        if (loadedTypes >= schemaCapacity) return;
        if (loaded == null) loaded = new IdentityHashMap<>();
        Map<String, Class<?>> names = loaded.get(loader);
        if (names == null) loaded.put(loader, names = new HashMap<>());
        if (names.putIfAbsent(name, type) == null) loadedTypes++;
    }
    /** {@code Class.forName(name, false, loader)} through the per-scope cache. */
    private Class<?> loadOwner(String name, ClassLoader loader) throws ClassNotFoundException {
        if (lookups.get() < 2) return Class.forName(name, false, loader);
        Class<?> type = loadedType(loader, name);
        if (type == null) { type = Class.forName(name, false, loader); rememberType(loader, name, type); }
        return type;
    }
    /** Fills the parameter types and returns the return/field type, through the cache when {@code reuse}. */
    private Class<?> resolveTypes(MemberDescriptor d, ClassLoader loader, Class<?>[] parameters, boolean reuse) throws ClassNotFoundException {
        if (!reuse) {
            for (int i = 0; i < parameters.length; i++) parameters[i] = d.parameters().get(i).resolve(loader);
            return d.type().resolve(loader);
        }
        for (int i = 0; i < parameters.length; i++) parameters[i] = resolveType(d.parameters().get(i), loader);
        return resolveType(d.type(), loader);
    }
    /** {@code descriptor.resolve(loader)} through the per-scope cache; primitives never reach the cache. */
    private Class<?> resolveType(TypeDescriptor descriptor, ClassLoader loader) throws ClassNotFoundException {
        String value = descriptor.value();
        if (value.length() == 1) return descriptor.resolve(loader);
        Class<?> type = loadedType(loader, value);
        if (type == null) { type = descriptor.resolve(loader); rememberType(loader, value, type); }
        return type;
    }
    private synchronized MethodSchema schema(Member member) {
        MethodSchema cached = schemas.get(member);
        if (cached != null) return cached;
        MethodSchema result = new MethodSchema(member);
        if (!(member instanceof Field)) parameterReads.incrementAndGet();
        if (schemaCapacity > 0) {
            schemas.put(member, result);
            if (schemas.size() > schemaCapacity) schemas.remove(schemas.keySet().iterator().next());
        }
        return result;
    }
    public Outcome resolve(QuerySpec query, ClassLoader loader, ProviderRegistry registry, String codeFingerprint) {
        lookups.incrementAndGet();
        if (Thread.currentThread().isInterrupted()) return failure(ResolutionStatus.CANCELLED, query, "interrupted", null);
        try {
            Class<?> owner = query.runtimeOwner() == null ? loadOwner(query.owner(), loader) : query.runtimeOwner();
            if (query.providerId() != null) return provider(query, owner, loader, registry, codeFingerprint);
            if (query.exactDescriptor() != null) return exact(query, owner, loader);
            return structural(query, owner);
        } catch (ClassNotFoundException e) { return failure(ResolutionStatus.UNAVAILABLE, query, "owner_type_unavailable", e); }
        catch (LinkageError e) { return failure(ResolutionStatus.PARTIAL, query, "signature_linkage_failure", e); }
        catch (SecurityException e) { return failure(ResolutionStatus.ACCESS_DENIED, query, "query_access_denied", e); }
    }
    private Outcome exact(QuerySpec query, Class<?> owner, ClassLoader scopeLoader) throws ClassNotFoundException {
        MemberDescriptor d = query.exactDescriptor();
        ClassLoader loader;
        try { loader = owner.getClassLoader(); }
        catch (SecurityException denied) { loader = scopeLoader; }
        Class<?>[] parameters = new Class<?>[d.parameters().size()];
        Class<?> type;
        boolean reuse = lookups.get() > 1; // the first lookup of a resolver takes the direct path below
        try { type = resolveTypes(d, loader, parameters, reuse); }
        catch (SecurityException denied) {
            if (loader == scopeLoader) throw denied;
            type = resolveTypes(d, scopeLoader, parameters, reuse);
        }
        try {
            Member member;
            if (d.kind() == MemberKind.METHOD) {
                Method method = owner.getDeclaredMethod(d.name(), parameters);
                if (method.getReturnType() != type) {
                    // A covariant bridge can share name and arguments. Search only the declared domain.
                    int inspected = 0;
                    for (Method candidate : owner.getDeclaredMethods()) {
                        if (++inspected > query.candidateBudget()) return failure(ResolutionStatus.PARTIAL, query, "candidate_budget", null);
                        if (candidate.getName().equals(d.name()) && candidate.getReturnType() == type && Arrays.equals(candidate.getParameterTypes(), parameters)) return Outcome.single(candidate, d, query, inspected);
                    }
                    return failure(ResolutionStatus.UNAVAILABLE, query, "not_found_or_filtered", null);
                }
                member = method;
            } else if (d.kind() == MemberKind.FIELD) {
                Field field = owner.getDeclaredField(d.name());
                if (field.getType() != type) return failure(ResolutionStatus.UNAVAILABLE, query, "field_type_mismatch", null);
                member = field;
            } else member = owner.getDeclaredConstructor(parameters);
            return Outcome.single(member, d, query, 1);
        } catch (NoSuchMethodException | NoSuchFieldException e) { return failure(ResolutionStatus.UNAVAILABLE, query, "not_found_or_filtered", e); }
    }
    private Outcome structural(QuerySpec query, Class<?> owner) {
        int[] inspectedCount = new int[1];
        try { return structural(query, owner, inspectedCount); }
        finally { candidateChecks.addAndGet(inspectedCount[0]); }
    }
    /**
     * The walked domain has no equal Members: one declared table per distinct class (Member equality includes the
     * declaring class, and a class file cannot declare the same name and descriptor twice) or a single getMethods/
     * getFields table, which the JDK and ART deduplicate. Only accepted matches keep a defensive set, created from the
     * second match on, so the scope's one-slot-per-Member commit never sees duplicates.
     */
    private Outcome structural(QuerySpec query, Class<?> owner, int[] inspectedCount) {
        List<Entry> matches = new ArrayList<>();
        Set<Member> accepted = null;
        int inspected = 0, depth = 0;
        boolean complete = true;
        String reason = "complete_visible_universe";
        Throwable cause = null;
        Class<?>[] parameters = query.parameterClasses(), arguments = query.argumentClasses();
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            if (++depth > query.depthLimit()) { complete = false; reason = "hierarchy_budget"; break; }
            Member[] members;
            try { members = table(current, query); }
            catch (LinkageError e) { complete = false; reason = "signature_linkage_failure"; cause = e; break; }
            catch (SecurityException e) { complete = false; reason = "query_access_denied"; cause = e; break; }
            for (Member member : members) {
                if (Thread.currentThread().isInterrupted()) return outcome(ResolutionStatus.CANCELLED, query, matches, false, inspected, "interrupted", null);
                if (inspected >= query.candidateBudget()) return select(query, matches, false, inspected, "candidate_budget", cause);
                inspectedCount[0] = ++inspected;
                try {
                    if (!cheapMatches(member, query)) continue;
                    MethodSchema schema = schema(member);
                    if (!matches(schema, query, parameters, arguments)) continue;
                    if (!matches.isEmpty()) {
                        if (accepted == null) { accepted = new HashSet<>(); for (Entry match : matches) accepted.add(match.member); }
                        if (!accepted.add(member)) continue;
                    }
                    matches.add(new Entry(member, schema.descriptor()));
                } catch (LinkageError e) { complete = false; reason = "candidate_signature_unknown"; cause = e; continue; }
                if (query.selection() == Selection.FIRST) return select(query, matches, false, inspected, complete ? "first_in_reflection_order" : reason, cause);
                if (query.selection() == Selection.UNIQUE && matches.size() == 2) return select(query, matches, false, inspected, "multiple_matches", cause);
                if (query.selection() == Selection.ALL && matches.size() == query.resultLimit()) {
                    // Reaching a limit is conservatively partial, even if it happened to be the last match.
                    return select(query, matches, false, inspected, "result_limit", cause);
                }
            }
            if (query.hierarchy() != Hierarchy.SUPERCLASSES) break;
        }
        if (Thread.currentThread().isInterrupted()) return outcome(ResolutionStatus.CANCELLED, query, matches, false, inspected, "interrupted", null);
        return select(query, matches, complete, inspected, reason, cause);
    }
    /** Cached structural enumeration; the arrays are never mutated or handed to callers. */
    private Member[] table(Class<?> owner, QuerySpec query) {
        int shape = TableKey.shape(query.kind(), query.kind() != MemberKind.CONSTRUCTOR && query.hierarchy() == Hierarchy.JAVA_PUBLIC);
        LastTable last = lastTable;
        if (last != null && last.owner == owner && last.shape == shape) return last.members;
        TableKey key = new TableKey(owner, shape);
        synchronized (this) {
            Member[] cached = tables.get(key);
            if (cached != null) { lastTable = new LastTable(owner, shape, cached); return cached; }
        }
        Member[] fresh = members(owner, query);
        int cost = Math.max(1, fresh.length);
        if (cost > schemaCapacity) return fresh;
        synchronized (this) {
            Member[] raced = tables.get(key);
            if (raced != null) { lastTable = new LastTable(owner, shape, raced); return raced; }
            tables.put(key, fresh); tableMembers += cost;
            // The new table is the tail and fits the capacity alone, so eviction never removes it.
            if (tableMembers > schemaCapacity) {
                for (Iterator<Member[]> eldest = tables.values().iterator(); tableMembers > schemaCapacity; ) {
                    tableMembers -= Math.max(1, eldest.next().length); eldest.remove();
                }
            }
            lastTable = new LastTable(owner, shape, fresh);
            return fresh;
        }
    }
    /** Direct enumeration: a fresh copy on every call (provider domains and the verifier keep using it). */
    private Member[] members(Class<?> owner, QuerySpec query) {
        boolean publicLookup = query.hierarchy() == Hierarchy.JAVA_PUBLIC;
        switch (query.kind()) {
            case METHOD: return publicLookup ? owner.getMethods() : owner.getDeclaredMethods();
            case FIELD: return publicLookup ? owner.getFields() : owner.getDeclaredFields();
            case CONSTRUCTOR: return owner.getDeclaredConstructors();
            default: throw new AssertionError(query.kind());
        }
    }
    private boolean cheapMatches(Member member, QuerySpec query) {
        String name = member instanceof Constructor<?> ? "<init>" : member.getName();
        if (query.name() != null && !query.name().equals(name)) return false;
        if (query.staticOnly() != null && query.staticOnly() != Modifier.isStatic(member.getModifiers())) return false;
        if (query.parameterCount() >= 0) {
            int count = member instanceof Method ? ((Method) member).getParameterCount() : member instanceof Constructor<?> ? ((Constructor<?>) member).getParameterCount() : 0;
            if (query.parameterCount() != count) return false;
        }
        if (query.synthetic() != null && query.synthetic() != member.isSynthetic()) return false;
        return query.bridge() == null || query.bridge() == (member instanceof Method && ((Method) member).isBridge());
    }
    private boolean matches(MethodSchema schema, QuerySpec query, Class<?>[] parameters, Class<?>[] arguments) {
        if (parameters != null && !schema.parametersEqual(parameters)) return false;
        if (arguments != null && !schema.argumentsMatch(arguments, query.compatibility())) return false;
        return query.returnClass() == null || query.returnClass() == schema.type();
    }
    private Outcome provider(QuerySpec query, Class<?> owner, ClassLoader loader, ProviderRegistry registry, String fingerprint) {
        int[] inspectedCount = new int[1];
        try { return provider(query, owner, loader, registry, fingerprint, inspectedCount); }
        finally { candidateChecks.addAndGet(inspectedCount[0]); }
    }
    private Outcome provider(QuerySpec query, Class<?> owner, ClassLoader loader, ProviderRegistry registry, String fingerprint, int[] inspectedCount) {
        if (registry == null || fingerprint == null) return failure(ResolutionStatus.UNAVAILABLE, query, "provider_or_code_identity_missing", null);
        CandidateProvider provider = registry.provider(query.providerId());
        if (provider == null) return failure(ResolutionStatus.UNAVAILABLE, query, "provider_not_installed", null);
        CandidateBatch batch;
        try { batch = Objects.requireNonNull(provider.discover(new CandidateRequest(query, fingerprint)), "provider batch"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return failure(ResolutionStatus.CANCELLED, query, "provider_interrupted", e); }
        catch (java.util.concurrent.CancellationException e) { return failure(ResolutionStatus.CANCELLED, query, "provider_cancelled", e); }
        catch (Exception e) { return failure(ResolutionStatus.PARTIAL, query, "provider_failed", e); }
        if (Thread.currentThread().isInterrupted()) return failure(ResolutionStatus.CANCELLED, query, "interrupted", null);
        if (!fingerprint.equals(batch.codeFingerprint())) return failure(ResolutionStatus.STALE, query, "provider_code_identity_mismatch", null);
        List<Entry> matches = new ArrayList<>(); Set<Member> seen = new HashSet<>();
        Set<Member> publicDomain = query.hierarchy() == Hierarchy.JAVA_PUBLIC && !batch.candidates().isEmpty()
                ? new HashSet<>(Arrays.asList(members(owner, query))) : null;
        Map<String, Map<MemberDescriptor, Member>> publicIndexes = publicDomain == null ? null : new HashMap<>();
        int inspected = 0; boolean complete = batch.complete(); String reason = complete ? "provider_claim_runtime_verified" : "provider_partial"; Throwable cause = null;
        Class<?>[] parameters = query.parameterClasses(), arguments = query.argumentClasses();
        for (MemberDescriptor descriptor : batch.candidates()) {
            if (Thread.currentThread().isInterrupted()) return outcome(ResolutionStatus.CANCELLED, query, matches, false, inspected, "interrupted", null);
            if (inspected >= query.candidateBudget()) { complete = false; reason = "candidate_budget"; break; }
            inspectedCount[0] = ++inspected;
            Outcome verified = resolve(QuerySpec.exact(descriptor), loader, null, null);
            Entry entry = verified.status == ResolutionStatus.RESOLVED ? verified.entries.get(0) : null;
            boolean inDomain = entry != null && inDomain(owner, entry.member.getDeclaringClass(), query)
                    && (publicDomain == null || Modifier.isPublic(entry.member.getModifiers()));
            if (!inDomain || (descriptor.kind() == query.kind() && publicDomain != null && !publicDomain.contains(entry.member))) {
                Entry actual = domainEntry(query, owner, loader, descriptor, publicDomain, publicIndexes);
                if (actual == null) { complete = false; reason = entry == null ? "provider_candidate_unavailable" : "provider_candidate_outside_domain"; continue; }
                entry = actual;
            }
            if (descriptor.kind() != query.kind() || !seen.add(entry.member)) continue;
            if (query.exactDescriptor() != null && !query.exactDescriptor().equals(descriptor)) continue;
            if (!cheapMatches(entry.member, query)) continue;
            MethodSchema schema = schema(entry.member);
            if (matches(schema, query, parameters, arguments)) matches.add(entry);
            if (query.selection() == Selection.UNIQUE && matches.size() >= 2) {
                if (complete) reason = "multiple_matches";
                complete = false;
                break;
            }
            if (matches.size() >= query.resultLimit()) { complete = false; reason = "result_limit"; break; }
        }
        if (complete && !(query.selection() == Selection.UNIQUE && matches.size() > 1)) {
            Set<Member> provided = new HashSet<>();
            for (Entry match : matches) provided.add(match.member);
            ProviderCoverageVerifier.Verification verification = ProviderCoverageVerifier.verify(query, owner, provided);
            complete = verification != null;
            reason = complete ? "provider_claim_independently_verified_java_query" : "provider_coverage_not_proven";
            if (complete && query.selection() == Selection.FIRST && verification.first != null) {
                for (int i = 0; i < matches.size(); i++) {
                    if (matches.get(i).member.equals(verification.first)) { Collections.swap(matches, 0, i); break; }
                }
            }
        }
        if (Thread.currentThread().isInterrupted()) return outcome(ResolutionStatus.CANCELLED, query, matches, false, inspected, "interrupted", null);
        ProviderDescriptor providerDescriptor = provider.descriptor();
        if (Thread.currentThread().isInterrupted()) return outcome(ResolutionStatus.CANCELLED, query, matches, false, inspected, "interrupted", null);
        Outcome result = select(query, matches, complete, inspected, reason, cause);
        return Outcome.trusted(result.status, result.entries, new Coverage("provider:" + providerDescriptor.id() + ":" + batch.universe(), result.coverage.complete(), inspected, reason), reason, result.cause);
    }
    /** Resolve a shadowed declaring owner only within the actual requested domain. */
    private Entry domainEntry(QuerySpec query, Class<?> owner, ClassLoader loader, MemberDescriptor descriptor,
                              Set<Member> publicDomain, Map<String, Map<MemberDescriptor, Member>> publicIndexes) {
        if (descriptor.kind() != query.kind()) return null;
        if (publicDomain != null) {
            if (Thread.currentThread().isInterrupted() || publicDomain.size() > query.candidateBudget()) return null;
            Map<MemberDescriptor, Member> index = publicIndexes.get(descriptor.owner());
            if (index == null) {
                index = new HashMap<>();
                for (Member member : publicDomain) {
                    if (Thread.currentThread().isInterrupted()) return null;
                    if (!member.getDeclaringClass().getName().equals(descriptor.owner())) continue;
                    MemberDescriptor key = MemberDescriptor.from(member);
                    if (!index.containsKey(key)) index.put(key, member);
                    else if (!member.equals(index.get(key))) index.put(key, null);
                }
                publicIndexes.put(descriptor.owner(), index);
            }
            Member found = index.get(descriptor);
            return found == null ? null : new Entry(found, descriptor);
        }
        Class<?> declaring = null; int depth = 0;
        for (Class<?> current = owner; current != null && depth++ < query.depthLimit(); current = current.getSuperclass()) {
            if (current.getName().equals(descriptor.owner())) {
                if (declaring != null && declaring != current) return null;
                declaring = current;
            }
            if (query.hierarchy() != Hierarchy.SUPERCLASSES) break;
        }
        if (declaring == null) return null;
        QuerySpec exactQuery = QuerySpec.exact(descriptor);
        lookups.incrementAndGet();
        try {
            Outcome verified = exact(exactQuery, declaring, loader);
            return verified.status == ResolutionStatus.RESOLVED ? verified.entries.get(0) : null;
        } catch (ClassNotFoundException | LinkageError | SecurityException failure) { return null; }
    }
    private boolean inDomain(Class<?> requested, Class<?> declaring, QuerySpec query) {
        if (query.hierarchy() == Hierarchy.JAVA_PUBLIC) return declaring.isAssignableFrom(requested);
        int depth = 0;
        for (Class<?> current = requested; current != null && depth++ < query.depthLimit(); current = current.getSuperclass()) {
            if (current == declaring) return true;
            if (query.hierarchy() != Hierarchy.SUPERCLASSES) break;
        }
        return false;
    }
    private Outcome select(QuerySpec query, List<Entry> matches, boolean complete, int inspected, String reason, Throwable cause) {
        ResolutionStatus status;
        if (query.selection() == Selection.UNIQUE && matches.size() > 1) status = ResolutionStatus.AMBIGUOUS;
        else if (query.selection() == Selection.FIRST && !matches.isEmpty() && reason.equals("first_in_reflection_order")) status = ResolutionStatus.RESOLVED;
        else if (!complete) status = ResolutionStatus.PARTIAL;
        else if (matches.isEmpty()) status = ResolutionStatus.NOT_FOUND_IN_VISIBLE_UNIVERSE;
        else status = query.selection() == Selection.ALL ? ResolutionStatus.ALL_MATCHES : ResolutionStatus.RESOLVED;
        if (status == ResolutionStatus.RESOLVED && query.selection() == Selection.FIRST && matches.size() > 1) matches = Collections.singletonList(matches.get(0));
        return outcome(status, query, matches, complete, inspected, reason, cause);
    }
    private static Outcome failure(ResolutionStatus status, QuerySpec query, String reason, Throwable cause) { return outcome(status, query, Collections.emptyList(), false, 0, reason, cause); }
    private static Outcome outcome(ResolutionStatus status, QuerySpec query, List<Entry> matches, boolean complete, int inspected, String reason, Throwable cause) {
        return Outcome.trusted(status, matches, new Coverage(universe(query), complete, inspected, reason), reason, cause == null ? null : cause.getClass().getName());
    }
    /** {@code "java-visible:" + owner + ":" + hierarchy}, reused while consecutive outcomes share owner and hierarchy. */
    private static String universe(QuerySpec query) {
        String owner = query.owner();
        Hierarchy hierarchy = query.hierarchy();
        Universe last = lastUniverse;
        if (last != null && last.hierarchy == hierarchy && (last.owner == owner || last.owner.equals(owner))) return last.text;
        String text = "java-visible:" + owner + ":" + hierarchy;
        lastUniverse = new Universe(owner, hierarchy, text);
        return text;
    }
    private static final class Universe {
        final String owner, text;
        final Hierarchy hierarchy;
        Universe(String owner, Hierarchy hierarchy, String text) { this.owner = owner; this.hierarchy = hierarchy; this.text = text; }
    }
    private static final class LastTable {
        final Class<?> owner;
        final int shape;
        final Member[] members;
        LastTable(Class<?> owner, int shape, Member[] members) { this.owner = owner; this.shape = shape; this.members = members; }
    }
    private static final class TableKey {
        final Class<?> owner;
        final int shape;
        TableKey(Class<?> owner, int shape) { this.owner = owner; this.shape = shape; }
        static int shape(MemberKind kind, boolean publicLookup) { return kind.ordinal() * 2 + (publicLookup ? 1 : 0); }
        @Override public boolean equals(Object other) { return other instanceof TableKey && ((TableKey) other).owner == owner && ((TableKey) other).shape == shape; }
        @Override public int hashCode() { return owner.hashCode() * 31 + shape; }
    }
    public static final class Entry {
        public final Member member;
        public final MemberDescriptor descriptor;
        public Entry(Member member, MemberDescriptor descriptor) { this.member = member; this.descriptor = descriptor; }
    }
    public static final class Outcome {
        public final ResolutionStatus status;
        public final List<Entry> entries;
        public final Coverage coverage;
        public final String reason, cause;
        public Outcome(ResolutionStatus status, List<Entry> entries, Coverage coverage, String reason, String cause) {
            this(status, Collections.unmodifiableList(new ArrayList<>(entries)), coverage, reason, cause, true);
        }
        private Outcome(ResolutionStatus status, List<Entry> entries, Coverage coverage, String reason, String cause, boolean owned) {
            this.status = status; this.entries = entries; this.coverage = coverage; this.reason = reason; this.cause = cause;
        }
        /** Internal lists are never mutated after an outcome is built, so they are wrapped instead of copied. */
        private static Outcome trusted(ResolutionStatus status, List<Entry> entries, Coverage coverage, String reason, String cause) {
            return new Outcome(status, Collections.unmodifiableList(entries), coverage, reason, cause, true);
        }
        static Outcome single(Member member, MemberDescriptor descriptor, QuerySpec query, int inspected) {
            return outcome(ResolutionStatus.RESOLVED, query, Collections.singletonList(new Entry(member, descriptor)), true, inspected, "exact_runtime_verified", null);
        }
    }
}
