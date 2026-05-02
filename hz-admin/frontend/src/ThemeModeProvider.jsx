import { createContext, useContext, useMemo, useState } from 'react';
import { ThemeProvider, createTheme, CssBaseline } from '@mui/material';

const Ctx = createContext({ mode: 'light', toggle: () => {} });

/**
 * App-wide theme with Purple and Orange color scheme.
 * Purple: Primary brand color
 * Orange: Secondary accent color
 *
 * Initial mode is read from localStorage so a user's choice survives a reload;
 * default is dark, which is the right pick for an ops dashboard.
 */
export function ThemeModeProvider({ children }) {
  const [mode, setMode] = useState(() => {
    const saved = typeof window !== 'undefined' ? window.localStorage.getItem('hz-admin.theme-mode') : null;
    return saved === 'light' || saved === 'dark' ? saved : 'dark';
  });

  const theme = useMemo(() => createTheme({
    palette: {
      mode,
      primary: {
        main: mode === 'dark' ? '#A78BFA' : '#7C3AED',  // Purple: light in dark mode, dark in light mode
        light: '#C4B5FD',
        dark: '#6D28D9',
        contrastText: '#FFFFFF',
      },
      secondary: {
        main: mode === 'dark' ? '#FB923C' : '#F97316',  // Orange: light in dark mode, dark in light mode
        light: '#FDBA74',
        dark: '#EA580C',
        contrastText: '#FFFFFF',
      },
      background: mode === 'dark'
        ? { default: '#0F172A', paper: '#1E293B' }
        : { default: '#F8FAFC', paper: '#FFFFFF' },
      divider: mode === 'dark' ? 'rgba(255,255,255,0.1)' : 'rgba(0,0,0,0.08)',
      text: {
        primary: mode === 'dark' ? '#F1F5F9' : '#0F172A',
        secondary: mode === 'dark' ? '#CBD5E1' : '#64748B',
      },
    },
    shape: { borderRadius: 8 },
    typography: {
      fontFamily: '-apple-system,BlinkMacSystemFont,"Segoe UI","Inter",Roboto,Helvetica,Arial,sans-serif',
      h3: { fontWeight: 700, letterSpacing: '-0.01em' },
      h4: { fontWeight: 700, letterSpacing: '-0.01em' },
      h5: { fontWeight: 700, letterSpacing: '-0.01em' },
      h6: { fontWeight: 600 },
      subtitle1: { fontWeight: 600 },
      overline: { letterSpacing: '0.08em', fontWeight: 600 },
      button: { textTransform: 'none', fontWeight: 600 },
    },
    components: {
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: {
          root: { borderRadius: 8, fontWeight: 600 },
          contained: ({ theme: t }) => ({
            background: `linear-gradient(135deg, ${t.palette.primary.main} 0%, ${t.palette.primary.light} 100%)`,
            '&:hover': {
              background: `linear-gradient(135deg, ${t.palette.primary.dark} 0%, ${t.palette.primary.main} 100%)`,
            },
          }),
          containedSecondary: ({ theme: t }) => ({
            background: `linear-gradient(135deg, ${t.palette.secondary.main} 0%, ${t.palette.secondary.light} 100%)`,
            '&:hover': {
              background: `linear-gradient(135deg, ${t.palette.secondary.dark} 0%, ${t.palette.secondary.main} 100%)`,
            },
          }),
        },
      },
      MuiCard: {
        defaultProps: { variant: 'outlined' },
        styleOverrides: {
          root: { borderRadius: 12, transition: 'all 0.3s ease' },
        },
      },
      MuiPaper: {
        styleOverrides: {
          outlined: { borderRadius: 12 },
        },
      },
      MuiTableCell: {
        styleOverrides: {
          root: ({ theme: t }) => ({
            borderBottom: `1px solid ${t.palette.divider}`,
          }),
          head: ({ theme: t }) => ({
            backgroundColor: t.palette.mode === 'dark'
              ? 'rgba(167, 139, 250, 0.05)' : 'rgba(124, 58, 237, 0.05)',
            color: t.palette.text.secondary,
            fontSize: 12,
            textTransform: 'uppercase',
            letterSpacing: '0.04em',
            fontWeight: 600,
          }),
        },
      },
      MuiTableRow: {
        styleOverrides: {
          hover: ({ theme: t }) => ({
            '&:hover': {
              backgroundColor: t.palette.mode === 'dark'
                ? 'rgba(167, 139, 250, 0.08)' : 'rgba(124, 58, 237, 0.05)',
            },
          }),
        },
      },
      MuiChip: {
        styleOverrides: {
          root: { fontWeight: 500 },
          sizeSmall: { height: 22 },
          colorPrimary: ({ theme: t }) => ({
            backgroundColor: t.palette.mode === 'dark' ? 'rgba(167, 139, 250, 0.15)' : 'rgba(124, 58, 237, 0.1)',
            color: t.palette.primary.main,
          }),
        },
      },
      MuiTooltip: {
        styleOverrides: {
          tooltip: { fontSize: 12, padding: '6px 10px' },
        },
      },
      MuiListItemButton: {
        styleOverrides: {
          root: ({ theme: t }) => ({
            transition: 'all 0.2s ease',
            '&.Mui-selected': {
              backgroundColor: t.palette.mode === 'dark' ? 'rgba(167, 139, 250, 0.15)' : 'rgba(124, 58, 237, 0.1)',
              '& .MuiListItemIcon-root, & .MuiListItemText-primary': {
                color: t.palette.primary.main,
                fontWeight: 700,
              },
            },
          }),
        },
      },
      MuiAppBar: {
        styleOverrides: {
          root: ({ theme: t }) => ({
            backgroundColor: t.palette.background.paper,
            backgroundImage: 'none',
          }),
        },
      },
      MuiDrawer: {
        styleOverrides: {
          paper: ({ theme: t }) => ({
            transition: 'all 0.3s ease',
            backgroundColor: t.palette.background.paper,
          }),
        },
      },
    },
  }), [mode]);

  const value = useMemo(() => ({
    mode,
    toggle: () => setMode(m => {
      const next = m === 'light' ? 'dark' : 'light';
      try { window.localStorage.setItem('hz-admin.theme-mode', next); } catch { /* private mode */ }
      return next;
    }),
  }), [mode]);

  return (
    <Ctx.Provider value={value}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </Ctx.Provider>
  );
}

export function useThemeMode() {
  return useContext(Ctx);
}

