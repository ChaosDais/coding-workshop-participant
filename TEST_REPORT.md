# Test Report: Project Manager

**Date:** 2026-10-15 · **Commit:** `032f068` on branch `workshop`, plus uncommitted changes in the working tree · **Result:** ✅ all executed tests passed

## Summary

| Suite | Tests | Passed | Failed | Skipped | Run time |
|---|---:|---:|---:|---:|---:|
| Backend (JUnit 5, Java 21) | 85 | 81 | 0 | 4 | 3.2 s |
| Frontend (Vitest + React Testing Library) | 111 | 111 | 0 | 0 | 5.9 s |
| **Total** | **196** | **192** | **0** | **4** | |````````````````

The production frontend build (`vite build`) also succeeded: 354.6 kB of JavaScript (104.2 kB gzipped) and 24.8 kB of CSS.

**Skipped:** the 4 skipped tests are the PostgreSQL integration tests. They run only when `POSTGRES_TEST_URL` points at a database, and it was not set for this run (see [Not covered](#not-covered-by-this-run)).

## Environment

| | |
|---|---|
| Backend | Java 21.0.12 (OpenJDK), Maven offline (`mvn -o test`), JUnit 5 via Surefire |
| Frontend | Node 22.23.3, Vitest 3.2, React 19.2, jsdom, React Testing Library |
| Backend under test | The whole service wired with in-memory repositories and a controllable clock, called through the Lambda entry point with function-URL-shaped events, so routing and authorization run exactly as deployed |
| Frontend under test | Pages and components rendered in jsdom against a fake backend (`src/test/fakeBackend.js`) |

## What the tests cover

### Backend

| Test class | Tests | Skipped | Time | Area |
|---|---:|---:|---:|---|
| `AdminViewTest` | 6 |  | 218 ms | Audit log filters and paging, archived milestones, names recorded in audit entries |
| `AuthTest` | 7 |  | 89 ms | Premade admin and forced password change, sign-in, token expiry, refresh rotation and reuse detection, sign-out |
| `EmployeeTest` | 19 |  | 688 ms | Employee visibility, profile edits, milestone status changes, time logging, timers and the 8-hour cap, corrections, cost, per-person and per-day hours |
| `HandlerTest` | 10 |  | 234 ms | Request handling end to end: project and milestone lifecycle, validation messages, CloudFront prefix, encoded bodies, database failures |
| `IntegrityTest` | 7 |  | 181 ms | Data integrity: milestone dates within the project, frozen dates on completion, completion and status-change times, milestone dependencies |
| `ManagerTest` | 13 |  | 747 ms | Manager scope: their projects and team, assigning, moving and removing employees, one open milestone per employee |
| `ProjectDependencyTest` | 5 |  | 96 ms | Project dependencies: blocking until dependencies complete, no cycles, blocked milestones and time logging, admin-only changes |
| `UsersTest` | 7 |  | 912 ms | Accounts: creation and validation, moving employees between managers, archiving and lockout, admin guards |
| `models.ProjectTest` | 2 |  | 6 ms | Project model defaults and validation |
| `repos.PostgresDatabaseTest` | 3 |  | 33 ms | Retrying the connection while a paused Aurora database resumes |
| `repos.PostgresRepositoriesTest` | 5 | 4 | 27 ms | Every PostgreSQL repository against a real database, plus schema upgrades |
| `services.AuditServiceTest` | 1 |  | 3 ms | Audit service defaults |

### Frontend

| Test file | Tests | Time |
|---|---:|---:|
| `components/StatusAge.test.jsx` | 2 | 25 ms |
| `pages/AuditLogPage.test.jsx` | 4 | 791 ms |
| `pages/EmployeeView.test.jsx` | 5 | 761 ms |
| `pages/EmployeesPage.test.jsx` | 7 | 759 ms |
| `pages/ManagerView.test.jsx` | 5 | 804 ms |
| `pages/OptionsPage.test.jsx` | 6 | 595 ms |
| `pages/ProjectDetailPage.test.jsx` | 4 | 394 ms |
| `pages/ProjectsPage.test.jsx` | 9 | 839 ms |
| `pages/SignInPage.test.jsx` | 4 | 502 ms |
| `pages/WorkloadPage.test.jsx` | 4 | 414 ms |
| `services/api.test.js` | 2 | 3 ms |
| `services/apiClient.test.js` | 3 | 2 ms |
| `services/timer.test.js` | 6 | 2 ms |
| `utils/audit.test.js` | 9 | 2 ms |
| `utils/format.test.js` | 2 | 1 ms |
| `utils/hours.test.js` | 3 | 3 ms |
| `utils/overview.test.js` | 7 | 10 ms |
| `utils/projects.test.js` | 11 | 11 ms |
| `utils/timeInStatus.test.js` | 6 | 2 ms |
| `utils/users.test.js` | 3 | 8 ms |
| `utils/validation.test.js` | 6 | 3 ms |
| `utils/workload.test.js` | 3 | 2 ms |

- **Pages** (`pages/*.test.jsx`): sign-in and the forced password change; the project list with its overview, filters, expanding rows and archiving; the project view; the admin, manager and employee views; Employees and Team; Options; the audit log and its paging; the Workload charts and sorting.
- **Components** (`components/*.test.jsx`): time-in-status labels and warnings.
- **Logic** (`utils/*.test.js`, `services/*.test.js`): availability and dependencies, workload and overview calculations, audit text, hours and dates, validation, timer maths, the API client and its handling of CloudFront replies.

## Not covered by this run

- **PostgreSQL integration (4 tests, skipped).** These create a temporary schema, exercise every repository against real PostgreSQL and drop the schema afterwards. To include them, run from `backend/workshop-service`:

  ```sh
  POSTGRES_TEST_URL="jdbc:postgresql://localhost:5432/postgres?user=<user>&password=<password>" mvn test
  ```

- **Code coverage** was not measured: neither JaCoCo (backend) nor `@vitest/coverage-v8` (frontend) is installed.
- **Browser end-to-end tests** are not automated. Changes made during development were also checked by hand in headless Firefox against the local stack (LocalStack and PostgreSQL); those checks are not part of this report.
- **Linting** was not run: the project's ESLint configuration refers to plugins that are not installed.
- **AWS deployment** was not tested; everything ran locally.

## How to rerun

```sh
cd backend/workshop-service && mvn test        # JUnit; reports in target/surefire-reports/
cd frontend && npm test                          # Vitest
cd frontend && npx vite build                    # production build
```

> **Note for local development:** running Maven rebuilds `backend/workshop-service/target`, which LocalStack hot-reloads, and this can leave the local API answering 502. If that happens, remove the `*workshop-service*` Lambda containers (`docker rm -f`) and re-save the function configuration so LocalStack starts fresh ones.

## Every test case

### Backend

<details>
<summary><code>AdminViewTest</code>: 6 tests</summary>

- ✅ The audit log filters by project milestone action text and date
- ✅ Archived milestones can be listed
- ✅ Admins adjust the bottleneck thresholds everyone reads
- ✅ Time entries and team moves are logged by name
- ✅ Audit entries name the milestones and people they are about
- ✅ The audit log comes in pages with the total

</details>

<details>
<summary><code>AuthTest</code>: 7 tests</summary>

- ✅ Refresh tokens rotate and a reused one ends every session
- ✅ Sign in failures all look the same
- ✅ The premade admin is only created while there are no accounts
- ✅ The premade admin is created on first sign in and must change its password
- ✅ Refresh tokens expire and sign out revokes them
- ✅ Missing invalid and expired tokens are rejected
- ✅ Changing your password needs the current one and ends other sessions

</details>

<details>
<summary><code>EmployeeTest</code>: 19 tests</summary>

- ✅ Employees no longer see projects once they are complete
- ✅ Employees see only their assigned projects with all their milestones but no money
- ✅ A timer measures the time and only one can run
- ✅ Project progress is the sum of its milestones
- ✅ Employees see their manager and teammates without salaries
- ✅ Manual hours are validated
- ✅ Logged hours add to progress and project spending
- ✅ Employees cannot change anything else
- ✅ Admins and managers log their own time where they may
- ✅ Employees set their milestones to todo in progress or review but cannot complete them
- ✅ Employees edit only their own name and email
- ✅ Corrections are validated and admin only
- ✅ A timer stops counting at eight hours
- ✅ Projects carry each persons hours per milestone but employees see only their own
- ✅ Employees without a salary log time that costs nothing
- ✅ Admins correct logged time
- ✅ A persons assignments and logged time are summed by project and milestone for those who may see them
- ✅ Logged hours come by person milestone and day for the workload chart
- ✅ A forgotten timer is closed at eight hours so a new one can start

</details>

<details>
<summary><code>HandlerTest</code>: 10 tests</summary>

- ✅ Requests through cloud front keep their api prefix
- ✅ Unexpected errors become server errors instead of failing the invocation
- ✅ Invalid requests are rejected
- ✅ Project and milestone lifecycle
- ✅ Projects and milestones have optional expected hours
- ✅ Service root answers without sign in
- ✅ Base64 bodies are decoded
- ✅ A body that is not base64 is rejected
- ✅ Database failures become server errors
- ✅ Managers and employees must be active users in the right role

</details>

<details>
<summary><code>IntegrityTest</code>: 7 tests</summary>

- ✅ Milestone dates stay within the project
- ✅ Rule violations saved before the rules stay editable
- ✅ Completion times are recorded kept and cleared on reopening
- ✅ Status change times are recorded on creation and each status change
- ✅ A milestone cannot start or complete before its dependencies
- ✅ Completed work has frozen dates
- ✅ Dependencies must be valid and keep their targets

</details>

<details>
<summary><code>ManagerTest</code>: 13 tests</summary>

- ✅ Changing assignees leaves logged progress alone
- ✅ An employee works on one unfinished milestone per project
- ✅ Managers cannot manage accounts or read the audit log
- ✅ Managers edit their projects but cannot hand them over
- ✅ Managers run milestones and assign their own team
- ✅ Managers assign unassign and move their team between milestones
- ✅ Only admins change a projects description
- ✅ An employee moved away stays on their milestones but cannot be newly assigned
- ✅ Managers see only the projects they manage
- ✅ Admins also assign only the project managers team
- ✅ Managers cannot create archive or restore projects
- ✅ Managers see the name and status of dependencies owned by other managers
- ✅ Managers see their team without salaries

</details>

<details>
<summary><code>ProjectDependencyTest</code>: 5 tests</summary>

- ✅ A project cannot start until its dependencies are complete
- ✅ The chain stays intact
- ✅ Dependencies must point at other active projects without cycles
- ✅ Its milestones cannot start and no time can be logged while it is blocked
- ✅ Only admins change dependencies and updates without them keep them

</details>

<details>
<summary><code>UsersTest</code>: 7 tests</summary>

- ✅ Admins reset passwords and edit accounts
- ✅ Archived users are locked out immediately and can be restored
- ✅ Users have an optional job type and salary
- ✅ Admins create users of every role including other admins
- ✅ New users are validated
- ✅ Rules keep the team structure consistent
- ✅ Employees move between managers

</details>

<details>
<summary><code>models.ProjectTest</code>: 2 tests</summary>

- ✅ Defaults are applied
- ✅ Invalid models are rejected

</details>

<details>
<summary><code>repos.PostgresDatabaseTest</code>: 3 tests</summary>

- ✅ Keeps trying while the server cannot be reached
- ✅ Errors that waiting cannot fix fail at once
- ✅ Gives up when the window has passed

</details>

<details>
<summary><code>repos.PostgresRepositoriesTest</code>: 5 tests</summary>

- ✅ JDBC URL follows the environment
- ⏭️ Users tokens and settings round trip through PostgreSQL (skipped: needs `POSTGRES_TEST_URL`)
- ⏭️ Tables from the earlier version are upgraded (skipped: needs `POSTGRES_TEST_URL`)
- ⏭️ Time entries round trip through PostgreSQL (skipped: needs `POSTGRES_TEST_URL`)
- ⏭️ Projects and audit entries round trip through PostgreSQL (skipped: needs `POSTGRES_TEST_URL`)

</details>

<details>
<summary><code>services.AuditServiceTest</code>: 1 tests</summary>

- ✅ Records entries newest first with defaults

</details>

### Frontend

<details>
<summary><code>components/StatusAge.test.jsx</code>: 2 tests</summary>

- ✅ StatusAge › shows the days in the status with full-caps status names
- ✅ StatusAge › shows only the warning when asked, and nothing when there is none

</details>

<details>
<summary><code>pages/AuditLogPage.test.jsx</code>: 4 tests</summary>

- ✅ audit log page › shows everything on one page, without page controls, when it fits
- ✅ audit log page › moves between pages of 10 from controls above and below the list
- ✅ audit log page › goes to a typed page number, kept within the pages there are
- ✅ audit log page › confirms a typed page with Go, keeps it when Apply has no new filters, and moves at once with the arrow keys

</details>

<details>
<summary><code>pages/EmployeeView.test.jsx</code>: 5 tests</summary>

- ✅ employee view › lists only their current projects, with their milestones and no warnings
- ✅ employee view › shows the project without money and lets them work only on their own milestone
- ✅ employee view › logs hours by hand after checking them
- ✅ employee view › runs a timer on the milestone's own line that ticks every second, and logs the time when stopped
- ✅ employee view › shows a running timer in the bar at the top on other pages

</details>

<details>
<summary><code>pages/EmployeesPage.test.jsx</code>: 7 tests</summary>

- ✅ employees page › shows everyone with email, id, role, job, salary and manager
- ✅ employees page › searches, filters by role and shows archived people on request
- ✅ employees page › opens an employee to show their assigned milestones and the time logged on each
- ✅ employees page › opens a manager to show their projects with milestones and assignees, and their team
- ✅ employees page › does not offer to archive your own account
- ✅ employees page › lets an admin set someone else’s password, after checking it
- ✅ employees page › does not offer a password reset for your own account or archived people

</details>

<details>
<summary><code>pages/ManagerView.test.jsx</code>: 5 tests</summary>

- ✅ manager view › offers Projects and Employees only
- ✅ manager view › lists their projects with completion against expected hours and flags overruns
- ✅ manager view › shows previously completed projects with their completion dates on request
- ✅ manager view › assigns, moves and removes team members on a milestone, and flags a stalled review
- ✅ manager view › shows their team with each person’s current milestones

</details>

<details>
<summary><code>pages/OptionsPage.test.jsx</code>: 6 tests</summary>

- ✅ options › replaces the sign-out and password buttons in the navigation bar with Options
- ✅ options › shows the account and saves a new name and email
- ✅ options › remembers the chosen theme and applies it to the page
- ✅ options › remembers the chosen text size and applies it to the whole page
- ✅ options › lets admins, and only admins, change the bottleneck thresholds
- ✅ options › gives managers no System tab, even by address

</details>

<details>
<summary><code>pages/ProjectDetailPage.test.jsx</code>: 4 tests</summary>

- ✅ project view › shows the description, money, dependencies and availability
- ✅ project view › lists milestones with assignees and what they wait for
- ✅ project view › shows archived milestones on request
- ✅ project view › shows the project history, narrowed to a milestone on request

</details>

<details>
<summary><code>pages/ProjectsPage.test.jsx</code>: 9 tests</summary>

- ✅ projects page › lists current projects with status, manager, progress and availability
- ✅ projects page › opens with an overview of status and pain points for the projects shown
- ✅ projects page › shows each project start and expected end date
- ✅ projects page › hides completed projects until asked, then shows them greyed out at the bottom
- ✅ projects page › searches and filters by status and manager
- ✅ projects page › shows archived projects on request
- ✅ projects page › opens a project in place to show its description and milestones, and a milestone to show who is on it
- ✅ projects page › lets admins archive projects and milestones from the list
- ✅ projects page › validates a new project before creating it

</details>

<details>
<summary><code>pages/SignInPage.test.jsx</code>: 4 tests</summary>

- ✅ signing in › checks the fields before sending anything
- ✅ signing in › shows the server message when the credentials are wrong
- ✅ signing in › sends the premade admin to choose a password before anything else
- ✅ signing in › opens the projects page with the navigation bar for an admin, whatever page was open before

</details>

<details>
<summary><code>pages/WorkloadPage.test.jsx</code>: 4 tests</summary>

- ✅ workload page › charts every employee for admins, with their progress against their workload
- ✅ workload page › sorts the charts by any figure, ascending or descending
- ✅ workload page › shows managers only their own team
- ✅ workload page › reads out the total on a day from the keyboard, as hovering does

</details>

<details>
<summary><code>services/api.test.js</code>: 2 tests</summary>

- ✅ API replies › reads no content from GET /timer as no running timer
- ✅ API replies › treats an HTML page in place of JSON as the 404 CloudFront turned it from

</details>

<details>
<summary><code>services/apiClient.test.js</code>: 3 tests</summary>

- ✅ fillPlaceholders › fills known values in paths and bodies
- ✅ fillPlaceholders › leaves placeholders without a value so the request stays visibly invalid
- ✅ fillPlaceholders › does not touch ordinary JSON braces

</details>

<details>
<summary><code>services/timer.test.js</code>: 6 tests</summary>

- ✅ elapsedMs › counts the time since the timer started
- ✅ elapsedMs › stops at the 8-hour cap
- ✅ elapsedMs › is never negative when the local clock is behind the server
- ✅ formatDuration › pads minutes and seconds
- ✅ formatDuration › drops partial seconds
- ✅ formatDuration › shows the full cap

</details>

<details>
<summary><code>utils/audit.test.js</code>: 9 tests</summary>

- ✅ actionLabel › turns an action into a sentence
- ✅ describeDetails › uses names where it knows them
- ✅ describeDetails › prefers the stored milestone name, so renamed or archived milestones keep their name
- ✅ describeDetails › names people, falling back to the lookup for older entries
- ✅ describeDetails › shows moves between managers by name
- ✅ describeDetails › shows corrections with the hours before and after, and the reason
- ✅ names instead of ids › names who made the change: stored name first, then by email
- ✅ names instead of ids › never shows an id, even when nothing is known about it
- ✅ names instead of ids › uses the names stored with time entries and team moves

</details>

<details>
<summary><code>utils/format.test.js</code>: 2 tests</summary>

- ✅ formatHours › shows hours and minutes, to the nearest minute
- ✅ formatHours › shows nothing logged as 0 min

</details>

<details>
<summary><code>utils/hours.test.js</code>: 3 tests</summary>

- ✅ hoursAgainstExpected › gives the completion percentage of the expected hours
- ✅ hoursAgainstExpected › flags progress beyond the estimate and by how much
- ✅ hoursAgainstExpected › has nothing to compare without an estimate

</details>

<details>
<summary><code>utils/overview.test.js</code>: 7 tests</summary>

- ✅ overview › counts projects by status and the milestones complete
- ✅ overview › finds the pain points, naming what is affected
- ✅ overview › leaves hours estimates and money out for employees
- ✅ overview › lists unfinished milestones due in the next 30 days, soonest first
- ✅ overview › reports nothing for an empty list
- ✅ employeeOverview › measures their logged hours against their share of their open milestones
- ✅ employeeOverview › lists only their own pain points and deadlines

</details>

<details>
<summary><code>utils/projects.test.js</code>: 11 tests</summary>

- ✅ availability › is available with no dependencies or only complete ones
- ✅ availability › names the dependencies that are not complete, including unknown ones
- ✅ availability › is completed for a complete project, whatever its dependencies
- ✅ projectRows › hides completed and archived projects by default and orders the rest by start date
- ✅ projectRows › puts completed projects last when shown, after archived open ones
- ✅ projectRows › shows completed projects when the status filter asks for them
- ✅ projectRows › searches names, descriptions and manager names, and filters by manager
- ✅ blockingMilestones › lists the incomplete milestones a milestone waits for
- ✅ sortProjects › orders by start date, then name, without changing the input
- ✅ openMilestoneByEmployee › maps each employee to the unfinished milestone they work on, leaving one milestone out
- ✅ workloadByEmployee › splits each unfinished milestone's expected hours between the people assigned to it

</details>

<details>
<summary><code>utils/timeInStatus.test.js</code>: 6 tests</summary>

- ✅ timeInStatus › counts whole days in the current status
- ✅ timeInStatus › shows nothing for completed work or an unknown start of the status
- ✅ timeInStatus › flags a review that has waited three days or more
- ✅ timeInStatus › flags work not started by its start date, and work past its expected end
- ✅ timeInStatus › flags anything in one status for 30 days or more
- ✅ timeInStatus › uses the thresholds an admin has set

</details>

<details>
<summary><code>utils/users.test.js</code>: 3 tests</summary>

- ✅ filterUsers › sorts by name and keeps everyone without filters
- ✅ filterUsers › searches name, email, job and id, ignoring case
- ✅ filterUsers › filters by role, combined with the search

</details>

<details>
<summary><code>utils/validation.test.js</code>: 6 tests</summary>

- ✅ validation › checks required text
- ✅ validation › checks emails
- ✅ validation › checks numbers of zero or more, optionally empty
- ✅ validation › checks date ranges
- ✅ validation › checks passwords like the backend does
- ✅ validation › drops fields without an error

</details>

<details>
<summary><code>utils/workload.test.js</code>: 3 tests</summary>

- ✅ workload helpers › lists every day of a range, ending today
- ✅ workload helpers › runs a total of the hours logged on the workload milestones, counting time from before the range
- ✅ workload helpers › rounds the shared scale up to a clean number

</details>
