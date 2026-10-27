'use strict';
/*
 * Teste ponta a ponta: gera PDFs, RENDERIZA com pdftoppm e lê os pixels.
 * Cada página de origem é um bloco de cor sólida cujo canal R codifica o número da página
 * (R = n × 20), então dá para provar qual página caiu em cada posição da folha.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');
const { PDFDocument, rgb, degrees } = require('pdf-lib');
const C = require('../js/core.js');
const B = require('../js/pdfbuilder.js');

const haveTools = spawnSync('pdftoppm', ['-v']).status !== null && spawnSync('convert', ['-version']).status !== null;
const skip = haveTools ? false : 'pdftoppm/ImageMagick não encontrados';

const DPI = 72;
const PX = DPI / 25.4; // pixels por mm
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'zine-'));

function render(bytes, name) {
  const pdf = path.join(tmp, name + '.pdf');
  fs.writeFileSync(pdf, bytes);
  execFileSync('pdftoppm', ['-r', String(DPI), '-png', pdf, path.join(tmp, name)]);
  return fs
    .readdirSync(tmp)
    .filter((f) => f.startsWith(name + '-') && f.endsWith('.png'))
    .sort()
    .map((f) => {
      const file = path.join(tmp, f);
      const dims = execFileSync('convert', [file, '-format', '%w %h', 'info:']).toString().split(' ').map(Number);
      const data = execFileSync('convert', [file, '-depth', '8', 'rgb:-'], { maxBuffer: 1 << 28 });
      return {
        w: dims[0],
        h: dims[1],
        // x,y em mm a partir do canto inferior esquerdo (como no PDF)
        at(xmm, ymm) {
          const x = Math.min(dims[0] - 1, Math.max(0, Math.floor(xmm * PX)));
          const y = Math.min(dims[1] - 1, Math.max(0, Math.floor(dims[1] - ymm * PX)));
          const i = (y * dims[0] + x) * 3;
          return [data[i], data[i + 1], data[i + 2]];
        },
      };
    });
}

/** Decodifica o número da página pela cor (R = n×20, G=B=0); branco = 0. */
function pageAt(img, xmm, ymm) {
  const [r, g, b] = img.at(xmm, ymm);
  if (r > 250 && g > 250 && b > 250) return 0;
  assert.ok(g < 12 && b < 12, `cor inesperada em (${xmm},${ymm}): ${r},${g},${b}`);
  return Math.round(r / 20);
}

async function solidPdf(n, w = 148, h = 210) {
  const doc = await PDFDocument.create();
  for (let i = 1; i <= n; i++) {
    const p = doc.addPage([w * C.MM, h * C.MM]);
    p.drawRectangle({ x: 0, y: 0, width: w * C.MM, height: h * C.MM, color: rgb((i * 20) / 255, 0, 0) });
  }
  return doc.save();
}

function pdfItems(bytes, n, prefix = 'p') {
  return Array.from({ length: n }, (_, i) => ({
    kind: 'pdf',
    id: prefix + (i + 1),
    fileId: prefix,
    pageIndex: i,
    getBytes: async () => bytes,
    rotation: 0,
  }));
}

const base = (over) => Object.assign({}, C.DEFAULT_SETTINGS, over || {}, { trim: Object.assign({}, C.DEFAULT_SETTINGS.trim, (over && over.trim) || {}) });

// ------------------------------------------------------------------------------

test('12 páginas: cada página cai no lugar certo da folha A4 (frente e verso)', { skip }, async () => {
  const items = pdfItems(await solidPdf(12), 12);
  const out = await B.buildPdf(items, { settings: base(), offsets: [0, 0, 0] });
  const pages = render(out, 'imposicao');
  assert.equal(pages.length, 6); // 3 folhas × 2 faces
  assert.ok(Math.abs(pages[0].w - 842) <= 1 && Math.abs(pages[0].h - 595) <= 1, `A4 paisagem, veio ${pages[0].w}×${pages[0].h}`);

  const expected = [
    [12, 1], [2, 11], // folha 1: frente, verso
    [10, 3], [4, 9], // folha 2
    [8, 5], [6, 7], // folha 3
  ];
  expected.forEach(([l, r], i) => {
    assert.equal(pageAt(pages[i], 70, 105), l, `folha ${i >> 1} ${i % 2 ? 'verso' : 'frente'} esquerda`);
    assert.equal(pageAt(pages[i], 227, 105), r, `folha ${i >> 1} ${i % 2 ? 'verso' : 'frente'} direita`);
  });
});

test('leitura da direita para a esquerda inverte as posições', { skip }, async () => {
  const items = pdfItems(await solidPdf(8), 8);
  const out = await B.buildPdf(items, { settings: base({ rtl: true }), offsets: [0, 0] });
  const pages = render(out, 'rtl');
  assert.equal(pageAt(pages[0], 70, 105), 1);
  assert.equal(pageAt(pages[0], 227, 105), 8);
});

test('sem refile: a arte ocupa a meia-folha inteira, sem marcas', { skip }, async () => {
  const items = pdfItems(await solidPdf(4), 4);
  const out = await B.buildPdf(items, { settings: base({ fit: 'cover' }), offsets: [0] });
  const [front] = render(out, 'semrefile');
  assert.equal(pageAt(front, 2, 105), 4); // quase na borda esquerda já tem arte
  assert.equal(pageAt(front, 295, 105), 1);
  assert.equal(pageAt(front, 148, 5), 4); // logo antes da dobra
  assert.equal(pageAt(front, 149.5, 5), 1); // logo depois
});

test('com refile: sobra branca na lateral, e cresce nas folhas internas (creep)', { skip }, async () => {
  const items = pdfItems(await solidPdf(12), 12);
  // espessura exagerada (1 mm × fator 1) para o deslocamento ser mensurável em 72 dpi
  const offsets = C.creepOffsets([1, 1, 1], 1); // [0, 1, 2]
  const s = base({ fit: 'contain', trim: { enabled: true, margin: 6, topBottom: 0, marks: false } });
  const out = await B.buildPdf(items, { settings: s, offsets });
  const pages = render(out, 'refile');

  // folha externa (offset 0): arte começa a 6 mm da borda
  assert.equal(pageAt(pages[0], 4, 105), 0, 'sobra branca (esq.) na folha externa');
  assert.equal(pageAt(pages[0], 8, 105), 12);
  assert.equal(pageAt(pages[0], 293, 105), 0, 'sobra branca (dir.) na folha externa');
  assert.equal(pageAt(pages[0], 289, 105), 1);

  // folha mais interna (offset 2): arte só começa em 8 mm
  assert.equal(pageAt(pages[4], 7, 105), 0, 'sobra maior na folha interna');
  assert.equal(pageAt(pages[4], 9.5, 105), 8);
  assert.equal(pageAt(pages[4], 290, 105), 0);
  assert.equal(pageAt(pages[4], 287.5, 105), 5);

  // a arte que passa da dobra é recortada: não invade a página vizinha
  assert.equal(pageAt(pages[4], 147.5, 105), 8);
  assert.equal(pageAt(pages[4], 149.5, 105), 5);
});

test('com refile: depois do corte único as folhas têm a mesma margem externa', { skip }, async () => {
  const items = pdfItems(await solidPdf(12), 12);
  const offsets = C.creepOffsets([1, 1, 1], 1);
  const s = base({ fit: 'contain', trim: { enabled: true, margin: 6, topBottom: 0, marks: false } });
  const out = await B.buildPdf(items, { settings: s, offsets });
  const pages = render(out, 'refile2');
  const geom = (e) => C.sheetGeometry(297, 210, s.trim, e);
  // distância física, medida a partir da lombada de referência, até o corte
  offsets.forEach((e, k) => {
    const g = geom(e);
    const cutFromFold = g.half - g.left.cutX; // dentro da própria folha
    assert.ok(Math.abs(cutFromFold + e - (148.5 - 6)) < 1e-9, `folha ${k}`);
    // e o PDF realmente tem a arte começando em cutX
    const px = pages[k * 2];
    assert.equal(pageAt(px, g.left.cutX - 0.8, 105), 0);
    assert.equal(pageAt(px, g.left.cutX + 0.8, 105) > 0, true);
  });
});

test('modo preencher (cover) passa um pouco da linha de corte: sangria natural', { skip }, async () => {
  const items = pdfItems(await solidPdf(4), 4);
  const s = base({ fit: 'cover', trim: { enabled: true, margin: 6, topBottom: 0, marks: false } });
  const [front] = render(await B.buildPdf(items, { settings: s, offsets: [0] }), 'cover');
  // largura final 148,5 − 6 = 142,5; cover escala a página A5 (148 mm) e centraliza:
  // a arte começa em 6 + (142,5 − 148)/2 = 3,25 mm, ou seja, passa 2,75 mm da linha de corte
  assert.equal(pageAt(front, 3.7, 105), 4);
  assert.equal(pageAt(front, 2.8, 105), 0);
});

test('página de PDF sem conteúdo vira página em branco em vez de quebrar', { skip }, async () => {
  const doc = await PDFDocument.create();
  for (let i = 0; i < 4; i++) doc.addPage([148 * C.MM, 210 * C.MM]); // nenhuma tem /Contents
  const items = pdfItems(await doc.save(), 4);
  const out = await B.buildPdf(items, { settings: base(), offsets: [0] });
  assert.equal((await PDFDocument.load(out)).getPageCount(), 2);
});

test('espelhar a sobra pinta a lateral com a própria borda da página', { skip }, async () => {
  const items = pdfItems(await solidPdf(4), 4);
  const s = base({ fit: 'cover', fill: 'mirror', trim: { enabled: true, margin: 6, topBottom: 0, marks: false } });
  const out = await B.buildPdf(items, { settings: s, offsets: [0] });
  const [front] = render(out, 'mirror');
  assert.equal(pageAt(front, 2, 105), 4, 'sobra preenchida (não branca)');
  assert.equal(pageAt(front, 295, 105), 1);
  assert.equal(pageAt(front, 148, 105), 4); // mesmo com espelho, a dobra separa as páginas
  assert.equal(pageAt(front, 149.5, 105), 1);
});

test('marcas de corte caem na linha de refile e só existem com a opção ligada', { skip }, async () => {
  const items = pdfItems(await solidPdf(4), 4);
  const withMarks = base({ trim: { enabled: true, margin: 6, topBottom: 0, marks: true } });
  const noMarks = base({ trim: { enabled: true, margin: 6, topBottom: 0, marks: false } });
  const a = render(await B.buildPdf(items, { settings: withMarks, offsets: [0] }), 'marcas')[0];
  const b = render(await B.buildPdf(items, { settings: noMarks, offsets: [0] }), 'semmarcas')[0];
  // página 4 (esquerda) usa cor R=80; o traço preto sobre ela deixa o pixel mais escuro/preto
  const cutX = 6;
  let darker = 0;
  for (let dx = -0.6; dx <= 0.6; dx += 0.1) {
    const [ra] = a.at(cutX + dx, 105);
    const [rb] = b.at(cutX + dx, 105);
    if (ra < rb - 20 || (ra < 60 && rb > 200)) darker++;
  }
  assert.ok(darker > 0, 'a marca precisa aparecer em y = meio da página');
  // nenhum traço fora das alturas das marcas (ex.: a 10 mm do topo)
  assert.deepEqual(a.at(cutX, 200), b.at(cutX, 200));
});

test('virar pela borda longa gira o verso em 180°', { skip }, async () => {
  const doc = await PDFDocument.create();
  // página com canto superior esquerdo vermelho (n=5 → R=100) e resto branco
  for (let i = 0; i < 4; i++) {
    const p = doc.addPage([148 * C.MM, 210 * C.MM]);
    if (i === 1) p.drawRectangle({ x: 0, y: 190 * C.MM, width: 30 * C.MM, height: 20 * C.MM, color: rgb(100 / 255, 0, 0) });
  }
  const bytes = await doc.save();
  const items = pdfItems(bytes, 4);
  const short = render(await B.buildPdf(items, { settings: base({ duplex: 'short' }), offsets: [0] }), 'curta');
  const long = render(await B.buildPdf(items, { settings: base({ duplex: 'long' }), offsets: [0] }), 'longa');
  // página 2 está na esquerda do verso. Curta: canto sup. esq. da meia-folha esquerda
  assert.equal(pageAt(short[1], 5, 205), 5);
  assert.equal(pageAt(short[1], 5, 5), 0);
  // Longa: tudo girado 180° → o canto vai para inferior direito da folha
  assert.equal(pageAt(long[1], 292, 5), 5);
  assert.equal(pageAt(long[1], 5, 205), 0);
  // a frente não muda
  assert.deepEqual(short[0].at(100, 100), long[0].at(100, 100));
});

test('imagens PNG e JPEG; rotação de 90° (horário) leva a esquerda para cima', { skip }, async () => {
  // imagem 40×20: metade esquerda vermelha (R=100 → página "5"), direita azul
  const png = path.join(tmp, 'rb.png');
  const jpg = path.join(tmp, 'rb.jpg');
  const mk = (out) =>
    execFileSync('convert', ['-size', '20x20', 'xc:rgb(100,0,0)', '(', '-size', '20x20', 'xc:rgb(0,0,100)', ')', '+append', out]);
  mk(png);
  mk(jpg);
  const img = (file, mime, rotation, id) => ({ kind: 'image', id, mime, getBytes: async () => fs.readFileSync(file), rotation });
  const blank = { kind: 'blank' };

  // página 4 = png sem girar, página 1 = jpg girado 90° (contain na meia-folha 148.5×210)
  const items = [blank, blank, blank, img(png, 'image/png', 0, 'a')];
  items[0] = img(jpg, 'image/jpeg', 90, 'b');
  const out = await B.buildPdf(items, { settings: base(), offsets: [0] });
  const [front] = render(out, 'imgs');
  // página 4 (esq.) png 2:1 contain: faixa central; esquerda vermelha, direita azul
  assert.equal(pageAt(front, 40, 105), 5);
  const [r1, g1, b1] = front.at(110, 105);
  assert.ok(b1 > 80 && r1 < 20, 'azul à direita do png sem girar: ' + [r1, g1, b1]);
  // página 1 (dir.) girada 90° horário: a metade esquerda (vermelha) vai para CIMA
  const top = front.at(222, 150);
  const bottom = front.at(222, 60);
  assert.ok(top[0] > 80 && top[2] < 20, 'topo vermelho: ' + top);
  assert.ok(bottom[2] > 80 && bottom[0] < 20, 'base azul: ' + bottom);
});

test('/Rotate do PDF de origem é respeitado como o leitor de PDF mostraria', { skip }, async () => {
  const doc = await PDFDocument.create();
  // página paisagem 100×50 mm: esquerda vermelha, direita azul; /Rotate 90 (horário) ao exibir
  const p = doc.addPage([100 * C.MM, 50 * C.MM]);
  p.drawRectangle({ x: 0, y: 0, width: 50 * C.MM, height: 50 * C.MM, color: rgb(100 / 255, 0, 0) });
  p.drawRectangle({ x: 50 * C.MM, y: 0, width: 50 * C.MM, height: 50 * C.MM, color: rgb(0, 0, 100 / 255) });
  p.setRotation(degrees(90));
  for (let i = 0; i < 3; i++) doc.addPage([148 * C.MM, 210 * C.MM]);
  const bytes = await doc.save();
  const items = pdfItems(bytes, 4);
  const [front] = render(await B.buildPdf(items, { settings: base(), offsets: [0] }), 'rotate');
  // item 1 é a página da direita: exibida em retrato, vermelho em cima
  const top = front.at(222, 150);
  const bottom = front.at(222, 60);
  assert.ok(top[0] > 80 && top[2] < 20, 'topo vermelho: ' + top);
  assert.ok(bottom[2] > 80 && bottom[0] < 20, 'base azul: ' + bottom);
  // e a ordem das páginas continua batendo
  assert.equal(pageAt(front, 40, 105), 0, 'página 4 está em branco');
});

test('folha de teste: 4 páginas impostas com quadros de margem da impressora', { skip }, async () => {
  const out = await B.buildTestSheet(base({ trim: { enabled: true, margin: 3, topBottom: 0, marks: true } }), 'Meu zine');
  const doc = await PDFDocument.load(out);
  assert.equal(doc.getPageCount(), 2);
  assert.equal(doc.getTitle(), 'Meu zine — folha de teste');
  const [front] = render(out, 'teste');
  assert.ok(front.w > 800);
});

test('saída é um PDF válido que o poppler abre sem erro', { skip }, async () => {
  const items = pdfItems(await solidPdf(8), 8);
  const out = await B.buildPdf(items, { settings: base({ fill: 'mirror', trim: { enabled: true, margin: 4, topBottom: 2, marks: true } }), offsets: [0, 0.3] });
  const f = path.join(tmp, 'valido.pdf');
  fs.writeFileSync(f, out);
  const info = spawnSync('pdfinfo', [f]);
  assert.equal(info.status, 0, info.stderr.toString());
  assert.match(info.stdout.toString(), /Pages:\s+4/);
});
