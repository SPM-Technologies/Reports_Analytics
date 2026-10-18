import { defineConfig } from '@playwright/test';

// End-to-end tests of the deployed app (the pr-app container must run the current code).
// Uses the installed Microsoft Edge, so no browser download is needed.  Run:  npm run e2e
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.APP_URL ?? 'http://localhost:8080/patient-registration/',
    channel: 'msedge',
    headless: true,
    viewport: { width: 1500, height: 950 },
    acceptDownloads: true,
    screenshot: 'only-on-failure',
    // signed in as the administrator of H001 (individual tests switch user where needed)
    storageState: 'e2e/.auth/h001.json',
  },
});
