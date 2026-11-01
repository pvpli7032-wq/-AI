package com.ruoyi.system.cps.rule;

import java.time.Instant;

/** Database projections; JSON encodes immutable records, never polymorphic auto-type payloads. */
public final class RuleDocuments {
    private RuleDocuments() { }
    public record Draft(long id, String platform, long revision, String config, boolean archived) { }
    public record Version(long id, long documentId, long revision, String config, boolean enabled,
            Instant effectiveAt, long createdBy, String safetyReference) { }
}
