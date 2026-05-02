import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { Provider } from 'react-redux';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CssBaseline } from '@mui/material';
import { App } from './App';
import { AuthProvider } from './auth/AuthContext';
import { ThemeModeProvider } from './ThemeModeProvider';
import store from './store';

const qc = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false, staleTime: 5000 } },
});

const rootEl = document.getElementById('root');
if (rootEl) {
  ReactDOM.createRoot(rootEl).render(
    <React.StrictMode>
      <Provider store={store}>
        <QueryClientProvider client={qc}>
          <ThemeModeProvider>
            <CssBaseline />
            <BrowserRouter>
              <AuthProvider>
                <App />
              </AuthProvider>
            </BrowserRouter>
          </ThemeModeProvider>
        </QueryClientProvider>
      </Provider>
    </React.StrictMode>
  );
}

