import { type Page, expect, test } from '@playwright/test';

/**
 * Records file saves instead of letting headless Edge write them: under automation on Windows,
 * Edge crashes when a download completes (real users' downloads are unaffected). The test then
 * checks the exact export response the UI received and that the UI saves it under the right name.
 */
async function recordSaves(page: Page) {
  await page.addInitScript(() => {
    const w = window as unknown as { __saves: { name: string; href: string }[] };
    w.__saves = [];
    HTMLAnchorElement.prototype.click = function click(this: HTMLAnchorElement) {
      if (this.download) {
        w.__saves.push({ name: this.download, href: this.href });
        return;
      }
      HTMLElement.prototype.click.call(this);
    };
  });
  await page.reload();
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
}

const saves = (page: Page) =>
  page.evaluate(() => (window as unknown as { __saves: { name: string; href: string }[] }).__saves);

/** Bytes of the file the UI saved (read back from its blob URL), as latin1 so binary survives. */
const savedBytes = (page: Page, href: string) =>
  page.evaluate(async url => {
    const bytes = new Uint8Array(await (await fetch(url)).arrayBuffer());
    let s = '';
    bytes.forEach(b => { s += String.fromCharCode(b); });
    return s;
  }, href);

// User journeys through the Analytics UI against the live app (1 lakh patients in ClickHouse).

const results = (page: Page) => page.getByLabel('Report results');
const totalsRow = (page: Page) => results(page).locator('tfoot tr');
const meta = (page: Page) => page.locator('.meta');

async function openTemplate(page: Page, name: string) {
  await page.getByRole('navigation', { name: 'Reports' }).getByRole('button', { name }).click();
  await expect(page.getByRole('heading', { level: 1 })).toContainText(name);
  await expect(meta(page)).toContainText('Data as of');
}

test.beforeEach(async ({ page }) => {
  // every test starts clean; unexpected browser dialogs fail the test unless a test handles them
  await page.goto('app/');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
});

test('opens with a template and links back to the JSP screens', async ({ page }) => {
  await expect(meta(page)).toContainText('Data as of');
  const nav = page.getByRole('navigation', { name: 'Application' });
  await expect(nav.getByRole('link', { name: 'New Registration' })).toHaveAttribute('href', /\/patient-registration\/patient\/new$/);
  await expect(nav.getByRole('link', { name: 'Analytics' })).toHaveAttribute('aria-current', 'page');
});

test('Report 2 matches the live totals', async ({ page }) => {
  await openTemplate(page, 'Department Summary (Report 2)');
  await expect(results(page).locator('tbody tr')).toHaveCount(10);
  await expect(totalsRow(page)).toContainText('1,00,002');
  await expect(totalsRow(page)).toContainText('2,94,66,300.00');
});

test('Report 1 pages through 1 lakh rows and cannot be charted', async ({ page }) => {
  await openTemplate(page, 'Patient Register (Report 1)');
  await expect(meta(page)).toContainText('1,00,002 rows');
  await expect(page.getByRole('button', { name: 'Chart' })).toBeDisabled();
  const pager = page.getByRole('navigation', { name: 'Pages' });
  await expect(pager).toContainText('Page 1 of 2,001');
  await pager.getByRole('button', { name: /Next/ }).click();
  await expect(pager).toContainText('Page 2 of 2,001');
  await expect(results(page).locator('tbody tr').first()).toContainText('51');
  await pager.getByRole('button', { name: /Last/ }).click();
  await expect(pager).toContainText('Page 2,001 of 2,001');
  await expect(results(page).locator('tbody tr')).toHaveCount(2);   // 1,00,002 = 2,000 x 50 + 2
  await expect(pager.getByRole('button', { name: /Next/ })).toBeDisabled();
});

test('header click sorts on the server, toggling asc, desc and off', async ({ page }) => {
  await openTemplate(page, 'Department Summary (Report 2)');
  const header = results(page).getByRole('button', { name: /Patients/ }).first();
  await header.click();   // ascending
  await expect(results(page).locator('thead th[aria-sort="ascending"]')).toContainText('Patients');
  await expect(results(page).locator('tbody tr').first()).toContainText('Obstetrics');   // smallest department
  await header.click();   // descending
  await expect(results(page).locator('thead th[aria-sort="descending"]')).toContainText('Patients');
  await expect(results(page).locator('tbody tr').first()).toContainText('General Medicine');
  await header.click();   // off
  await expect(results(page).locator('thead th[aria-sort="ascending"], thead th[aria-sort="descending"]')).toHaveCount(0);
});

test('build a filtered summary and chart it', async ({ page }) => {
  await page.getByRole('button', { name: '+ New report' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('New report');
  // group by state, count patients, only insured women
  for (const c of ['Reg No', 'Reg Date', 'Patient Name', 'Gender', 'Department']) {
    await page.getByRole('button', { name: `Remove ${c}` }).click();
  }
  await page.getByLabel('Add column').selectOption({ label: 'State' });
  await page.getByLabel('Add measure').selectOption({ label: 'Patients' });
  await page.getByRole('button', { name: '+ Add filter' }).click();
  await page.getByLabel('Field').selectOption({ label: 'Gender' });
  await page.getByLabel('Value').fill('Female');
  await page.getByRole('button', { name: '+ Add filter' }).click();
  await page.getByLabel('Field').nth(1).selectOption({ label: 'Insured' });
  await page.getByRole('button', { name: 'Run' }).click();
  await expect(meta(page)).toContainText('groups');
  const total = await totalsRow(page).innerText();
  expect(Number(total.replace(/[^\d]/g, ''))).toBeGreaterThan(10_000);
  expect(Number(total.replace(/[^\d]/g, ''))).toBeLessThan(50_000);

  await page.getByRole('button', { name: 'Chart' }).click();
  await expect(page.getByRole('img', { name: /bar chart/ }).locator('canvas')).toBeVisible();
  await page.getByLabel('Chart type').selectOption('pie');
  await expect(page.getByRole('img', { name: /pie chart/ }).locator('canvas')).toBeVisible();
});

test('incomplete filters are ignored rather than breaking the query', async ({ page }) => {
  await openTemplate(page, 'Patient Register (Report 1)');
  await page.getByRole('button', { name: '+ Add filter' }).click();   // relative date with no period chosen
  await expect(page.locator('.filter-row.incomplete')).toHaveCount(1);
  await page.getByRole('button', { name: 'Run' }).click();
  await expect(meta(page)).toContainText('1,00,002 rows');
  await page.getByLabel('Period').selectOption('LAST_30_DAYS');
  await expect(page.locator('.filter-row.incomplete')).toHaveCount(0);
  await page.getByRole('button', { name: 'Run' }).click();
  await expect(meta(page)).not.toContainText('1,00,002 rows');
});

test('save, reopen by bookmark, rename, delete', async ({ page }) => {
  const name = `E2E report ${Date.now()}`;
  await openTemplate(page, 'Department Summary (Report 2)');
  page.once('dialog', d => d.accept(name));
  await page.getByRole('button', { name: 'Save as' }).click();
  await expect(page.locator('.alert.ok')).toContainText(`Saved as "${name}"`);
  await expect(page.getByRole('navigation', { name: 'Reports' }).getByRole('button', { name })).toBeVisible();
  const url = page.url();
  expect(url).toMatch(/#report=\d+$/);

  // bookmark opens the saved report
  await page.goto(url);
  await page.reload();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(name);

  // change the definition and save in place
  await page.getByRole('button', { name: 'Remove Average Age' }).click();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.locator('.alert.ok')).toContainText(`Saved "${name}"`);

  page.once('dialog', d => d.accept());
  await page.getByRole('button', { name: 'Delete' }).click();
  await expect(page.locator('.alert.ok')).toContainText('Report deleted');
  await expect(page.getByRole('navigation', { name: 'Reports' }).getByRole('button', { name })).toHaveCount(0);

  // the old bookmark now falls back to a template with a notice
  await page.goto(url);
  await page.reload();
  await expect(page.locator('.alert.ok')).toContainText('no longer exists');
});

test('templates cannot be overwritten or deleted', async ({ page }) => {
  await openTemplate(page, 'Monthly Registrations');
  await expect(page.getByRole('button', { name: 'Delete' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Save as' })).toBeVisible();
});

test('a report name with HTML is shown as text, never executed', async ({ page }) => {
  const evil = `<img src=x onerror="window.__xss=1">E2E ${Date.now()}`;
  await openTemplate(page, 'Department Summary (Report 2)');
  page.once('dialog', d => d.accept(evil));
  await page.getByRole('button', { name: 'Save as' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(evil);
  expect(await page.evaluate(() => (window as unknown as { __xss?: number }).__xss)).toBeUndefined();
  page.once('dialog', d => d.accept());
  await page.getByRole('button', { name: 'Delete' }).click();
  await expect(page.locator('.alert.ok')).toContainText('Report deleted');
});

test('CSV download has the on-screen columns and totals', async ({ page }) => {
  await recordSaves(page);
  await openTemplate(page, 'Department Summary (Report 2)');
  const [response] = await Promise.all([
    page.waitForResponse(r => r.url().endsWith('/api/v1/query/export')),
    page.getByRole('button', { name: 'CSV' }).click(),
  ]);
  expect(response.status()).toBe(200);
  await expect.poll(() => saves(page)).toHaveLength(1);
  const [saved] = await saves(page);
  expect(saved.name).toMatch(/^department_summary_report_2_\d{8}_\d{4}\.csv$/);
  const text = Buffer.from(await savedBytes(page, saved.href), 'latin1').toString('utf8');
  expect(text.startsWith('﻿Department,Patients,Total Insured,Average Age,Total Fee (Rs)')).toBe(true);
  expect(text).toContain('TOTAL,100002,100001');
  expect(text.trim().split('\r\n')).toHaveLength(12);   // header + 10 departments + total
});

test('PDF of 1 lakh rows is refused with a helpful message; Excel works', async ({ page }) => {
  test.setTimeout(180_000);   // the 1 lakh row Excel download takes a while
  await recordSaves(page);
  await openTemplate(page, 'Patient Register (Report 1)');
  await page.getByRole('button', { name: 'PDF' }).click();
  await expect(page.getByRole('alert')).toContainText('limited to 50,000 rows');
  expect(await saves(page)).toHaveLength(0);   // nothing saved for a refused download
  const [response] = await Promise.all([
    page.waitForResponse(r => r.url().endsWith('/api/v1/query/export'), { timeout: 120_000 }),
    page.getByRole('button', { name: 'Excel' }).click(),
  ]);
  expect(response.status()).toBe(200);
  await expect.poll(() => saves(page), { timeout: 60_000 }).toHaveLength(1);
  const [saved] = await saves(page);
  expect(saved.name).toMatch(/^patient_register_report_1_\d{8}_\d{4}\.xlsx$/);
  const bytes = await savedBytes(page, saved.href);
  expect(bytes.substring(0, 2)).toBe('PK');           // a real .xlsx (zip) file
  expect(bytes.length).toBeGreaterThan(5_000_000);    // all 1 lakh rows, not one page
  await expect(page.getByRole('alert')).toHaveCount(0);   // the earlier error is cleared on success
});

test('a late response never overwrites what the user did meanwhile (race)', async ({ browser }) => {
  const page = await browser.newPage();
  await page.goto('app/');
  // click as soon as the button exists, while the initial template query is still running
  await page.getByRole('button', { name: '+ New report' }).click();
  await page.waitForTimeout(3000);   // give the template's late query time to come back
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('New report');
  await expect(page.getByText('Choose columns and filters, then press Run.')).toBeVisible();
  await page.close();
});

// Remove any report these tests created, even when a test failed half-way (names contain "E2E").
test.afterAll(async ({ request }) => {
  const reports = (await (await request.get('api/v1/reports')).json()) as { id: number; name: string; template: boolean }[];
  // changes need the CSRF token, which the server keeps in the XSRF-TOKEN cookie
  const token = (await request.storageState()).cookies.find(c => c.name === 'XSRF-TOKEN')?.value ?? '';
  for (const r of reports.filter(x => !x.template && x.name.includes('E2E'))) {
    await request.delete(`api/v1/reports/${r.id}`, { headers: { 'X-XSRF-TOKEN': token } });
  }
});
