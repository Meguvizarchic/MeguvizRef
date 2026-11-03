package io.github.jichuo1.meguviz.ref.api.descriptor;

import java.util.Objects;

/** A validated JVM type descriptor; loading never initializes the referenced class. */
public final class TypeDescriptor {
    private static final String PRIMITIVES = "VZBCSIJFD";
    /** Immutable value objects, so the nine primitive descriptors are shared instances. */
    private static final TypeDescriptor[] PRIMITIVE = new TypeDescriptor[PRIMITIVES.length()];
    static { for (int i = 0; i < PRIMITIVE.length; i++) PRIMITIVE[i] = new TypeDescriptor(PRIMITIVES.substring(i, i + 1)); }
    private final String value;
    private TypeDescriptor(String value) { this.value = value; }
    /** Same checks, order and messages as before; validated in place without substring copies. */
    public static TypeDescriptor of(String value) {
        Objects.requireNonNull(value, "descriptor");
        int length = value.length();
        if (length == 0 || length > 4096) throw new IllegalArgumentException("descriptor length");
        int dimensions = 0;
        while (dimensions < length && value.charAt(dimensions) == '[') dimensions++;
        if (dimensions > 255 || dimensions == length) throw new IllegalArgumentException("array dimensions");
        char first = value.charAt(dimensions);
        int primitive = PRIMITIVES.indexOf(first);
        if (length - dimensions == 1 && primitive >= 0) {
            if (dimensions > 0 && first == 'V') throw new IllegalArgumentException("void array");
            if (dimensions == 0) return PRIMITIVE[primitive];
        } else if (first == 'L' && value.charAt(length - 1) == ';' && length - dimensions > 2) {
            int start = dimensions + 1, end = length - 1;
            // The name ends just before ';', so any "//" found from start lies inside the name.
            if (value.charAt(start) == '/' || value.charAt(end - 1) == '/' || value.indexOf("//", start) >= 0) throw new IllegalArgumentException("reference name");
            for (int i = start; i < end; i++) {
                char c = value.charAt(i);
                if (c == '.' || c == ';' || c == '[' || c == '(' || c == ')' || Character.isWhitespace(c)) throw new IllegalArgumentException("reference name");
            }
        } else throw new IllegalArgumentException("invalid descriptor: " + value);
        return new TypeDescriptor(value);
    }
    /**
     * Same checks and messages as {@code of("L" + owner.replace('.', '/') + ";")} without building that string: the
     * name is non-empty, at most 4094 characters, has no leading, trailing or doubled separator ('.' or '/'), and no
     * ';', '[', '(', ')' or whitespace. Every failure of that call carries one of the three messages used here.
     */
    static void validateOwner(String owner) {
        int length = owner.length();
        if (length > 4094) throw new IllegalArgumentException("descriptor length");
        if (length == 0) throw new IllegalArgumentException("invalid descriptor: L;");
        boolean previousSeparator = true; // a leading separator is rejected like a doubled one
        for (int i = 0; i < length; i++) {
            char c = owner.charAt(i);
            boolean separator = c == '.' || c == '/';
            if ((separator && previousSeparator) || c == ';' || c == '[' || c == '(' || c == ')' || Character.isWhitespace(c)) throw new IllegalArgumentException("reference name");
            previousSeparator = separator;
        }
        if (previousSeparator) throw new IllegalArgumentException("reference name");
    }
    public static TypeDescriptor fromClass(Class<?> type) {
        if (type.isArray()) return of(type.getName().replace('.', '/'));
        if (!type.isPrimitive()) return of("L" + type.getName().replace('.', '/') + ";");
        if (type == void.class) return PRIMITIVE[0];
        if (type == boolean.class) return PRIMITIVE[1];
        if (type == byte.class) return PRIMITIVE[2];
        if (type == char.class) return PRIMITIVE[3];
        if (type == short.class) return PRIMITIVE[4];
        if (type == int.class) return PRIMITIVE[5];
        if (type == long.class) return PRIMITIVE[6];
        if (type == float.class) return PRIMITIVE[7];
        return PRIMITIVE[8];
    }
    public String value() { return value; }
    public boolean isVoid() { return value.equals("V"); }
    public Class<?> resolve(ClassLoader loader) throws ClassNotFoundException {
        switch (value) {
            case "V": return void.class;
            case "Z": return boolean.class;
            case "B": return byte.class;
            case "C": return char.class;
            case "S": return short.class;
            case "I": return int.class;
            case "J": return long.class;
            case "F": return float.class;
            case "D": return double.class;
            default:
                String name = value.charAt(0) == 'L' ? value.substring(1, value.length() - 1) : value;
                return Class.forName(name.replace('/', '.'), false, loader);
        }
    }
    @Override public boolean equals(Object other) { return other instanceof TypeDescriptor && value.equals(((TypeDescriptor) other).value); }
    @Override public int hashCode() { return value.hashCode(); }
    @Override public String toString() { return value; }
}
