import { AuthProvider, useAuth } from './auth/AuthContext.jsx';
import NavBar from './components/NavBar.jsx';
import { useHashRoute } from './hooks/useHashRoute.js';
import ApiTester from './pages/ApiTester.jsx';
import AuditLogPage from './pages/AuditLogPage.jsx';
import ChangePasswordPage from './pages/ChangePasswordPage.jsx';
import EmployeesPage from './pages/EmployeesPage.jsx';
import OtherRolePage from './pages/OtherRolePage.jsx';
import ProjectDetailPage from './pages/ProjectDetailPage.jsx';
import ProjectsPage from './pages/ProjectsPage.jsx';
import SignInPage from './pages/SignInPage.jsx';

/**
 * Chooses the screen: sign-in when signed out, the forced password change for the premade admin, the admin
 * views for admins, and a placeholder for other roles until their views exist. #/tester is the developer API tester.
 * @returns {JSX.Element} The current screen.
 */
function Routes() {
  const { user, signOut } = useAuth();
  const [section = 'projects', id] = useHashRoute();

  if (section === 'tester') {
    return <ApiTester />;
  }
  if (!user) {
    return <SignInPage />;
  }
  if (user.passwordChangeRequired) {
    return <ChangePasswordPage forced />;
  }
  const changingPassword = section === 'account' && id === 'password';
  if (user.role !== 'ADMIN') {
    return changingPassword ? <ChangePasswordPage forced={false} /> : <OtherRolePage />;
  }

  let page;
  if (changingPassword) {
    page = <ChangePasswordPage forced={false} />;
  } else if (section === 'employees') {
    page = <EmployeesPage />;
  } else if (section === 'audit') {
    page = <AuditLogPage />;
  } else if (section === 'projects' && id) {
    page = <ProjectDetailPage key={id} projectId={id} />;
  } else {
    page = <ProjectsPage />;
  }
  return (
    <>
      <NavBar section={section} user={user} onSignOut={signOut} />
      {page}
    </>
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
