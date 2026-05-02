import { useState, useEffect } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  AppBar, Toolbar, Typography, IconButton, Drawer, List, ListItemButton, ListItemIcon, ListItemText,
  Box, Tooltip, Avatar, Divider, Chip, Stack, Menu, MenuItem, Badge, InputAdornment, TextField,
  Button, CircularProgress, Paper,
} from '@mui/material';
import DashboardIcon from '@mui/icons-material/Dashboard';
import StorageIcon from '@mui/icons-material/Storage';
import HistoryIcon from '@mui/icons-material/History';
import PeopleIcon from '@mui/icons-material/People';
import LogoutIcon from '@mui/icons-material/Logout';
import Brightness4Icon from '@mui/icons-material/Brightness4';
import Brightness7Icon from '@mui/icons-material/Brightness7';
import MenuIcon from '@mui/icons-material/Menu';
import CloseIcon from '@mui/icons-material/Close';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import SearchIcon from '@mui/icons-material/Search';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline';
import { useAuth } from '../auth/AuthContext';
import { useThemeMode } from '../ThemeModeProvider';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';

const DRAWER_WIDTH = 260;
const DRAWER_WIDTH_COLLAPSED = 75;

export function AppShell() {
  const { me, hasAnyRole, logout } = useAuth();
  const { mode, toggle } = useThemeMode();
  const navigate = useNavigate();
  const location = useLocation();

  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [userMenuAnchor, setUserMenuAnchor] = useState(null);
  const [clusterMenuAnchor, setClusterMenuAnchor] = useState(null);
  const [selectedClusterId, setSelectedClusterId] = useState(null);
  const [currentTime, setCurrentTime] = useState(new Date());
  const [clusterSearch, setClusterSearch] = useState('');

  // Fetch clusters
  const clustersQuery = useQuery({
    queryKey: ['clusters'],
    queryFn: async () => (await api.get('/api/clusters')).data,
    refetchInterval: 30000,
  });

  // Update clock every second
  useEffect(() => {
    const timer = setInterval(() => setCurrentTime(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);

  const clusters = clustersQuery.data || [];
  const selectedCluster = clusters.find(c => c.id === selectedClusterId) || clusters[0];
  const filteredClusters = clusters.filter(c =>
    c.name.toLowerCase().includes(clusterSearch.toLowerCase())
  );

  const navigationItems = [
    { to: '/', label: 'Dashboard', icon: <DashboardIcon />, show: true },
    { to: '/clusters', label: 'Manage Clusters', icon: <StorageIcon />, show: true },
    { to: '/audit', label: 'Audit Log', icon: <HistoryIcon />, show: hasAnyRole('SUPER_ADMIN') },
    { to: '/users', label: 'Users', icon: <PeopleIcon />, show: hasAnyRole('SUPER_ADMIN') },
  ];

  const isActive = (to) =>
    to === '/' ? location.pathname === '/' : location.pathname === to || location.pathname.startsWith(to + '/');

  const handleLogout = async () => {
    setUserMenuAnchor(null);
    await logout();
    navigate('/login');
  };

  const timeString = currentTime.toLocaleString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: true,
  });

  return (
    <Box sx={{ display: 'flex', height: '100vh' }}>
      {/* AppBar */}
      <AppBar
        position="fixed"
        sx={{
          zIndex: theme => theme.zIndex.drawer + 1,
          borderBottom: '1px solid',
          borderColor: 'divider',
          backdropFilter: 'blur(10px)',
          backgroundColor: mode === 'dark'
            ? 'rgba(30, 41, 59, 0.8)'
            : 'rgba(255, 255, 255, 0.8)',
        }}
      >
        <Toolbar sx={{ gap: 2 }}>
          {/* Menu Toggle */}
          <Tooltip title={sidebarOpen ? 'Collapse sidebar' : 'Expand sidebar'}>
            <IconButton
              onClick={() => setSidebarOpen(!sidebarOpen)}
              sx={{ transition: 'all 0.3s ease' }}
            >
              {sidebarOpen ? <CloseIcon /> : <MenuIcon />}
            </IconButton>
          </Tooltip>

          {/* Brand */}
          <Stack direction="row" alignItems="baseline" spacing={1} sx={{ minWidth: 200 }}>
            <Typography
              variant="h6"
              sx={{
                fontWeight: 700,
                lineHeight: 1,
                background: 'linear-gradient(135deg, #7C3AED 0%, #F97316 100%)',
                backgroundClip: 'text',
                WebkitBackgroundClip: 'text',
                WebkitTextFillColor: 'transparent',
              }}
            >
              Hazelcast
            </Typography>
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: { xs: 'none', sm: 'inline' }, fontWeight: 500 }}
            >
              Management Center
            </Typography>
          </Stack>

          <Box sx={{ flexGrow: 1 }} />

          {/* Links */}
          <Stack direction="row" spacing={0.5} sx={{ display: { xs: 'none', md: 'flex' } }}>
            <Tooltip title="Documentation">
              <IconButton size="small" onClick={() => window.open('https://docs.hazelcast.com', '_blank')}>
                <Typography variant="caption" sx={{ fontWeight: 600 }}>Docs</Typography>
              </IconButton>
            </Tooltip>
            <Tooltip title="Toggle Dev Mode">
              <Button
                size="small"
                variant="text"
                sx={{ textTransform: 'none', fontSize: 12, fontWeight: 600 }}
              >
                Dev Mode
              </Button>
            </Tooltip>
          </Stack>

          {/* Cluster Selector */}
          <Tooltip title={selectedCluster ? `Selected: ${selectedCluster.name}` : 'Select a cluster'}>
            <Button
              onClick={(e) => setClusterMenuAnchor(e.currentTarget)}
              sx={{
                textTransform: 'none',
                fontWeight: 600,
                px: 2,
                gap: 1,
              }}
              endIcon={<ExpandMoreIcon />}
            >
              <Badge
                color="primary"
                variant="dot"
                invisible={!selectedCluster}
                sx={{ width: 12, height: 12 }}
              >
                {selectedCluster ? (
                  <span>{selectedCluster.name}</span>
                ) : (
                  <span style={{ color: '#FB923C', fontWeight: 700 }}>None selected</span>
                )}
              </Badge>
            </Button>
          </Tooltip>

          {/* Theme Toggle */}
          <Tooltip title={mode === 'dark' ? 'Light theme' : 'Dark theme'}>
            <IconButton onClick={toggle}>
              {mode === 'dark' ? <Brightness7Icon /> : <Brightness4Icon />}
            </IconButton>
          </Tooltip>

          {/* User Menu */}
          <Tooltip title={me?.username || 'User'}>
            <IconButton
              onClick={(e) => setUserMenuAnchor(e.currentTarget)}
              sx={{ ml: 1 }}
            >
              <Avatar
                sx={{
                  width: 36,
                  height: 36,
                  background: 'linear-gradient(135deg, #7C3AED 0%, #F97316 100%)',
                  fontSize: 14,
                  fontWeight: 700,
                }}
              >
                {me?.username?.[0]?.toUpperCase() ?? '?'}
              </Avatar>
            </IconButton>
          </Tooltip>

          {/* Logout */}
          <Tooltip title="Logout">
            <IconButton onClick={handleLogout} size="small">
              <LogoutIcon />
            </IconButton>
          </Tooltip>
        </Toolbar>
      </AppBar>

      {/* Cluster Selector Menu */}
      <Menu
        anchorEl={clusterMenuAnchor}
        open={Boolean(clusterMenuAnchor)}
        onClose={() => setClusterMenuAnchor(null)}
        PaperProps={{
          sx: { width: 350, maxHeight: 400 },
        }}
      >
        <Box sx={{ p: 1.5 }}>
          <TextField
            placeholder="Search clusters..."
            value={clusterSearch}
            onChange={(e) => setClusterSearch(e.target.value)}
            size="small"
            fullWidth
            InputProps={{
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon sx={{ fontSize: 18 }} />
                </InputAdornment>
              ),
            }}
          />
        </Box>
        <Divider />
        {clustersQuery.isLoading ? (
          <Box sx={{ p: 2, display: 'flex', justifyContent: 'center' }}>
            <CircularProgress size={24} />
          </Box>
        ) : clustersQuery.isError ? (
          <Box sx={{ p: 2, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 1 }}>
            <ErrorOutlineIcon color="error" />
            <Typography variant="caption" color="error">Failed to load clusters</Typography>
          </Box>
        ) : filteredClusters.length === 0 ? (
          <MenuItem disabled>
            <Typography variant="body2" color="text.secondary">No clusters found</Typography>
          </MenuItem>
        ) : (
          filteredClusters.map(cluster => (
            <MenuItem
              key={cluster.id}
              onClick={() => {
                setSelectedClusterId(cluster.id);
                setClusterMenuAnchor(null);
                setClusterSearch('');
              }}
              selected={selectedClusterId === cluster.id}
              sx={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 0.5 }}
            >
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                {cluster.name}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {cluster.environment} • v{cluster.majorVersion}
              </Typography>
            </MenuItem>
          ))
        )}
      </Menu>

      {/* Sidebar */}
      <Drawer
        variant="permanent"
        sx={{
          width: sidebarOpen ? DRAWER_WIDTH : DRAWER_WIDTH_COLLAPSED,
          flexShrink: 0,
          transition: 'all 0.3s ease',
          '& .MuiDrawer-paper': {
            width: sidebarOpen ? DRAWER_WIDTH : DRAWER_WIDTH_COLLAPSED,
            boxSizing: 'border-box',
            borderRight: '1px solid',
            borderColor: 'divider',
            transition: 'all 0.3s ease',
            overflowX: 'hidden',
          },
        }}
      >
        <Toolbar />
        <Box sx={{ overflow: 'auto', display: 'flex', flexDirection: 'column', height: '100%' }}>
          {/* Navigation */}
          <List sx={{ pt: 1 }}>
            {navigationItems.filter(item => item.show).map(item => {
              const active = isActive(item.to);
              return (
                <Tooltip
                  key={item.to}
                  title={!sidebarOpen ? item.label : ''}
                  placement="right"
                >
                  <ListItemButton
                    onClick={() => navigate(item.to)}
                    selected={active}
                    sx={{
                      mx: 1,
                      borderRadius: 1,
                      mb: 0.5,
                      transition: 'all 0.2s ease',
                      justifyContent: sidebarOpen ? 'flex-start' : 'center',
                      '&.Mui-selected': {
                        backgroundColor: mode === 'dark'
                          ? 'rgba(167, 139, 250, 0.15)'
                          : 'rgba(124, 58, 237, 0.1)',
                        '& .MuiListItemIcon-root, & .MuiListItemText-primary': {
                          color: 'primary.main',
                          fontWeight: 700,
                        },
                      },
                    }}
                  >
                    <ListItemIcon sx={{ minWidth: sidebarOpen ? 36 : 0 }}>
                      {item.icon}
                    </ListItemIcon>
                    {sidebarOpen && <ListItemText primary={item.label} />}
                  </ListItemButton>
                </Tooltip>
              );
            })}
          </List>

          <Box sx={{ flexGrow: 1 }} />

          {/* Sidebar Footer */}
          <Divider />
          {sidebarOpen && (
            <Paper
              elevation={0}
              sx={{
                p: 2,
                m: 1,
                borderRadius: 2,
                backgroundColor: mode === 'dark'
                  ? 'rgba(167, 139, 250, 0.08)'
                  : 'rgba(124, 58, 237, 0.05)',
                border: '1px solid',
                borderColor: mode === 'dark'
                  ? 'rgba(167, 139, 250, 0.1)'
                  : 'rgba(124, 58, 237, 0.1)',
              }}
            >
              <Typography variant="overline" color="text.secondary" sx={{ display: 'block', mb: 1 }}>
                Management Center
              </Typography>
              <Typography variant="caption" sx={{ fontWeight: 600, display: 'block', mb: 2 }}>
                v4.2021.12
              </Typography>

              <Divider sx={{ my: 1.5 }} />

              <Typography variant="overline" color="text.secondary" sx={{ display: 'block', mb: 0.5, fontSize: 10 }}>
                Current Time
              </Typography>
              <Typography variant="caption" sx={{ display: 'block', fontFamily: 'monospace', fontSize: 10, mb: 2 }}>
                {timeString}
              </Typography>

              <Divider sx={{ my: 1.5 }} />

              <Typography variant="overline" color="text.secondary" sx={{ display: 'block', mb: 0.5, fontSize: 10 }}>
                Signed in as
              </Typography>
              <Typography variant="caption" sx={{ fontWeight: 600, display: 'block', mb: 1 }}>
                {me?.username ?? '—'}
              </Typography>
              <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                {(me?.roles ?? []).slice(0, 2).map(role => (
                  <Chip
                    key={role}
                    size="small"
                    variant="outlined"
                    label={role.replace('ROLE_', '').replace('_', ' ').toLowerCase()}
                    sx={{ fontSize: 9, height: 18 }}
                  />
                ))}
              </Stack>
            </Paper>
          )}
        </Box>
      </Drawer>

      {/* Main Content */}
      <Box
        component="main"
        sx={{
          flexGrow: 1,
          p: 3,
          mt: 8,
          overflow: 'auto',
          bgcolor: 'background.default',
          transition: 'all 0.3s ease',
        }}
      >
        <Outlet />
      </Box>
    </Box>
  );
}
