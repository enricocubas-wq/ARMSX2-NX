'use strict';
/*
 * Testa o app de desktop de verdade: abre o Electron, carrega o exemplo, exporta o PDF
 * e confere o arquivo baixado. No Linux sem tela use:  xvfb-run -a node test-desktop.js
 * Precisa de Playwright (npm i -D playwright) e do poppler (pdfinfo).
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

let electron;
try {
  ({ _electron: electron } = require('playwright'));
} catch (e) {
  console.error('Playwright não encontrado: npm i -D playwright');
  process.exit(2);
}

(async () => {
  execFileSync(process.execPath, [path.join(__dirname, 'scripts', 'prepare.js')], { stdio: 'inherit' });
  const dl = fs.mkdtempSync(path.join(os.tmpdir(), 'zine-desktop-'));
  const env = { ...process.env, ZINE_TEST_DOWNLOAD_DIR: dl };
  delete env.NODE_OPTIONS;

  // ZINE_DESKTOP_EXE aponta para o executável JÁ EMPACOTADO (o que vai no instalador);
  // sem ela, roda a partir do código-fonte com o Electron da pasta.
  const packaged = process.env.ZINE_DESKTOP_EXE;
  const args = packaged ? [] : [__dirname];
  if (process.platform === 'linux') args.push('--no-sandbox'); // contêineres rodando como root
  const app = await electron.launch({ executablePath: packaged || require('electron'), args, env });
  console.log('  executável: ' + (packaged || 'código-fonte'));
  const page = await app.firstWindow();
  const logs = [];
  page.on('console', (m) => logs.push(m.type() + ': ' + m.text()));
  page.on('pageerror', (e) => logs.push('PAGEERROR ' + e.message));

  await page.waitForSelector('#btn-sample');
  assert.equal(await page.title(), 'Zine Maker');
  console.log('  ✓ janela abriu com o app');

  await page.click('#btn-sample');
  await page.waitForFunction(() => document.querySelectorAll('#preview-body canvas').length >= 4 && !document.querySelector('#preview-body.busy'), null, { timeout: 60000 });
  console.log('  ✓ exemplo carregado e prévia montada');

  await page.click('[data-export="miolo"]');
  const pdf = path.join(dl, 'meu-zine-miolo-a4.pdf');
  for (let i = 0; i < 120 && !fs.existsSync(pdf); i++) await new Promise((r) => setTimeout(r, 250));
  assert.ok(fs.existsSync(pdf), 'PDF não foi baixado');
  const info = execFileSync('pdfinfo', [pdf]).toString();
  assert.match(info, /Pages:\s+4/);
  assert.match(info, /Page size:\s+841\.\d+ x 595\.\d+/);
  console.log('  ✓ PDF do miolo baixado (4 páginas, A4 paisagem)');

  const bad = logs.filter((l) => /^(error|PAGEERROR)/i.test(l));
  assert.deepEqual(bad, []);
  const fake = logs.filter((l) => /fake worker/i.test(l));
  assert.equal(fake.length, 0, 'pdf.js caiu no fake worker');
  console.log('  ✓ sem erros no console e com o worker real do pdf.js');

  await app.close();
  console.log('\nApp de desktop OK.');
})().catch((e) => {
  console.error('\nFALHOU:', e && e.stack ? e.stack : e);
  process.exit(1);
});
