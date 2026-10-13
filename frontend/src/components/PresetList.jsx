import PropTypes from 'prop-types';

/**
 * Buttons that load a ready-made request, grouped by area.
 * @param {object} props Component props.
 * @param {object[]} props.presets Available presets.
 * @param {string} [props.activeId] Id of the currently loaded preset.
 * @param {Function} props.onSelect Called with the chosen preset.
 * @returns {JSX.Element} The preset list.
 */
function PresetList({ presets, activeId, onSelect }) {
  const groups = [...new Set(presets.map((preset) => preset.group))];

  return (
    <nav className="preset-list" aria-label="Request presets">
      {groups.map((group) => (
        <section key={group}>
          <h2>{group}</h2>
          {presets.filter((preset) => preset.group === group).map((preset) => (
            <button
              key={preset.id}
              type="button"
              aria-pressed={preset.id === activeId}
              onClick={() => onSelect(preset)}
            >
              <span className={`method method-${preset.method}`}>{preset.method}</span>
              {preset.label}
            </button>
          ))}
        </section>
      ))}
    </nav>
  );
}

PresetList.propTypes = {
  presets: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.string.isRequired,
    group: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
    method: PropTypes.string.isRequired,
  })).isRequired,
  activeId: PropTypes.string,
  onSelect: PropTypes.func.isRequired,
};

export default PresetList;
