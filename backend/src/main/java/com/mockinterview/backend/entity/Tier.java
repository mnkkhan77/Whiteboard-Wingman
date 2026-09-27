package com.mockinterview.backend.entity;

/** Subscription tier gating Study Pack uploads (limits live in app.tiers.* — see TierProperties).
 *  Set by an admin via PUT /api/admin/users/{userId}/tier; every account starts as FREE. */
public enum Tier {
    FREE, PRO, MAX
}
