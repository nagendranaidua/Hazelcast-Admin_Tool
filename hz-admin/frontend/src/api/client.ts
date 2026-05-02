import axios from 'axios';

// Vite dev proxy + Apache prod reverse-proxy both route /api -> backend.
// withCredentials so the browser session cookie + CSRF cookie travel with each call.
export const api = axios.create({
  baseURL: '/',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
});

// Single-source error normalization for the UI's error toast layer.
api.interceptors.response.use(
  r => r,
  err => {
    if (err.response?.status === 412 && err.response?.data?.code === 'MUST_CHANGE_PASSWORD') {
      // Surface a stable shape; route guard reads this.
      err.mustChangePassword = true;
    }
    return Promise.reject(err);
  }
);
