package fixture;

public final class ShadowOwner {
    public ShadowParam field;
    public ShadowParam[] arrayField;

    public ShadowOwner(ShadowParam value) { }
    public ShadowOwner(ShadowParam[] value) { }
    public ShadowParam method(ShadowParam value) { return value; }
    public ShadowParam[] arrayMethod(ShadowParam[] value) { return value; }
    public ShadowParam returns() { return null; }
    public ShadowParam[] returnsArray() { return null; }
}
