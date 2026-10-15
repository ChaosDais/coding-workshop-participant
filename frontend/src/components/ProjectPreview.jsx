import PropTypes from 'prop-types';
import HoursProgress from './HoursProgress.jsx';
import StatusAge from './StatusAge.jsx';
import StatusBadge from './StatusBadge.jsx';
import { ChevronIcon } from './Icons.jsx';
import { useToggleSet } from '../hooks/useToggleSet.js';
import { formatDate, formatHours } from '../utils/format.js';

/**
 * A project opened up in the project list: its description and milestones. Each milestone opens to show its
 * description, who is assigned to it and the time each person has logged on it. Admins can archive milestones
 * from here.
 * @param {object} props Component props.
 * @param {object} props.project The project, with its active milestones.
 * @param {Map<string, {name: string}>} props.usersById People, to name the assignees.
 * @param {boolean} props.showOverrun Whether to flag progress beyond the estimate (admins and managers).
 * @param {boolean} [props.showStatusAge] Whether to show how long each has been in its status (admins and managers).
 * @param {string} [props.highlightUserId] Milestones this person is assigned to are marked "You".
 * @param {boolean} [props.everyonesHours] Whether project.hoursByMilestone holds everyone's hours (admins and
 *   managers) or only the viewer's own (employees), so nobody else's time reads as zero.
 * @param {Function} [props.onArchiveMilestone] Archives a milestone; offered only when given.
 * @param {boolean} [props.busy] Disables the archive buttons while a change is saved.
 * @returns {JSX.Element} The expanded project.
 */
function ProjectPreview({
  project, usersById, showOverrun, showStatusAge = false, highlightUserId, everyonesHours = true, onArchiveMilestone,
  busy = false,
}) {
  const [open, toggle] = useToggleSet();

  return (
    <div className="project-preview">
      <p className={project.description ? 'preview-description' : 'muted'}>{project.description || 'No description.'}</p>
      {showStatusAge && <StatusAge item={project} />}
      <h3 className="preview-heading">{`Milestones (${project.milestones.length})`}</h3>
      {project.milestones.length === 0 ? <p className="muted">No milestones yet.</p> : (
        <ul className="milestone-previews">
          {project.milestones.map((milestone) => {
            const expanded = open.has(milestone.id);
            const panelId = `milestone-preview-${milestone.id}`;
            const hours = project.hoursByMilestone?.[milestone.id] ?? {};
            // Everyone assigned, then anyone else who logged time on it (e.g. a manager, or someone moved off it).
            const people = [...new Set([...milestone.assignedEmployees, ...Object.keys(hours)])].map((id) => ({
              id,
              name: id === highlightUserId ? 'You' : usersById.get(id)?.name ?? 'Former team member',
              assigned: milestone.assignedEmployees.includes(id),
              hours: hours[id] === undefined ? (everyonesHours ? 0 : undefined) : Number(hours[id]),
            }));
            return (
              <li key={milestone.id} className={expanded ? 'open' : undefined}>
                <div className="milestone-preview-row">
                  <button type="button" className="expand-toggle" aria-expanded={expanded} aria-controls={panelId} onClick={() => toggle(milestone.id)}>
                    <ChevronIcon />
                    <span className="milestone-preview-name">{milestone.name}</span>
                  </button>
                  {milestone.assignedEmployees.includes(highlightUserId) && <span className="badge you-badge">You</span>}
                  <StatusBadge status={milestone.status} />
                  {showStatusAge && <StatusAge item={milestone} />}
                  <span className="muted small nowrap">{`${formatDate(milestone.startDate)} – ${formatDate(milestone.endDate)}`}</span>
                  <span className="milestone-preview-hours">
                    <HoursProgress progress={milestone.progressTime} expected={milestone.expectedHours} showOverrun={showOverrun} label={milestone.name} />
                  </span>
                  {onArchiveMilestone && (
                    <button type="button" className="link-button danger" disabled={busy} aria-label={`Archive milestone ${milestone.name}`} onClick={() => onArchiveMilestone(milestone)}>
                      Archive
                    </button>
                  )}
                </div>
                {expanded && (
                  <div className="milestone-preview-details" id={panelId}>
                    <p className={milestone.description ? undefined : 'muted'}>{milestone.description || 'No description.'}</p>
                    {people.length === 0 ? <p className="muted">Nobody assigned yet.</p> : (
                      <ul className="milestone-people" aria-label={`Time on ${milestone.name}`}>
                        {people.map((person) => (
                          <li key={person.id}>
                            <span>
                              {person.name}
                              {!person.assigned && <span className="muted small"> · not assigned</span>}
                            </span>
                            <span className="milestone-people-hours">
                              {person.hours === undefined ? '' : formatHours(person.hours)}
                            </span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

ProjectPreview.propTypes = {
  project: PropTypes.shape({
    description: PropTypes.string,
    milestones: PropTypes.arrayOf(PropTypes.shape({
      id: PropTypes.string.isRequired,
      name: PropTypes.string.isRequired,
      description: PropTypes.string,
      status: PropTypes.string.isRequired,
      startDate: PropTypes.string,
      endDate: PropTypes.string,
      progressTime: PropTypes.number,
      expectedHours: PropTypes.number,
      assignedEmployees: PropTypes.arrayOf(PropTypes.string).isRequired,
    })).isRequired,
  }).isRequired,
  usersById: PropTypes.instanceOf(Map).isRequired,
  showOverrun: PropTypes.bool.isRequired,
  showStatusAge: PropTypes.bool,
  highlightUserId: PropTypes.string,
  everyonesHours: PropTypes.bool,
  onArchiveMilestone: PropTypes.func,
  busy: PropTypes.bool,
};

export default ProjectPreview;
