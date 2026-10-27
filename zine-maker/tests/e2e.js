'use strict';
/*
 * Teste de ponta a ponta no navegador (Chromium via Playwright), abrindo o app por file://
 * exatamente como um aluno faria ao baixar a pasta. Sobe arquivos de verdade, reordena,
 * exporta e RENDERIZA os PDFs baixados com pdftoppm para conferir os pixels.
 *
 *   npm i -D playwright && npx playwright install chromium   (uma vez)
 *   node tests/e2e.js
 *
 * Precisa também de pdftoppm (poppler) e ImageMagick (convert) no PATH.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { PDFDocument, rgb } = require('pdf-lib');

let chromium;
try {
  ({ chromium } = require('playwright'));
} catch (e) {
  console.error('Playwright não encontrado. Instale com: npm i -D playwright && npx playwright install chromium');
  process.exit(2);
}

const ROOT = path.resolve(__dirname, '..');
// ZINE_E2E_ENTRY=dist/zine-maker.html roda o mesmo teste no arquivo único
const ENTRY = path.resolve(ROOT, process.env.ZINE_E2E_ENTRY || 'index.html');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'zine-e2e-'));
const MM = 72 / 25.4;
const PX = 72 / 25.4;
let passed = 0;
const ok = (name) => console.log('  ✓ ' + name) || passed++;

// ------------------------------------------------------------------ fixtures

const solid = (file, n, w = 148, h = 210) =>
  execFileSync('convert', ['-size', `${w * 4}x${h * 4}`, `xc:rgb(${n * 20},0,0)`, file]);

async function pdfWithPages(file, nums) {
  const doc = await PDFDocument.create();
  for (const n of nums) {
    const p = doc.addPage([148 * MM, 210 * MM]);
    p.drawRectangle({ x: 0, y: 0, width: 148 * MM, height: 210 * MM, color: rgb((n * 20) / 255, 0, 0) });
  }
  fs.writeFileSync(file, await doc.save());
}

function render(pdf, name) {
  execFileSync('pdftoppm', ['-r', '72', '-png', pdf, path.join(tmp, name)]);
  return fs
    .readdirSync(tmp)
    .filter((f) => f.startsWith(name + '-') && f.endsWith('.png'))
    .sort()
    .map((f) => {
      const file = path.join(tmp, f);
      const [w, h] = execFileSync('convert', [file, '-format', '%w %h', 'info:']).toString().split(' ').map(Number);
      const data = execFileSync('convert', [file, '-depth', '8', 'rgb:-'], { maxBuffer: 1 << 28 });
      return {
        w,
        h,
        at(xmm, ymm) {
          const x = Math.min(w - 1, Math.max(0, Math.floor(xmm * PX)));
          const y = Math.min(h - 1, Math.max(0, Math.floor(h - ymm * PX)));
          const i = (y * w + x) * 3;
          return [data[i], data[i + 1], data[i + 2]];
        },
      };
    });
}

/** Número da página pela cor (R = n×20, G=B=0); 0 = branco. */
function pageAt(img, x, y) {
  const [r, g, b] = img.at(x, y);
  if (r > 250 && g > 250 && b > 250) return 0;
  assert.ok(g < 14 && b < 14, `cor inesperada (${r},${g},${b}) em ${x},${y}`);
  return Math.round(r / 20);
}

// --------------------------------------------------------------------- testes

(async () => {
  // arquivos: nomes fora de ordem de propósito (pag10 antes de pag2)
  const f = (n) => path.join(tmp, n);
  solid(f('pag1.png'), 1);
  solid(f('pag2.png'), 2);
  solid(f('pag10.png'), 10);
  await pdfWithPages(f('doc.pdf'), [3, 4]); // páginas 3 e 4
  await pdfWithPages(f('capa.pdf'), [11, 12]); // capa + contracapa (2 páginas)
  // foto "de celular": 40×20 (esq. vermelha R=100, dir. azul), EXIF 6 → exibida em pé, vermelho em cima
  execFileSync('convert', ['-size', '20x20', 'xc:rgb(100,0,0)', '(', '-size', '20x20', 'xc:rgb(0,0,100)', ')', '+append', '-strip', f('base.jpg')]);
  injectExif6(f('base.jpg'), f('celular.jpg'));

  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 900 }, acceptDownloads: true });
  const page = await ctx.newPage();
  const problems = [];
  page.on('pageerror', (e) => problems.push('PAGEERROR ' + e.message));
  page.on('console', (m) => m.type() === 'error' && problems.push('console.error ' + m.text()));
  page.on('dialog', (d) => d.accept()); // confirm("Remover todas as páginas?")
  await page.goto('file://' + ENTRY);
  const idle = () =>
    page.waitForFunction(() => !document.querySelector('#preview-body.busy') && !/Lendo/.test(document.querySelector('#toast').textContent || ''), null, { timeout: 60000 });
  const labels = () => page.$$eval('#tray .pg[data-id]:not(.auto) .pg-name', (els) => els.map((e) => e.textContent));
  const note = () => page.textContent('#pad-note');
  const download = async (selector, file) => {
    const [dl] = await Promise.all([page.waitForEvent('download', { timeout: 60000 }), page.click(selector)]);
    await dl.saveAs(file);
    return file;
  };

  console.log('Zine Maker · e2e');

  // 1. carga inicial
  assert.equal(await page.title(), 'Zine Maker');
  assert.match(await page.textContent('#ab-sum'), /Adicione páginas/);
  ok('abre por file:// e começa vazio');

  // 2. upload com ordenação natural + PDF expandido em páginas
  await page.setInputFiles('#file-input', [f('pag10.png'), f('pag2.png'), f('doc.pdf'), f('pag1.png')]);
  await page.waitForFunction(() => document.querySelectorAll('#tray .pg[data-id]').length === 5, null, { timeout: 30000 });
  assert.deepEqual(await labels(), ['doc.pdf · p.1', 'doc.pdf · p.2', 'pag1.png', 'pag2.png', 'pag10.png']);
  assert.match(await note(), /múltiplos de 4.*3 páginas em branco.*Total: 8/);
  assert.equal(await page.$$eval('#tray .pg.auto', (e) => e.length), 3);
  ok('ordem natural (pag2 antes de pag10), PDF vira páginas, completa até múltiplo de 4');

  // 3. reordenar com os botões e remover
  await page.click('#tray .pg[data-id]:nth-child(1) button[data-act="right"]'); // doc p.2 passa à frente
  assert.deepEqual((await labels()).slice(0, 2), ['doc.pdf · p.2', 'doc.pdf · p.1']);
  await page.click('#tray .pg[data-id]:nth-child(2) button[data-act="left"]'); // volta
  assert.deepEqual((await labels()).slice(0, 2), ['doc.pdf · p.1', 'doc.pdf · p.2']);
  ok('reordenar por botões');

  // 3b. reordenar arrastando (o gesto mais natural): cartão 1 vai para depois do 4 e volta
  const cards = page.locator('#tray .pg[data-id]');
  const box = await cards.nth(3).boundingBox();
  await cards.nth(0).dragTo(cards.nth(3), { targetPosition: { x: box.width - 6, y: box.height / 2 } });
  assert.deepEqual(await labels(), ['doc.pdf · p.2', 'pag1.png', 'pag2.png', 'doc.pdf · p.1', 'pag10.png']);
  await cards.nth(3).dragTo(cards.nth(0), { targetPosition: { x: 6, y: box.height / 2 } });
  assert.deepEqual(await labels(), ['doc.pdf · p.1', 'doc.pdf · p.2', 'pag1.png', 'pag2.png', 'pag10.png']);
  assert.deepEqual(await page.$$eval('#tray .pg[data-id] .pg-num', (e) => e.map((x) => x.textContent)), ['1', '2', '3', '4', '5']);
  ok('reordenar arrastando e soltando (números acompanham)');

  // 4. capa: PDF de 2 páginas preenche capa + contracapa
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.click('.slot[data-slot="front"]')]);
  await chooser.setFiles(f('capa.pdf'));
  await page.waitForSelector('.slot.filled[data-slot="back"]', { timeout: 30000 });
  assert.equal(await page.$$eval('.slot.filled', (e) => e.length), 2);
  assert.ok(await page.$('.slot.filled[data-slot="front"]'));
  ok('PDF de 2 páginas preenche capa e contracapa');

  // 5. exporta o miolo e confere a imposição pelos pixels
  await idle();
  const miolo = await download('[data-export="miolo"]', f('miolo.pdf'));
  const m = render(miolo, 'miolo');
  assert.equal(m.length, 4); // 2 folhas × 2 faces
  const seq = [3, 4, 1, 2, 10, 0, 0, 0]; // doc p.1(3), doc p.2(4), pag1, pag2, pag10, 3 brancas
  const pg = (n) => seq[n - 1];
  const faces = [[8, 1], [2, 7], [6, 3], [4, 5]];
  faces.forEach(([l, r], i) => {
    assert.equal(pageAt(m[i], 70, 105), pg(l), `face ${i} esquerda (pág ${l})`);
    assert.equal(pageAt(m[i], 227, 105), pg(r), `face ${i} direita (pág ${r})`);
  });
  ok('PDF do miolo: páginas certas em cada face (8|1, 2|7, 6|3, 4|5)');

  // 6. capa exportada: [contracapa | capa] por fora, brancas por dentro
  const capa = await download('[data-export="capa"]', f('capa-out.pdf'));
  const c = render(capa, 'capa');
  assert.equal(c.length, 2);
  assert.equal(pageAt(c[0], 70, 105), 12, 'contracapa à esquerda');
  assert.equal(pageAt(c[0], 227, 105), 11, 'capa à direita');
  assert.equal(pageAt(c[1], 70, 105), 0);
  assert.equal(pageAt(c[1], 227, 105), 0);
  ok('PDF da capa: contracapa | capa por fora, 2ª e 3ª capa em branco');

  // 7. refile + persistência
  await page.click('.switch');
  await page.fill('#f-margin', '6');
  await idle();
  const refilado = await download('[data-export="miolo"]', f('refilado.pdf'));
  const r = render(refilado, 'refilado');
  // página 1 (doc p.1, cor 3) está à direita da frente da folha 1: a arte termina ~291 mm
  assert.equal(pageAt(r[0], 288, 105), 3, 'arte até perto da linha de corte');
  assert.equal(pageAt(r[0], 294.5, 105), 0, 'sobra branca na lateral (6 mm)');
  assert.equal(pageAt(r[0], 227, 105), 3, 'página 1 continua à direita');
  await page.reload();
  assert.equal(await page.isChecked('input[data-k="trim.enabled"]'), true, 'refile lembrado depois de recarregar');
  assert.equal(await page.inputValue('#f-margin'), '6');
  ok('refile gera sobra na lateral e as configurações sobrevivem ao recarregar');

  // 8. foto com EXIF 6 entra em pé (vermelho em cima)
  await page.click('#btn-clear');
  await page.waitForFunction(() => document.querySelectorAll('#tray .pg').length === 0);
  await page.click('.switch'); // desliga refile para medir sem sobra
  await page.setInputFiles('#file-input', [f('celular.jpg')]);
  await page.waitForSelector('#tray .pg[data-id]', { timeout: 30000 });
  await idle();
  const foto = await download('[data-export="miolo"]', f('foto.pdf'));
  const ph = render(foto, 'foto');
  // 1 página + 3 brancas; página 1 fica à direita da frente da folha 1
  const top = ph[0].at(222, 150);
  const bottom = ph[0].at(222, 60);
  assert.ok(top[0] > 80 && top[2] < 25, 'topo vermelho: ' + top);
  assert.ok(bottom[2] > 80 && bottom[0] < 25, 'base azul: ' + bottom);
  ok('foto de celular (EXIF 6) sai em pé no PDF');

  // 9. "tudo num livreto só" e folha de teste
  await page.check('input[name="mode"][value="together"]');
  await idle();
  assert.match(await page.textContent('[data-export="miolo"]'), /Baixar o zine/);
  assert.equal(await page.$('[data-export="capa"]'), null);
  const teste = await download('[data-export="teste"]', f('teste.pdf'));
  assert.match(execFileSync('pdfinfo', [teste]).toString(), /Pages:\s+2/);
  ok('modo livreto único e folha de teste (2 páginas)');

  // 10. sem erros no console
  assert.deepEqual(problems, []);
  ok('nenhum erro de JavaScript no console');

  await browser.close();
  console.log(`\n${passed} verificações passaram.`);
})().catch((e) => {
  console.error('\nFALHOU:', e && e.stack ? e.stack : e);
  process.exit(1);
});

/** Injeta um segmento EXIF (orientação 6, big endian) logo após o SOI do JPEG. */
function injectExif6(src, dst) {
  const j = fs.readFileSync(src);
  const tiff = [0x4d, 0x4d, 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, 0, 0, 0, 0];
  const body = [0x45, 0x78, 0x69, 0x66, 0, 0, ...tiff];
  const len = body.length + 2;
  const seg = Buffer.from([0xff, 0xe1, len >> 8, len & 255, ...body]);
  fs.writeFileSync(dst, Buffer.concat([j.subarray(0, 2), seg, j.subarray(2)]));
}
