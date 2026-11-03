package io.github.jichuo1.meguviz.ref.api.descriptor;

import java.lang.reflect.*;
import java.util.*;

/** Portable identity includes the declaring owner and full return/field type. */
public final class MemberDescriptor {
    private final MemberKind kind;
    private final String owner, name;
    private final List<TypeDescriptor> parameters;
    private final TypeDescriptor type;
    /** Memoized {@link Objects#hash} value; 0 means not computed yet (racy single-check, value is deterministic). */
    private int hash;
    /** {@code parameters} must be a fresh list owned by this instance; it is wrapped, not copied. */
    private MemberDescriptor(MemberKind kind, String owner, String name, TypeDescriptor type, List<TypeDescriptor> parameters) {
        this.kind = Objects.requireNonNull(kind);
        TypeDescriptor.of("L" + Objects.requireNonNull(owner).replace('.', '/') + ";");
        // Equals the old substring/replace round trip: every '/' becomes '.', and '.' stays.
        this.owner = owner.replace('/', '.');
        this.name = Objects.requireNonNull(name);
        if (name.isEmpty() || name.length() > 1024) throw new IllegalArgumentException("member name");
        if (!(kind == MemberKind.CONSTRUCTOR && name.equals("<init>"))) {
            for (int i = 0; i < name.length(); i++) if (".;[/<>".indexOf(name.charAt(i)) >= 0) throw new IllegalArgumentException("member name");
        }
        if (parameters.size() > 255) throw new IllegalArgumentException("parameter count");
        for (TypeDescriptor parameter : parameters) if (parameter.isVoid()) throw new IllegalArgumentException("void parameter");
        if (kind == MemberKind.FIELD && (type.isVoid() || !parameters.isEmpty())) throw new IllegalArgumentException("field signature");
        if (kind == MemberKind.CONSTRUCTOR && (!name.equals("<init>") || !type.isVoid())) throw new IllegalArgumentException("constructor signature");
        this.type = Objects.requireNonNull(type);
        this.parameters = Collections.unmodifiableList(parameters);
    }
    public static MemberDescriptor method(String owner, String name, String returnType, String... parameters) { return create(MemberKind.METHOD, owner, name, returnType, parameters); }
    public static MemberDescriptor field(String owner, String name, String type) { return create(MemberKind.FIELD, owner, name, type); }
    public static MemberDescriptor constructor(String owner, String... parameters) { return create(MemberKind.CONSTRUCTOR, owner, "<init>", "V", parameters); }
    public static MemberDescriptor create(MemberKind kind, String owner, String name, String type, String... parameters) {
        List<TypeDescriptor> values = new ArrayList<>(parameters.length);
        for (String parameter : parameters) values.add(TypeDescriptor.of(parameter));
        return new MemberDescriptor(kind, owner, name, TypeDescriptor.of(type), values);
    }
    /** Each type is validated once by fromClass; failures and their order match the former string round trip. */
    public static MemberDescriptor from(Member member) {
        String owner = member.getDeclaringClass().getName();
        if (member instanceof Field) return new MemberDescriptor(MemberKind.FIELD, owner, member.getName(), TypeDescriptor.fromClass(((Field) member).getType()), new ArrayList<>(0));
        Class<?>[] params = member instanceof Method ? ((Method) member).getParameterTypes() : ((Constructor<?>) member).getParameterTypes();
        List<TypeDescriptor> values = new ArrayList<>(params.length);
        for (Class<?> param : params) values.add(TypeDescriptor.fromClass(param));
        return member instanceof Method
                ? new MemberDescriptor(MemberKind.METHOD, owner, member.getName(), TypeDescriptor.fromClass(((Method) member).getReturnType()), values)
                : new MemberDescriptor(MemberKind.CONSTRUCTOR, owner, "<init>", TypeDescriptor.fromClass(void.class), values);
    }
    public MemberKind kind() { return kind; }
    public String owner() { return owner; }
    public String name() { return name; }
    public List<TypeDescriptor> parameters() { return parameters; }
    public TypeDescriptor type() { return type; }
    public String signature() {
        StringBuilder value = new StringBuilder(owner).append('#').append(name);
        if (kind != MemberKind.FIELD) { value.append('('); for (TypeDescriptor parameter : parameters) value.append(parameter.value()); value.append(')'); }
        else value.append(':');
        return value.append(type.value()).toString();
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof MemberDescriptor)) return false;
        MemberDescriptor d = (MemberDescriptor) other;
        return kind == d.kind && owner.equals(d.owner) && name.equals(d.name) && parameters.equals(d.parameters) && type.equals(d.type);
    }
    @Override public int hashCode() {
        int value = hash;
        if (value == 0) hash = value = Objects.hash(kind, owner, name, parameters, type);
        return value;
    }
    @Override public String toString() { return signature(); }
}
