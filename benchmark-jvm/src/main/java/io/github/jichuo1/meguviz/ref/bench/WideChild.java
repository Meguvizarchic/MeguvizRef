package io.github.jichuo1.meguviz.ref.bench;

/** Two-level hierarchy (WideChild -> WideTarget -> Object) for SUPERCLASSES walks. */
public class WideChild extends WideTarget {
    public int childValue;
    public int child(int a) { return a + childValue; }
    @Override public int sum(int a, int b) { return a - b; }
}
