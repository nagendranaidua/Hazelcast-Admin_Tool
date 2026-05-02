import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  AppBar, Toolbar, Typography, IconButton, Drawer, List, ListItemButton, ListItemIcon, ListItemText,
  Box, Tooltip, Avatar, Divider, Chip, Stack,
} from '@mui/material';
import DashboardIcon from '@mui/icons-material/Dashboard';
import StorageIcon from '@mui/icons-material/Storage';
import HistoryIcon from '@mui/icons-material/History';
import PeopleIcon from '@mui/icons-material/People';
import LogoutIcon from '@mui/icons-material/Logout';
import Brightness4Icon from '@mui/icons-material/Brightness4';
import Brightness7Icon from '@mui/icons-material/Brightness7';
import HubIcon from '@mui/icons-material/Hub';
import { useAuth } from '../auth/AuthContext';
import { useThemeMode } from '../ThemeModeProvider';

const DRAWER_WIDTH = 240;

/**
 * App chrome: fixed AppBar with brand + theme toggle + sign-out, persistent drawer with
 * role-filtered navigation. The drawer's active item is computed from the current pathname
 * so users can see at a glance where they are without losing context.
 *
 * <p>Polished from the original to:
 *   - highlight the active route in the drawer (was clickable but no visual state),
 *   - separate brand from page title in the AppBar (was a single h6),
 *   - render the user's roles as compact chips at the bottom of the drawer instead of
 *     a list of typography elements,
 *   - upsize the user avatar slightly and add a username tooltip with role hint.
 */
export function AppShell() {
  const { me, hasAnyRole, logout } = useAuth();
  const { mode, toggle } = useThemeMode();
  const nav = useNavigate();
  const loc = useLocation();

  const items = [
    { to: '/',         label: 'Dashboard', icon: <DashboardIcon />, show: true },
    { to: '/clusters', label: 'Clusters',  icon: <StorageIcon />,   show: true },
    { to: '/audit',    label: 'Audit Log', icon: <HistoryIcon />,   show: hasAnyRole('SUPER_ADMIN') },
    { to: '/users',    label: 'Users',     icon: <PeopleIcon />,    show: hasAnyRole('SUPER_ADMIN') },
  ];

  // Active item = current path either equals item.to or starts with `${item.to}/`. The
  // root path matches only itself so / doesn't shadow /clusters etc.
  const isActive = (to: string) =>
    to === '/' ? loc.pathname === '/' : loc.pathname === to || loc.pathname.startsWith(to + '/');

  return (
    <Box sx={{ display: 'flex', height: '100vh' }}>
      <AppBar position="fixed" color="default" elevation={0}
              sx={{ zIndex: t => t.zIndex.drawer + 1, borderBottom: '1px solid', borderColor: 'divider' }}>
        <Toolbar sx={{ gap: 2 }}>
          <HubIcon color="primary" />
          <Stack direction="row" alignItems="baseline" spacing={1}>
            <Typography variant="h6" sx={{ fontWeight: 700, lineHeight: 1 }}>Hazelcast Admin</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ display: { xs: 'none', sm: 'inline' } }}>
              cluster operations
            </Typography>
          </Stack>
          <Box sx={{ flexGrow: 1 }} />
          <Tooltip title={mode === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}>
            <IconButton onClick={toggle}>
              {mode === 'dark' ? <Brightness7Icon /> : <Brightness4Icon />}
            </IconButton>
          </Tooltip>
          <Tooltip title={me?.username ? `${me.username}` : ''}>
            <Avatar sx={{ width: 32, height: 32, ml: 1, bgcolor: 'primary.main', fontSize: 14 }}>
              {me?.username?.[0]?.toUpperCase() ?? '?'}
            </Avatar>
          </Tooltip>
          <Tooltip title="Sign out">
            <IconButton onClick={async () => { await logout(); nav('/login'); }}>
              <LogoutIcon />
            </IconButton>
          </Tooltip>
        </Toolbar>
      </AppBar>

      <Drawer variant="permanent" sx={{
        width: DRAWER_WIDTH, flexShrink: 0,
        '& .MuiDrawer-paper': {
          width: DRAWER_WIDTH, boxSizing: 'border-box',
          borderRight: '1px solid', borderColor: 'divider',
        },
      }}>
        <Toolbar />
        <Box sx={{ overflow: 'auto', display: 'flex', flexDirection: 'column', height: '100%' }}>
          <List sx={{ pt: 1 }}>
            {items.filter(i => i.show).map(i => {
              const active = isActive(i.to);
              return (
                <ListItemButton
                  key={i.to}
                  onClick={() => nav(i.to)}
                  selected={active}
                  sx={{
                    mx: 1, borderRadius: 1, mb: 0.5,
                    '&.Mui-selected': {
                      bgcolor: 'action.selected',
                      '& .MuiListItemIcon-root, & .MuiListItemText-primary': { color: 'primary.main', fontWeight: 600 },
                    },
                  }}>
                  <ListItemIcon sx={{ minWidth: 36 }}>{i.icon}</ListItemIcon>
                  <ListItemText primary={i.label} />
                </ListItemButton>
              );
            })}
          </List>

          <Box sx={{ flexGrow: 1 }} />
          <Divider />
          <Box sx={{ p: 2 }}>
            <Typography variant="overline" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
              Signed in as
            </Typography>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>{me?.username ?? '—'}</Typography>
            <Stack direction="row" spacing={0.5} sx={{ mt: 1 }} flexWrap="wrap" useFlexGap>
              {(me?.roles ?? []).map(r => (
                <Chip key={r} size="small" variant="outlined"
                      label={r.replace('ROLE_', '').replace('_', ' ').toLowerCase()}
                      sx={{ fontSize: 10, textTransform: 'capitalize' }} />
              ))}
            </Stack>
          </Box>
        </Box>
      </Drawer>

      <Box component="main" sx={{
        flexGrow: 1, p: 3, mt: 8, overflow: 'auto',
        bgcolor: 'background.default',
      }}>
        <Outlet />
      </Box>
    </Box>
  );
}
