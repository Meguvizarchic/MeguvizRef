package fixture;

/** Same parameter type name as ShadowOwner; a child-first loader defines both this class and its own ShadowParam. */
public final class ShadowPeer {
    public ShadowParam method(ShadowParam value) { return value; }
}
