package com.acme.models;

import java.time.LocalDate;

final class Checks {
    private Checks() {
    }

    // Validate string fields
    static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    // Validate email addresses; stored lowercased so lookups ignore case
    static String email(String value) {
        String email = text(value, "email").toLowerCase();
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1 || email.contains(" ")) {
            throw new IllegalArgumentException("email must be a valid email address");
        }
        return email;
    }

    // Validate date ranges
    static void dateRange(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("startDate and endDate are required");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
    }

    // Validate non-negative numbers
    static void nonNegative(double value, String field) {
        if (!(value >= 0)) {
            throw new IllegalArgumentException(field + " must be a non-negative number");
        }
    }
}
