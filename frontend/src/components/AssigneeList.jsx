import PropTypes from 'prop-types';

/**
 * Who works on a milestone, with controls to assign someone from the team, move a person to another milestone
 * of the project, or take them off. Each change only touches the assignments.
 * @param {object} props Component props.
 * @param {{id: string, name: string, assignedEmployees: string[]}} props.milestone The milestone.
 * @param {{id: string, name: string}[]} props.otherMilestones The project's other active milestones.
 * @param {{id: string, name: string}[]} props.team Employees who can be assigned (the project manager's team).
 * @param {Map<string, string>} props.workingOn For employees already on another unfinished milestone of the project,
 *   its name; they cannot be added here, only moved.
 * @param {Map<string, {name: string}>} props.usersById Names of everyone the viewer can see.
 * @param {boolean} props.editable Whether to show the controls.
 * @param {boolean} props.busy Disables the controls while a change is saving.
 * @param {Function} props.onAssign Called with an employee id.
 * @param {Function} props.onUnassign Called with an employee id.
 * @param {Function} props.onMove Called with an employee id and the target milestone id.
 * @returns {JSX.Element} The assignees.
 */
function AssigneeList({
  milestone, otherMilestones, team, workingOn, usersById, editable, busy, onAssign, onUnassign, onMove,
}) {
  const name = (id) => usersById.get(id)?.name ?? `Former team member (${id.slice(0, 8)})`;
  const unassigned = team.filter((employee) => !milestone.assignedEmployees.includes(employee.id));

  if (!editable) {
    return milestone.assignedEmployees.length
      ? <span>{milestone.assignedEmployees.map(name).join(', ')}</span>
      : <span className="muted">Nobody</span>;
  }

  return (
    <div className="assignees">
      {milestone.assignedEmployees.length === 0 && <span className="muted">Nobody</span>}
      <ul className="plain-list">
        {milestone.assignedEmployees.map((id) => (
          <li key={id} className="assignee">
            <span>{name(id)}</span>
            {otherMilestones.length > 0 && (
              <select
                aria-label={`Move ${name(id)} to another milestone`}
                value=""
                disabled={busy}
                onChange={(event) => event.target.value && onMove(id, event.target.value)}
              >
                <option value="">Move to…</option>
                {otherMilestones.map((other) => <option key={other.id} value={other.id}>{other.name}</option>)}
              </select>
            )}
            <button type="button" className="link-button danger" disabled={busy} aria-label={`Remove ${name(id)} from ${milestone.name}`} onClick={() => onUnassign(id)}>
              Remove
            </button>
          </li>
        ))}
      </ul>
      {unassigned.length > 0 && (
        <select aria-label={`Assign an employee to ${milestone.name}`} value="" disabled={busy} onChange={(event) => event.target.value && onAssign(event.target.value)}>
          <option value="">Assign employee…</option>
          {unassigned.map((employee) => {
            const elsewhere = milestone.status !== 'COMPLETE' ? workingOn.get(employee.id) : undefined;
            return (
              <option key={employee.id} value={employee.id} disabled={Boolean(elsewhere)}>
                {elsewhere ? `${employee.name} — already on ${elsewhere}` : employee.name}
              </option>
            );
          })}
        </select>
      )}
    </div>
  );
}

AssigneeList.propTypes = {
  milestone: PropTypes.shape({
    id: PropTypes.string.isRequired,
    name: PropTypes.string.isRequired,
    status: PropTypes.string,
    assignedEmployees: PropTypes.arrayOf(PropTypes.string).isRequired,
  }).isRequired,
  otherMilestones: PropTypes.arrayOf(PropTypes.shape({ id: PropTypes.string, name: PropTypes.string })).isRequired,
  team: PropTypes.arrayOf(PropTypes.shape({ id: PropTypes.string, name: PropTypes.string })).isRequired,
  workingOn: PropTypes.instanceOf(Map).isRequired,
  usersById: PropTypes.instanceOf(Map).isRequired,
  editable: PropTypes.bool.isRequired,
  busy: PropTypes.bool.isRequired,
  onAssign: PropTypes.func.isRequired,
  onUnassign: PropTypes.func.isRequired,
  onMove: PropTypes.func.isRequired,
};

export default AssigneeList;
