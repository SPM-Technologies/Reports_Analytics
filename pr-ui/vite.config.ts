import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Relative base: the app works under any context path (served at <context>/app/ by Tomcat).
// `npm run dev` proxies API calls to the running Tomcat, so UI changes need no WAR rebuild.
export default defineConfig({
  plugins: [react()],
  base: './',
  build: {
    outDir: 'target/classes/META-INF/resources/app',
    emptyOutDir: true,
    chunkSizeWarningLimit: 900,
  },
  server: {
    port: 5173,
    proxy: { '/patient-registration/api': 'http://localhost:8080' },
  },
});
