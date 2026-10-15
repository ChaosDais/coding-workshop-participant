import { useState } from 'react';
import PropTypes from 'prop-types';
import { formatDate, formatHours } from '../utils/format.js';

const WIDTH = 320;
const HEIGHT = 150;
const PAD = {
  top: 14, right: 44, bottom: 22, left: 34,
};

/**
 * One employee's progress on their workload over time: a line of the hours logged so far on their workload's
 * milestones, against a dashed line at the workload itself. Every chart on the page shares one hours scale, so
 * they compare at a glance. Hovering, or focusing it and using the arrow keys, Home and End, shows the total on
 * a day.
 * @param {object} props Component props.
 * @param {string} props.name The employee, for the description.
 * @param {{day: string, total: number}[]} props.series Running totals, one per day, oldest first.
 * @param {number} props.workload Their workload in hours.
 * @param {number} props.maxHours Top of the shared hours scale.
 * @returns {JSX.Element} The chart.
 */
function WorkloadChart({
  name, series, workload, maxHours,
}) {
  const [hover, setHover] = useState();
  const plotWidth = WIDTH - PAD.left - PAD.right;
  const plotHeight = HEIGHT - PAD.top - PAD.bottom;
  const x = (index) => PAD.left + (series.length <= 1 ? plotWidth : (index / (series.length - 1)) * plotWidth);
  const y = (hours) => PAD.top + plotHeight - (Math.min(hours, maxHours) / maxHours) * plotHeight;

  const points = series.map((point, index) => `${x(index).toFixed(1)},${y(point.total).toFixed(1)}`);
  const line = `M${points.join('L')}`;
  const area = `${line}L${x(series.length - 1).toFixed(1)},${y(0)}L${x(0).toFixed(1)},${y(0)}Z`;
  const last = series[series.length - 1];
  const ticks = [0, maxHours / 2, maxHours];
  const middle = Math.floor((series.length - 1) / 2);

  const pick = (clientX, rect) => {
    const position = ((clientX - rect.left) / rect.width) * WIDTH;
    const index = Math.round(((position - PAD.left) / plotWidth) * (series.length - 1));
    setHover(Math.min(Math.max(index, 0), series.length - 1));
  };
  const shown = hover === undefined ? undefined : series[hover];

  return (
    <div className="workload-chart">
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label={`${name}: ${formatHours(last.total)} logged of a ${formatHours(workload)} workload, from ${formatDate(series[0].day)} to ${formatDate(last.day)}`}
        tabIndex={0}
        onPointerMove={(event) => pick(event.clientX, event.currentTarget.getBoundingClientRect())}
        onPointerLeave={() => setHover(undefined)}
        onBlur={() => setHover(undefined)}
        onKeyDown={(event) => {
          if (event.key === 'Home' || event.key === 'End') {
            event.preventDefault();
            setHover(event.key === 'Home' ? 0 : series.length - 1);
          } else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
            event.preventDefault();
            const from = hover ?? series.length - 1;
            setHover(Math.min(Math.max(from + (event.key === 'ArrowRight' ? 1 : -1), 0), series.length - 1));
          }
        }}
      >
        {ticks.map((tick) => (
          <g key={tick}>
            <line className="chart-grid" x1={PAD.left} x2={PAD.left + plotWidth} y1={y(tick)} y2={y(tick)} />
            <text className="chart-tick" x={PAD.left - 6} y={y(tick)} textAnchor="end" dominantBaseline="middle">{`${Math.round(tick)} h`}</text>
          </g>
        ))}
        {[0, middle, series.length - 1].filter((index, position, all) => all.indexOf(index) === position).map((index) => (
          <text key={index} className="chart-tick" x={x(index)} y={HEIGHT - 6} textAnchor={index === 0 ? 'start' : index === series.length - 1 ? 'end' : 'middle'}>
            {formatDate(series[index].day).replace(/, \d{4}$/, '')}
          </text>
        ))}

        <line className="chart-target" x1={PAD.left} x2={PAD.left + plotWidth} y1={y(workload)} y2={y(workload)} />
        <text className="chart-target-label" x={PAD.left + plotWidth + 4} y={y(workload)} dominantBaseline="middle">
          {formatHours(workload)}
        </text>

        <path className="chart-area" d={area} />
        <path className="chart-line" d={line} />
        <circle className="chart-dot" cx={x(series.length - 1)} cy={y(last.total)} r="4" />

        {shown && (
          <g className="chart-hover">
            <line className="chart-crosshair" x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + plotHeight} />
            <circle className="chart-dot" cx={x(hover)} cy={y(shown.total)} r="4" />
          </g>
        )}
      </svg>
      {shown && (
        <div className="chart-tooltip" style={{ left: `${(x(hover) / WIDTH) * 100}%` }} role="status">
          <strong>{formatHours(shown.total)}</strong>
          <span>{`logged by ${formatDate(shown.day)}`}</span>
          <span className="muted">{`of ${formatHours(workload)} workload`}</span>
        </div>
      )}
    </div>
  );
}

WorkloadChart.propTypes = {
  name: PropTypes.string.isRequired,
  series: PropTypes.arrayOf(PropTypes.shape({ day: PropTypes.string, total: PropTypes.number })).isRequired,
  workload: PropTypes.number.isRequired,
  maxHours: PropTypes.number.isRequired,
};

export default WorkloadChart;
