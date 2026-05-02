import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { Box, CircularProgress } from '@mui/material';
import { useAuth } from './auth/AuthContext';
import LoginPage from './pages/LoginPage';
import { ChangePasswordPage } from './pages/ChangePasswordPage';
import { AppShell } from './shell/AppShell';
import { DashboardPage } from './pages/DashboardPage';
import { ClustersPage } from './pages/ClustersPage';
import { ClusterOverviewPage } from './pages/ClusterOverviewPage';
import { MembersPage } from './pages/MembersPage';
import { MetricsPage } from './pages/MetricsPage';
import { MapsPage } from './pages/MapsPage';
import { MapBrowsePage } from './pages/MapBrowsePage';
import { QueuesPage } from './pages/QueuesPage';
import { QueueBrowsePage } from './pages/QueueBrowsePage';
import { TopicsPage } from './pages/TopicsPage';
import { TopicDetailPage } from './pages/TopicDetailPage';
import { SqlConsolePage } from './pages/SqlConsolePage';
import { OpsPage } from './pages/OpsPage';
import { UsersPage } from './pages/UsersPage';
import { AuditPage } from './pages/AuditPage';

function Protected({ children, roles }) {
  const { me, loading, hasAnyRole } = useAuth();
  const loc = useLocation();
  if (loading) return <Centered><CircularProgress /></Centered>;
  if (!me) return <Navigate to="/login" state={{ from: loc }} replace />;
  if (me.mustChangePassword && loc.pathname !== '/change-password')
    return <Navigate to="/change-password" replace />;
  if (roles && !hasAnyRole(...roles)) return <Navigate to="/" replace />;
  return children;
}

const Centered = ({ children }) => (
  <Box sx={{ height: '100vh', display: 'grid', placeItems: 'center' }}>{children}</Box>
);

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/change-password" element={<Protected><ChangePasswordPage /></Protected>} />
      <Route element={<Protected><AppShell /></Protected>}>
        <Route path="/"          element={<DashboardPage />} />
        <Route path="/clusters"             element={<ClustersPage />} />
        <Route path="/clusters/:id"         element={<ClusterOverviewPage />} />
        <Route path="/clusters/:id/members" element={<MembersPage />} />
        <Route path="/clusters/:id/metrics" element={<MetricsPage />} />
        <Route path="/clusters/:id/maps"            element={<MapsPage />} />
        <Route path="/clusters/:id/maps/:name"      element={<MapBrowsePage />} />
        <Route path="/clusters/:id/queues"           element={<QueuesPage />} />
        <Route path="/clusters/:id/queues/:name"     element={<QueueBrowsePage />} />
        <Route path="/clusters/:id/topics"           element={<TopicsPage />} />
        <Route path="/clusters/:id/topics/:name"     element={<TopicDetailPage />} />
        <Route path="/clusters/:id/sql"              element={<SqlConsolePage />} />
        <Route path="/clusters/:id/ops"     element={<Protected roles={['SUPER_ADMIN','CLUSTER_OPERATOR']}><OpsPage /></Protected>} />
        <Route path="/audit"     element={<AuditPage />} />
        <Route path="/users"     element={<Protected roles={['SUPER_ADMIN']}><UsersPage /></Protected>} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default App;

