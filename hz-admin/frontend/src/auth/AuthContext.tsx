import { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { api } from '../api/client';

export type Me = {
  username: string;
  fullName: string;
  email: string;
  roles: string[];                // "ROLE_SUPER_ADMIN", ...
  mustChangePassword: boolean;
  authSource: string;
};

type Ctx = {
  me: Me | null;
  loading: boolean;
  login: (u: string, p: string) => Promise<Me>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
  hasAnyRole: (...names: string[]) => boolean;
};

const C = createContext<Ctx>({} as Ctx);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = async () => {
    try {
      const { data } = await api.get<Me>('/api/auth/me');
      setMe(data);
    } catch {
      setMe(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void refresh(); }, []);

  const login = async (username: string, password: string) => {
    const { data } = await api.post<Me>('/api/auth/login', { username, password });
    setMe(data);
    return data;
  };

  const logout = async () => {
    await api.post('/api/auth/logout');
    setMe(null);
  };

  const hasAnyRole = (...names: string[]) =>
    !!me && names.some(n => me.roles.includes(`ROLE_${n}`));

  return (
    <C.Provider value={{ me, loading, login, logout, refresh, hasAnyRole }}>
      {children}
    </C.Provider>
  );
}

export const useAuth = () => useContext(C);
