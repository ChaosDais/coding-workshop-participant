package com.acme.models;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Hours one person logged on one milestone on one day (UTC): the day a timer started, or a manual entry was made. */
public record DailyHours(String userId, String projectId, String milestoneId, LocalDate day, BigDecimal hours) {
}
