package com.acme.services;

import com.acme.models.BottleneckThresholds;
import com.acme.models.User;
import com.acme.repos.SettingsRepository;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.LinkedHashMap;
import java.util.Map;

/** App-wide settings that admins adjust, stored so every Lambda instance and every user sees the same values. */
public class SettingsService {
    private static final String BOTTLENECKS = "bottleneck_thresholds";
    private static final Gson GSON = new Gson();

    private final SettingsRepository settings;
    private final AuditService auditService;

    public SettingsService(SettingsRepository settings, AuditService auditService) {
        this.settings = settings;
        this.auditService = auditService;
    }

    /** The stored thresholds, or the defaults when none have been saved (or the stored value is unreadable). */
    public BottleneckThresholds bottleneckThresholds() {
        return settings.get(BOTTLENECKS).map(json -> {
            try {
                return GSON.fromJson(json, BottleneckThresholds.class);
            } catch (JsonParseException | IllegalArgumentException e) {
                return BottleneckThresholds.DEFAULTS;
            }
        }).orElse(BottleneckThresholds.DEFAULTS);
    }

    public BottleneckThresholds updateBottleneckThresholds(BottleneckThresholds thresholds, User actor) {
        BottleneckThresholds previous = bottleneckThresholds();
        // One value holds both numbers, so they always change together.
        settings.put(BOTTLENECKS, GSON.toJson(thresholds));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("setting", BOTTLENECKS);
        details.put("previous", GSON.toJson(previous));
        details.put("value", GSON.toJson(thresholds));
        auditService.recordAction("settings_updated", actor.email(), details);
        return thresholds;
    }
}
