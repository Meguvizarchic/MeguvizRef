package io.github.jichuo1.meguviz.ref.scope;

import io.github.jichuo1.meguviz.ref.api.evidence.ResolutionReceipt;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionResult;
import io.github.jichuo1.meguviz.ref.api.result.ResolutionStatus;
import java.lang.reflect.Member;
import java.util.*;

/**
 * Result of {@link CodeScope#resolveRaw}: the same status and receipt as {@link CodeScope#resolve}, with raw members in
 * resolution order instead of scope tokens. Every member is an explicit ownership escape, like exportRaw; only
 * CodeScope creates instances.
 */
public final class RawResolution {
    private final ResolutionStatus status;
    private final List<Member> members;
    private final ResolutionReceipt receipt;
    RawResolution(ResolutionStatus status, List<Member> members, ResolutionReceipt receipt) {
        this.status = Objects.requireNonNull(status); this.members = Collections.unmodifiableList(members); this.receipt = Objects.requireNonNull(receipt);
    }
    static RawResolution failed(ResolutionResult failure) { return new RawResolution(failure.status(), Collections.emptyList(), failure.receipt()); }
    public ResolutionStatus status() { return status; }
    /** Unmodifiable; scope close or epoch changes do not revoke these references. */
    public List<Member> members() { return members; }
    public ResolutionReceipt receipt() { return receipt; }
}
