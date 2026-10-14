import { AuthProvider, useAuth } from './auth/AuthContext.jsx';
import NavBar from './components/NavBar.jsx';
import { useHashRoute } from './hooks/useHashRoute.js';
import ApiTester from './pages/ApiTester.jsx';
import AuditLogPage from './pages/AuditLogPage.jsx';
import ChangePasswordPage from './pages/ChangePasswordPage.jsx';
import EmployeesPage from './pages/EmployeesPage.jsx';
import OptionsPage from './pages/OptionsPage.jsx';
import OtherRolePage from './pages/OtherRolePage.jsx';
import ProjectDetailPage from './pages/ProjectDetailPage.jsx';
import ProjectsPage from './pages/ProjectsPage.jsx';
import SignInPage from './pages/SignInPage.jsx';
import TeamPage from './pages/TeamPage.jsx';
import { SettingsProvider } from './settings/SettingsContext.jsx';

/** The page for the current address and role, below the navigation bar. */
function pageFor(user, section, id) {
  if (section === 'options') {
    return <OptionsPage tab={id} />;
  }
  if (user.role === 'EMPLOYEE') {
    return <OtherRolePage />;
  }
  const isAdmin = user.role === 'ADMIN';
  if (section === 'employees') {
    return isAdmin ? <EmployeesPage /> : <TeamPage />;
  }
  if (section === 'audit' && isAdmin) {
    return <AuditLogPage />;
  }
  if (section === 'projects' && id) {
    return <ProjectDetailPage key={id} projectId={id} />;
  }
  return <ProjectsPage />;
}

/**
 * Chooses the screen: sign-in when signed out, the forced password change for the premade admin, then the
 * navigation bar and the page for the address. #/tester is the developer API tester.
 * @returns {JSX.Element} The current screen.
 */
function Routes() {
  const { user } = useAuth();
  const [section = 'projects', id] = useHashRoute();

  if (section === 'tester') {
    return <ApiTester />;
  }
  if (!user) {
    return <SignInPage />;
  }
  if (user.passwordChangeRequired) {
    return <ChangePasswordPage />;
  }
  return (
    <SettingsProvider signedIn>
      <NavBar section={section} user={user} />
      {pageFor(user, section, id)}
    </SettingsProvider>
  );
}

function App() {
  return (
    <AuthProvider>
      <Routes />
    </AuthProvider>
  );
}

export default App;
