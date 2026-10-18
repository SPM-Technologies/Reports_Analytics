import { type Page, expect, test } from '@playwright/test';

// Stage 5b in the browser (signed in as the administrator of H001, 1,00,002 patients): calculated fields,
// pivot, subtotals, top N, highlighting, drill-down, dashboards and the hospital settings screens.
// Nothing here adds patients; reports and dashboards created by a test are deleted again.

const results = (page: Page) => page.getByLabel('Report results');
const meta = (page: Page) => page.locator('.meta');

/** A new report grouped by the given columns with the Patients measure. */
async function summary(page: Page, columns: string[]) {
  await page.getByRole('button', { name: '+ New report' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('New report');
  for (const c of ['Reg No', 'Reg Date', 'Patient Name', 'Gender', 'Department']) {
    await page.getByRole('button', { name: `Remove ${c}` }).click();
  }
  for (const c of columns) {
    await page.getByLabel('Add column').selectOption({ label: c });
  }
  await page.getByLabel('Add measure').selectOption({ label: 'Patients' });
}

async function run(page: Page) {
  await page.getByRole('button', { name: 'Run' }).click();
  await expect(meta(page)).toContainText('groups');
}

test.beforeEach(async ({ page }) => {
  await page.goto('app/');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
});

test('a calculated field: checked by the server, then used as a group', async ({ page }) => {
  await summary(page, []);
  await page.getByRole('button', { name: '+ New calculated field' }).click();
  const dialog = page.getByRole('dialog', { name: 'Calculated field' });
  await dialog.getByLabel('Name').fill('Age group');
  // a mistake is reported with its position and blocks saving
  await dialog.getByLabel('Formula').fill('BUCKET([Age], 18, 40');
  await dialog.getByRole('button', { name: 'Check' }).click();
  await expect(dialog.getByRole('alert')).toContainText('character');
  await expect(dialog.getByRole('button', { name: 'Save' })).toBeDisabled();
  await dialog.getByLabel('Formula').fill('BUCKET([Age], 18, 40, 60)');
  await dialog.getByRole('button', { name: 'Check' }).click();
  await expect(dialog.getByRole('status')).toContainText('a value per patient');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();

  await page.getByLabel('Add column').selectOption({ label: 'Age group (calculated)' });
  await run(page);
  await expect(results(page)).toContainText('below 18');
  await expect(results(page)).toContainText('60 and above');
  await expect(results(page).locator('tfoot')).toContainText('1,00,002');
});

test('pivot spreads a column across, with row and column totals', async ({ page }) => {
  await summary(page, ['Gender', 'Visit Type']);
  await page.getByLabel('Pivot column').selectOption({ label: 'Visit Type' });
  await run(page);
  const pivot = page.getByLabel('Pivot table');
  await expect(pivot.locator('thead')).toContainText('NEW');
  await expect(pivot.locator('thead')).toContainText('FOLLOW_UP');
  await expect(pivot.locator('thead')).toContainText('Total');
  await expect(pivot.locator('tfoot')).toContainText('1,00,002');
});

test('subtotals per group, and top N with the rest as Others', async ({ page }) => {
  await summary(page, ['Gender', 'Visit Type']);
  await page.getByText('Subtotals per group').click();
  await run(page);
  await expect(results(page).locator('tr.subtotal').first()).toContainText('Subtotal');
  await expect(results(page).locator('tr.subtotal')).toHaveCount(3);

  await summary(page, ['Department']);
  await page.getByLabel('Top N groups').fill('3');
  await run(page);
  await expect(results(page).locator('tbody tr:not(.others)')).toHaveCount(3);
  await expect(results(page).locator('tr.others')).toContainText('Others');
  await expect(results(page).locator('tfoot')).toContainText('1,00,002');
});

test('share of total, highlight rules and drill-down to the patients of a group', async ({ page }) => {
  await summary(page, ['Gender']);
  await run(page);
  // highlight groups above 40,000 patients
  await page.getByText('Highlight', { exact: true }).click();
  await page.getByRole('button', { name: '+ Highlight cells' }).click();
  await page.getByLabel('Highlight value').fill('40000');
  const female = results(page).locator('tbody tr', { hasText: 'Female' });
  await expect(female.locator('td.num').nth(1)).toHaveAttribute('style', /background/);
  const other = results(page).locator('tbody tr', { hasText: 'Other' });
  await expect(other.locator('td.num').nth(1)).not.toHaveAttribute('style', /background/);
  const count = (await female.locator('td.num').nth(1).innerText()).trim();

  // the patients behind the Female group
  await female.click();
  await expect(page.locator('.drill-bar')).toContainText('Gender: Female');
  await expect(meta(page)).toContainText(`${count} rows`);
  await expect(results(page).locator('tbody')).not.toContainText(/\bMale\b/);
  await page.getByRole('button', { name: '‹ Back to the summary' }).click();
  await expect(meta(page)).toContainText('3 groups');
});

test('a dashboard of saved reports with a filter for all tiles', async ({ page }) => {
  page.on('dialog', d => d.accept());
  await page.getByRole('button', { name: '+ New dashboard' }).click();
  await page.getByLabel('Dashboard name').fill('E2E board');
  await page.getByLabel('Add a report as a tile').selectOption({ label: 'Department Summary (Report 2)' });
  await page.getByLabel('Add a report as a tile').selectOption({ label: 'Patient Register (Report 1)' });
  await expect(page.locator('.tile-card')).toHaveCount(2);
  await page.getByLabel('Tile view').first().selectOption('table');
  await expect(page.locator('.tile-card').first()).toContainText('1,00,002');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('status').first()).toContainText('Dashboard saved');
  await expect(page.getByRole('navigation', { name: 'Reports' })).toContainText('E2E board');

  // a filter for all tiles: only female patients
  await page.getByRole('button', { name: '+ Filter for all tiles' }).click();
  await page.getByLabel('Field').selectOption({ label: 'Gender' });
  await page.getByLabel('Value').fill('Female');
  await page.getByRole('button', { name: 'Apply filters' }).click();
  await expect(page.locator('.tile-card').first()).not.toContainText('1,00,002');

  // reopen from the list, then delete
  await page.getByRole('button', { name: '+ New report' }).click();
  await page.getByRole('navigation', { name: 'Reports' }).getByRole('button', { name: /E2E board/ }).click();
  await expect(page.locator('.tile-card')).toHaveCount(2);
  await page.getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByRole('navigation', { name: 'Reports' })).not.toContainText('E2E board');
});

test('the hospital settings screens open for the administrator', async ({ page }) => {
  for (const [path, text] of [
    ['settings/fields', 'Standard fields this hospital does not use'],
    ['settings/lists', 'One value per line'],
    ['settings/departments', 'Add a department'],
    ['settings/report-fields', 'How each field of the Patients dataset appears'],
    ['settings/downloads', 'Formats users may download'],
    ['admin/roles', 'Roles'],
  ]) {
    await page.goto(path);
    await expect(page.locator('main')).toContainText(text);
  }
});
