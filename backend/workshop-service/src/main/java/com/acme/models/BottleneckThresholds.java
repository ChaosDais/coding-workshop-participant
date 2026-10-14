package com.acme.models;

/**
 * When a project or milestone sitting in one status counts as a possible bottleneck: after awaitingReviewDays in
 * AWAITING_REVIEW, or after anyStatusDays in any unfinished status. Admins set them; each is 1 to 365 days.
 */
public record BottleneckThresholds(int awaitingReviewDays, int anyStatusDays) {
    public static final BottleneckThresholds DEFAULTS = new BottleneckThresholds(3, 30);
    static final int MAX_DAYS = 365;

    public BottleneckThresholds {
        requireDays(awaitingReviewDays, "awaitingReviewDays");
        requireDays(anyStatusDays, "anyStatusDays");
    }

    private static void requireDays(int days, String field) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException(field + " must be a whole number of days from 1 to " + MAX_DAYS);
        }
    }
}
