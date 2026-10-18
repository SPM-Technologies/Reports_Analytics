import { chromium, type FullConfig } from '@playwright/test';

/** Users the browser tests sign in as (demo accounts, see the project guide). */
export const USERS = {
  /** administrator of H001 only: the 1,00,002 original patients */
  h001: { username: 'h001.admin', password: 'Demo@1234', state: 'e2e/.auth/h001.json' },
  h002: { username: 'h002.reports', password: 'Demo@1234', state: 'e2e/.auth/h002.json' },
  group: { username: 'group.reports', password: 'Demo@1234', state: 'e2e/.auth/group.json' },
};

/** Signs each test user in once through the real sign-in page and keeps the session cookies. */
export default async function globalSetup(config: FullConfig) {
  const baseURL = config.projects[0].use.baseURL as string;
  const browser = await chromium.launch({ channel: 'msedge' });
  for (const u of Object.values(USERS)) {
    const page = await browser.newPage({ baseURL });
    await page.goto('login');
    await page.getByLabel('Username').fill(u.username);
    await page.getByLabel('Password').fill(u.password);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await page.waitForURL(url => !url.pathname.endsWith('/login'));
    await page.context().storageState({ path: u.state });
    await page.close();
  }
  await browser.close();
}
