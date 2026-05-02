import { createContext, useContext, useMemo, useState, ReactNode } from 'react';
import { ThemeProvider, createTheme, CssBaseline } from '@mui/material';

type Mode = 'light' | 'dark';
const Ctx = createContext<{ mode: Mode; toggle: () => void }>({ mode: 'light', toggle: () => {} });

/**
 * App-wide theme. Single source of truth for colour, typography, spacing, and the small
 * component overrides that give the admin tool its consistent look (table rows, cards,
 * outlined inputs).
 *
 * <p>Initial mode is read from localStorage so a user's choice survives a reload; default
 * is dark, which is the right pick for an ops dashboard people leave open all day.
 */
export function ThemeModeProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<Mode>(() => {
    const saved = typeof window !== 'undefined' ? window.localStorage.getItem('hz-admin.theme-mode') : null;
    return saved === 'light' || saved === 'dark' ? saved : 'dark';
  });

  const theme = useMemo(() => createTheme({
    palette: {
      mode,
      primary:   { main: mode === 'dark' ? '#5b9eff' : '#0a66ff' },
      secondary: { main: '#ff8a00' },
      background: mode === 'dark'
        ? { default: '#0e1116', paper: '#161a22' }
        : { default: '#f7f8fa', paper: '#ffffff' },
      divider: mode === 'dark' ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.08)',
    },
    shape: { borderRadius: 8 },
    typography: {
      fontFamily: '-apple-system,BlinkMacSystemFont,"Segoe UI","Inter",Roboto,Helvetica,Arial,sans-serif',
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
          root: { borderRadius: 8 },
        },
      },
      MuiCard: {
        defaultProps: { variant: 'outlined' },
        styleOverrides: {
          root: { borderRadius: 12 },
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
              ? 'rgba(255,255,255,0.02)' : 'rgba(0,0,0,0.02)',
            color: t.palette.text.secondary,
            fontSize: 12,
            textTransform: 'uppercase',
            letterSpacing: '0.04em',
          }),
        },
      },
      MuiTableRow: {
        styleOverrides: {
          hover: ({ theme: t }) => ({
            '&:hover': {
              backgroundColor: t.palette.mode === 'dark'
                ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.025)',
            },
          }),
        },
      },
      MuiChip: {
        styleOverrides: {
          root: { fontWeight: 500 },
          sizeSmall: { height: 22 },
        },
      },
      MuiTooltip: {
        styleOverrides: {
          tooltip: { fontSize: 12, padding: '6px 10px' },
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

export const useThemeMode = () => useContext(Ctx);
