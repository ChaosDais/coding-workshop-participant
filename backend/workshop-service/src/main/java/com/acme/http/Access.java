package com.acme.http;

import com.acme.models.Role;

import java.util.EnumSet;
import java.util.Set;

/**
 * Who may call a route: anyone, or signed-in users with one of the given roles.
 * Every route declares one when it is registered, so the permission check cannot be forgotten.
 */
public record Access(boolean signInRequired, Set<Role> roles) {
    public static final Access PUBLIC = new Access(false, Set.of());
    public static final Access SIGNED_IN = new Access(true, EnumSet.allOf(Role.class));

    public static Access roles(Role first, Role... rest) {
        return new Access(true, EnumSet.of(first, rest));
    }

    public boolean allows(Role role) {
        return roles.contains(role);
    }
}
