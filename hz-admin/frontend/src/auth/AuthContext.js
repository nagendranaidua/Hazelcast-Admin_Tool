import { createContext, useContext, useEffect, useState } from 'react';
import { api } from '../api/client';

const C = createContext({});

export default function AuthProvider({ children }) {
  const [me, setMe] = useState(null);
  const [loading, setLoading] = useState(true);

  const refresh = async () => {
    try {
      const { data } = await api.get('/api/auth/me');
      setMe(data);
    } catch {
      setMe(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); }, []);

  const login = async (username, password) => {
    const { data } = await api.post('/api/auth/login', { username, password });
    setMe(data);
    return data;
  };

  const logout = async () => {
    await api.post('/api/auth/logout');
    setMe(null);
  };

  const hasAnyRole = (...names) =>
    !!me && names.some(n => me.roles.includes(`ROLE_${n}`));

  return (
    <C.Provider value={{ me, loading, login, logout, refresh, hasAnyRole }}>
      {children}
    </C.Provider>
  );
}

export const useAuth = () => useContext(C);

