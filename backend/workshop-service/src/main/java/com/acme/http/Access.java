package com.acme.http;

import com.acme.models.Role;

import java.util.EnumSet;
import java.util.Set;

/**
 * Who may call a route: anyone, or signed-in users with one of the given roles.
 * Every route declares one when it is registered, so the permission check cannot be forgotten.
 * An account that must change its password can only use routes marked {@code beforePasswordChange}.
 */
public record Access(boolean signInRequired, Set<Role> roles, boolean beforePasswordChange) {
    public static final Access PUBLIC = new Access(false, Set.of(), true);
    public static final Access SIGNED_IN = new Access(true, EnumSet.allOf(Role.class), false);
    /** Any signed-in user, including one who still has to change their password: for their own account. */
    public static final Access OWN_ACCOUNT = new Access(true, EnumSet.allOf(Role.class), true);

    public static Access roles(Role first, Role... rest) {
        return new Access(true, EnumSet.of(first, rest), false);
    }

    public boolean allows(Role role) {
        return roles.contains(role);
    }
}
