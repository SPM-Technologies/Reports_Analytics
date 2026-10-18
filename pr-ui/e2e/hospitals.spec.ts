import { expect, test } from '@playwright/test';
import { USERS } from './global-setup';

// Multi-hospital isolation as users experience it (architecture 10.1): each user sees only the data of
// their own hospitals, in the JSP screens and in Analytics. Demo data: H001 1,00,002 patients,
// H002 20,000, H003 10,000.

const results = (page: import('@playwright/test').Page) => page.getByLabel('Report results');
const totalsRow = (page: import('@playwright/test').Page) => results(page).locator('tfoot tr');

async function openTemplate(page: import('@playwright/test').Page, name: string) {
  await page.getByRole('navigation', { name: 'Reports' }).getByRole('button', { name }).click();
  await expect(page.getByRole('heading', { level: 1 })).toContainText(name);
  await expect(page.locator('.meta')).toContainText('Data as of');
}

test.describe('a reports user of City Hospital (H002)', () => {
  test.use({ storageState: USERS.h002.state });

  test('sees only H002 in Analytics and cannot register patients', async ({ page }) => {
    await page.goto('app/');
    await expect(page.locator('.userbar')).toContainText('H002 - City Hospital');
    const nav = page.getByRole('navigation', { name: 'Application' });
    await expect(nav.getByRole('link', { name: 'New Registration' })).toHaveCount(0);
    await openTemplate(page, 'Department Summary (Report 2)');
    await expect(totalsRow(page)).toContainText('20,000');
    await openTemplate(page, 'Patient Register (Report 1)');
    await expect(page.locator('.meta')).toContainText('20,000 rows');
    await expect(results(page).locator('tbody')).toContainText('H002-REG');
    await expect(results(page).locator('tbody')).not.toContainText('H001');
  });

  test('JSP register shows only H002 patients with masked mobiles', async ({ page }) => {
    await page.goto('reports/register');
    await expect(page.locator('main')).toContainText('20,000');
    await expect(page.locator('table tbody')).toContainText('H002-REG');
    await expect(page.locator('table tbody')).toContainText('XXXXXX');
    await page.goto('patient/new');
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Error 403');
  });
});

test.describe('a group reports user (H001, H002, H003)', () => {
  test.use({ storageState: USERS.group.state });

  test('sees all three hospitals and can compare them', async ({ page }) => {
    await page.goto('app/');
    await expect(page.locator('.userbar')).toContainText('3 hospitals');
    await openTemplate(page, 'Department Summary (Report 2)');
    await expect(totalsRow(page)).toContainText('1,30,002');
    // the Hospital field is offered only to users of several hospitals
    await expect(page.locator('#add-column')).toContainText('Hospital');
  });

  test('switches the working hospital in the JSP header', async ({ page }) => {
    await page.goto('');
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Main Hospital');
    await page.getByLabel('Hospital').selectOption({ label: 'H003 - Sunrise Hospital' });
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Sunrise Hospital');
    await expect(page.locator('.stat').first()).toContainText('10,000');
    await page.getByLabel('Hospital').selectOption({ label: 'H001 - Main Hospital' });
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Main Hospital');
  });
});

test.describe('nobody signed in', () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test('is sent to the sign-in page, and a wrong password is refused', async ({ page }) => {
    await page.goto('app/');
    await expect(page).toHaveURL(/\/login$/);
    await page.getByLabel('Username').fill('h002.reports');
    await page.getByLabel('Password').fill('not-the-password');
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.locator('.alert-error')).toContainText('Wrong username or password');
    await page.getByLabel('Username').fill('h002.reports');
    await page.getByLabel('Password').fill(USERS.h002.password);
    await page.getByRole('button', { name: 'Sign in' }).click();
    // back to the page first asked for, now as the H002 user
    await expect(page).toHaveURL(/\/app\//);
    await expect(page.locator('.userbar')).toContainText('H002 - City Hospital');
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.locator('.alert-success')).toContainText('signed out');
  });
});
