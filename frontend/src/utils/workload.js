/** Time ranges the workload chart offers, in days back from today; 0 means since the first logged hour. */
export const RANGES = [
  { value: 28, label: 'Last 4 weeks' },
  { value: 91, label: 'Last 3 months' },
  { value: 182, label: 'Last 6 months' },
  { value: 0, label: 'All time' },
];

const DAY_MS = 86_400_000;

/** @param {Date} date A date. @returns {string} Its UTC day, "2026-10-15". */
export function dayOf(date) {
  return date.toISOString().slice(0, 10);
}

/** @param {string} day "2026-10-15". @param {number} days Days to add. @returns {string} The day that many later. */
export function addDays(day, days) {
  return dayOf(new Date(Date.parse(`${day}T00:00:00Z`) + days * DAY_MS));
}

/**
 * The days a range covers, oldest first, ending today.
 * @param {number} range Days back from today, or 0 for since the earliest logged day.
 * @param {string} today "2026-10-15".
 * @param {string} [earliest] The first day anything was logged, for "All time".
 * @returns {string[]} Every day in the range.
 */
export function rangeDays(range, today, earliest) {
  const first = range > 0 ? addDays(today, -(range - 1)) : (earliest && earliest < today ? earliest : addDays(today, -27));
  const days = [];
  for (let day = first; day <= today; day = addDays(day, 1)) {
    days.push(day);
  }
  return days;
}

/**
 * An employee's progress on their current workload: for each day, the hours they had logged by then on the
 * milestones that make up their workload, counting time from before the range too.
 * @param {{userId: string, milestoneId: string, day: string, hours: number}[]} daily Hours by person, milestone, day.
 * @param {string} userId The employee.
 * @param {Set<string>} milestoneIds The milestones their workload is made of.
 * @param {string[]} days The days to plot, oldest first.
 * @returns {{day: string, total: number}[]} Running totals, one per day.
 */
export function progressSeries(daily, userId, milestoneIds, days) {
  const byDay = new Map();
  let total = 0;
  daily.filter((row) => row.userId === userId && milestoneIds.has(row.milestoneId)).forEach((row) => {
    if (row.day < days[0]) {
      total += Number(row.hours);
    } else {
      byDay.set(row.day, (byDay.get(row.day) ?? 0) + Number(row.hours));
    }
  });
  return days.map((day) => {
    total += byDay.get(day) ?? 0;
    return { day, total: Math.round(total * 100) / 100 };
  });
}

/**
 * A round top for a shared hours axis, with ticks: 0, half and the top.
 * @param {number} max The largest value plotted.
 * @returns {number} A clean number at or above max.
 */
export function niceMax(max) {
  if (max <= 0) {
    return 8;
  }
  const magnitude = 10 ** Math.floor(Math.log10(max));
  const step = [1, 2, 2.5, 5, 10].find((candidate) => candidate * magnitude >= max);
  return step * magnitude;
}
