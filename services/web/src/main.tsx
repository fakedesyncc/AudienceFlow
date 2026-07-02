import React from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { serviceWorkerEnabled } from './runtime';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);

if ('serviceWorker' in navigator && import.meta.env.PROD) {
  if (serviceWorkerEnabled) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`).catch(() => {
        // The app remains fully usable without offline shell caching.
      });
    });
  } else {
    navigator.serviceWorker.getRegistrations()
      .then((registrations) => registrations.forEach((registration) => void registration.unregister()))
      .catch(() => {
        // Ignore cleanup errors: the app must not depend on service worker state.
      });
  }
}
