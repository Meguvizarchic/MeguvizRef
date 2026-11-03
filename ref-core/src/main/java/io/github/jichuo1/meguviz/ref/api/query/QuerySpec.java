package io.github.jichuo1.meguviz.ref.api.query;

import io.github.jichuo1.meguviz.ref.api.descriptor.*;
import java.util.*;

/** Immutable query. Class-valued builders are explicitly runtime-only. No receiver or argument values are retained. */
public final class QuerySpec {
    public static final int SCHEMA_MAJOR = 1;
    public static final String RULE_VERSION = "mvx-ref-1";
    private final String owner, name;
    private final Class<?> runtimeOwner, returnClass;
    private final Class<?>[] parameterClasses, argumentClasses;
    private final MemberKind kind;
    private final MemberDescriptor exact;
    private final Hierarchy hierarchy;
    private final Selection selection;
    private final CompatibilityPolicy compatibility;
    private final Boolean staticOnly, bridge, synthetic;
    private final int parameterCount, candidateBudget, resultLimit, depthLimit;
    private final String providerId;
    private final int hash;
    /** Memoized summary(); racy single-check of an immutable String. */
    private String summary;
    private QuerySpec(Builder b) {
        owner = b.owner; runtimeOwner = b.runtimeOwner; kind = b.kind; name = b.name;
        parameterClasses = b.parameterClasses == null ? null : b.parameterClasses.clone();
        argumentClasses = b.argumentClasses == null ? null : b.argumentClasses.clone();
        returnClass = b.returnClass; exact = b.exact; hierarchy = b.hierarchy; selection = b.selection;
        compatibility = b.compatibility; staticOnly = b.staticOnly; bridge = b.bridge; synthetic = b.synthetic;
        parameterCount = b.parameterCount; candidateBudget = b.candidateBudget; resultLimit = b.resultLimit; depthLimit = b.depthLimit; providerId = b.providerId;
        if (selection == Selection.EXACT && exact == null) throw new IllegalArgumentException("EXACT requires a complete portable descriptor");
        if (kind == MemberKind.CONSTRUCTOR && hierarchy != Hierarchy.DECLARED) throw new IllegalArgumentException("constructors are not inherited");
        // Same values and order as Objects.hash, without its varargs array and integer boxing.
        int value = 1;
        value = 31 * value + Objects.hashCode(owner);
        value = 31 * value + Objects.hashCode(name);
        value = 31 * value + Objects.hashCode(kind);
        value = 31 * value + Objects.hashCode(exact);
        value = 31 * value + Objects.hashCode(hierarchy);
        value = 31 * value + Objects.hashCode(selection);
        value = 31 * value + Objects.hashCode(compatibility);
        value = 31 * value + Objects.hashCode(staticOnly);
        value = 31 * value + Objects.hashCode(bridge);
        value = 31 * value + Objects.hashCode(synthetic);
        value = 31 * value + parameterCount;
        value = 31 * value + candidateBudget;
        value = 31 * value + resultLimit;
        value = 31 * value + depthLimit;
        value = 31 * value + Objects.hashCode(providerId);
        value = 31 * value + Objects.hashCode(runtimeOwner);
        value = 31 * value + Objects.hashCode(returnClass);
        value = 31 * value + Arrays.hashCode(parameterClasses);
        value = 31 * value + Arrays.hashCode(argumentClasses);
        hash = value;
    }
    public static QuerySpec exact(MemberDescriptor descriptor) {
        return new Builder(descriptor.owner(), null, descriptor.kind()).exact(descriptor).build();
    }
    public static Builder builder(Class<?> owner, MemberKind kind) { return new Builder(owner.getName(), owner, kind); }
    /** {@code builder(owner, METHOD).name(name).parameters(parameters).build()}: UNIQUE, DECLARED, exact parameter Class identities. */
    public static QuerySpec method(Class<?> owner, String name, Class<?>... parameters) { return builder(owner, MemberKind.METHOD).name(name).parameters(parameters).build(); }
    /** {@code builder(owner, FIELD).name(name).build()}: UNIQUE, DECLARED. */
    public static QuerySpec field(Class<?> owner, String name) { return builder(owner, MemberKind.FIELD).name(name).build(); }
    /** {@code builder(owner, CONSTRUCTOR).parameters(parameters).build()}: UNIQUE, exact parameter Class identities. */
    public static QuerySpec constructor(Class<?> owner, Class<?>... parameters) { return builder(owner, MemberKind.CONSTRUCTOR).parameters(parameters).build(); }
    public static Builder builder(String owner, MemberKind kind) { return new Builder(owner, null, kind); }
    public String owner() { return owner; }
    public String name() { return name; }
    public Class<?> runtimeOwner() { return runtimeOwner; }
    public MemberKind kind() { return kind; }
    public MemberDescriptor exactDescriptor() { return exact; }
    public Hierarchy hierarchy() { return hierarchy; }
    public Selection selection() { return selection; }
    public CompatibilityPolicy compatibility() { return compatibility; }
    public Boolean staticOnly() { return staticOnly; }
    public Boolean bridge() { return bridge; }
    public Boolean synthetic() { return synthetic; }
    public int parameterCount() { return parameterCount; }
    public int candidateBudget() { return candidateBudget; }
    public int resultLimit() { return resultLimit; }
    public int depthLimit() { return depthLimit; }
    public String providerId() { return providerId; }
    public Class<?>[] parameterClasses() { return parameterClasses == null ? null : parameterClasses.clone(); }
    public Class<?>[] argumentClasses() { return argumentClasses == null ? null : argumentClasses.clone(); }
    public Class<?> returnClass() { return returnClass; }
    public boolean portable() { return runtimeOwner == null && parameterClasses == null && argumentClasses == null && returnClass == null; }
    /** Descriptive value only, never a runtime cache key (Class identity is significant). */
    public String summary() {
        String value = summary;
        if (value == null) summary = value = describe();
        return value;
    }
    private String describe() { return RULE_VERSION + ":" + kind + ":" + owner + ":" + name + ":" + selection + ":" + hierarchy + ":" + exact + ":" + staticOnly + ":" + bridge + ":" + synthetic + ":" + parameterCount + ":" + candidateBudget + ":" + resultLimit + ":" + depthLimit + ":" + compatibility + ":" + Arrays.toString(parameterClasses) + ":" + Arrays.toString(argumentClasses) + ":" + returnClass + ":" + providerId; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof QuerySpec)) return false;
        QuerySpec q = (QuerySpec) other;
        return runtimeOwner == q.runtimeOwner && returnClass == q.returnClass && Arrays.equals(parameterClasses, q.parameterClasses) && Arrays.equals(argumentClasses, q.argumentClasses)
                && owner.equals(q.owner) && Objects.equals(name, q.name) && kind == q.kind && Objects.equals(exact, q.exact)
                && hierarchy == q.hierarchy && selection == q.selection && compatibility == q.compatibility
                && Objects.equals(staticOnly, q.staticOnly) && Objects.equals(bridge, q.bridge) && Objects.equals(synthetic, q.synthetic)
                && parameterCount == q.parameterCount && candidateBudget == q.candidateBudget && resultLimit == q.resultLimit
                && depthLimit == q.depthLimit && Objects.equals(providerId, q.providerId);
    }
    @Override public int hashCode() { return hash; }
    public static final class Builder {
        private final String owner;
        private final Class<?> runtimeOwner;
        private final MemberKind kind;
        private String name, providerId;
        private Class<?> returnClass;
        private Class<?>[] parameterClasses, argumentClasses;
        private MemberDescriptor exact;
        private Hierarchy hierarchy = Hierarchy.DECLARED;
        private Selection selection = Selection.UNIQUE;
        private CompatibilityPolicy compatibility;
        private Boolean staticOnly, bridge, synthetic;
        private int parameterCount = -1, candidateBudget = 16384, resultLimit = 4096, depthLimit = 256;
        private Builder(String owner, Class<?> runtimeOwner, MemberKind kind) {
            validateOwner(Objects.requireNonNull(owner));
            this.owner = owner.replace('/', '.'); // same result as the former substring/replace round trip
            this.runtimeOwner = runtimeOwner; this.kind = Objects.requireNonNull(kind);
        }
        private Builder exact(MemberDescriptor descriptor) { exact = descriptor; name = descriptor.name(); selection = Selection.EXACT; return this; }
        public Builder name(String value) { if (value == null || value.length() > 1024 || value.isEmpty()) throw new IllegalArgumentException("name"); name = value; return this; }
        public Builder parameters(Class<?>... values) {
            if (values.length > 255) throw new IllegalArgumentException("parameters");
            for (Class<?> value : values) if (value == null || value == void.class) throw new IllegalArgumentException("parameter type");
            parameterClasses = values.clone(); parameterCount = values.length; return this;
        }
        public Builder arguments(CompatibilityPolicy policy, Object... arguments) {
            if (arguments.length > 255) throw new IllegalArgumentException("arguments");
            compatibility = Objects.requireNonNull(policy); argumentClasses = new Class<?>[arguments.length];
            for (int i = 0; i < arguments.length; i++) argumentClasses[i] = arguments[i] == null ? null : arguments[i].getClass();
            parameterCount = arguments.length; return this;
        }
        public Builder returns(Class<?> type) { returnClass = Objects.requireNonNull(type); return this; }
        public Builder hierarchy(Hierarchy value) { hierarchy = Objects.requireNonNull(value); return this; }
        public Builder selection(Selection value) { selection = Objects.requireNonNull(value); return this; }
        public Builder staticOnly(boolean value) { staticOnly = value; return this; }
        public Builder bridge(boolean value) { bridge = value; return this; }
        public Builder synthetic(boolean value) { synthetic = value; return this; }
        public Builder parameterCount(int count) { if (count < 0 || count > 255) throw new IllegalArgumentException("arity"); parameterCount = count; return this; }
        public Builder candidateBudget(int count) { if (count < 1 || count > 1000000) throw new IllegalArgumentException("budget"); candidateBudget = count; return this; }
        public Builder resultLimit(int count) { if (count < 1 || count > 1000000) throw new IllegalArgumentException("limit"); resultLimit = count; return this; }
        public Builder depthLimit(int count) { if (count < 1 || count > 1024) throw new IllegalArgumentException("depth"); depthLimit = count; return this; }
        public Builder provider(String id) { if (id == null || id.isEmpty() || id.length() > 256) throw new IllegalArgumentException("provider ID"); providerId = id; return this; }
        public QuerySpec build() { return new QuerySpec(this); }
        /**
         * Same checks and messages as {@code TypeDescriptor.of("L" + owner.replace('.', '/') + ";")} without building
         * the string (a copy of TypeDescriptor's package-private validator; both are tested against the 1.0.0 oracle).
         */
        private static void validateOwner(String owner) {
            int length = owner.length();
            if (length > 4094) throw new IllegalArgumentException("descriptor length");
            if (length == 0) throw new IllegalArgumentException("invalid descriptor: L;");
            boolean previousSeparator = true;
            for (int i = 0; i < length; i++) {
                char c = owner.charAt(i);
                boolean separator = c == '.' || c == '/';
                if ((separator && previousSeparator) || c == ';' || c == '[' || c == '(' || c == ')' || Character.isWhitespace(c)) throw new IllegalArgumentException("reference name");
                previousSeparator = separator;
            }
            if (previousSeparator) throw new IllegalArgumentException("reference name");
        }
    }
}
