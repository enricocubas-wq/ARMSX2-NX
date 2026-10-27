'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const C = require('../js/core.js');

const near = (a, b, eps = 1e-9) => assert.ok(Math.abs(a - b) < eps, `${a} ≉ ${b}`);

// ------------------------------------------------------------------ imposição

test('imposição de 12 páginas bate com a tabela clássica', () => {
  const s = C.imposeSheets(12);
  assert.deepEqual(s, [
    { front: [12, 1], back: [2, 11] },
    { front: [10, 3], back: [4, 9] },
    { front: [8, 5], back: [6, 7] },
  ]);
});

test('imposição de 4 e 8 páginas', () => {
  assert.deepEqual(C.imposeSheets(4), [{ front: [4, 1], back: [2, 3] }]);
  assert.deepEqual(C.imposeSheets(8), [
    { front: [8, 1], back: [2, 7] },
    { front: [6, 3], back: [4, 5] },
  ]);
});

test('leitura da direita para a esquerda inverte esquerda/direita', () => {
  assert.deepEqual(C.imposeSheets(8, true), [
    { front: [1, 8], back: [7, 2] },
    { front: [3, 6], back: [5, 4] },
  ]);
});

test('só aceita múltiplos de 4', () => {
  for (const bad of [0, 1, 2, 3, 5, 10, 13, -4, 4.5]) assert.throws(() => C.imposeSheets(bad), /múltiplo de 4/);
});

test('invariantes: cada página aparece uma única vez e frente/verso casam', () => {
  for (let n = 4; n <= 64; n += 4) {
    for (const rtl of [false, true]) {
      const sheets = C.imposeSheets(n, rtl);
      assert.equal(sheets.length, n / 4);
      const seen = sheets.flatMap((s) => [...s.front, ...s.back]).sort((a, b) => a - b);
      assert.deepEqual(seen, Array.from({ length: n }, (_, i) => i + 1));
      sheets.forEach((s, i) => {
        // as duas páginas de uma face somam n+1 (par espelhado no livro)
        assert.equal(s.front[0] + s.front[1], n + 1);
        assert.equal(s.back[0] + s.back[1], n + 1);
        // costas de uma página: a frente é ímpar e o verso é a seguinte (par)
        const fOdd = rtl ? s.front[0] : s.front[1];
        const bEven = rtl ? s.back[1] : s.back[0];
        assert.equal(bEven, fOdd + 1);
        assert.equal(fOdd, 2 * i + 1);
      });
    }
  }
});

// ------------------------------------------------------------------- planejamento

const mk = (n, p = 'p') => Array.from({ length: n }, (_, i) => ({ kind: 'image', id: p + (i + 1) }));

test('capa separada: miolo completa com brancas no fim e capa vira folha de 4', () => {
  const r = C.planBooklet({ mode: 'separate', pages: mk(10), front: { id: 'F' }, back: { id: 'B' } });
  assert.equal(r.interior.length, 12);
  assert.equal(r.padCount, 2);
  assert.ok(r.interior.slice(10).every((x) => x.kind === 'blank' && x.auto));
  // [capa, 2ª capa, 3ª capa, contracapa]
  assert.equal(r.cover[0].id, 'F');
  assert.equal(r.cover[3].id, 'B');
  assert.equal(r.cover[1].kind, 'blank');
  assert.equal(r.cover[2].kind, 'blank');
  // a folha de capa tem a contracapa à esquerda e a capa à direita (por fora)
  assert.deepEqual(C.imposeSheets(4)[0].front, [4, 1]);
});

test('sem capa cadastrada não há folha de capa', () => {
  const r = C.planBooklet({ mode: 'separate', pages: mk(8) });
  assert.equal(r.cover, null);
  assert.equal(r.interior.length, 8);
  assert.equal(r.padCount, 0);
});

test('tudo junto: brancas entram antes da contracapa', () => {
  const r = C.planBooklet({ mode: 'together', pages: mk(9), front: { id: 'F' }, back: { id: 'B' } });
  assert.equal(r.interior.length, 12); // 1 + 9 + 1 = 11 → 12
  assert.equal(r.padCount, 1);
  assert.equal(r.interior[0].id, 'F');
  assert.equal(r.interior[11].id, 'B');
  assert.equal(r.interior[10].kind, 'blank');
  assert.equal(r.cover, null);
});

test('tudo junto sem capa: usa a lista como está', () => {
  const r = C.planBooklet({ mode: 'together', pages: mk(5) });
  assert.equal(r.interior.length, 8);
  assert.equal(r.padCount, 3);
  assert.equal(r.interior[0].id, 'p1');
});

test('projeto vazio não gera nada', () => {
  assert.deepEqual(C.planBooklet({ mode: 'separate', pages: [] }), { interior: [], cover: null, padCount: 0 });
  assert.equal(C.planBooklet({ mode: 'together', pages: [] }).interior.length, 0);
});

// --------------------------------------------------------------------------- creep

test('creep: folha 0 não desloca e cada folha soma a espessura da externa × fator', () => {
  assert.deepEqual(C.creepOffsets([0.1, 0.1, 0.1], 1).map((v) => C.round(v, 4)), [0, 0.1, 0.2]);
  assert.deepEqual(C.creepOffsets([0.1, 0.1, 0.1], 1.2).map((v) => C.round(v, 4)), [0, 0.12, 0.24]);
});

test('creep com capa mais grossa desloca já a primeira folha do miolo', () => {
  const t = C.sheetThicknesses(3, 1, 0.1, 0.2);
  assert.deepEqual(t, [0.2, 0.1, 0.1, 0.1]);
  assert.deepEqual(C.creepOffsets(t, 1).map((v) => C.round(v, 4)), [0, 0.2, 0.3, 0.4]);
});

test('calibração devolve o fator que reproduz a medida', () => {
  // 6 folhas de 0,10 mm; a mais interna sobrou 1,5 mm → fator 3
  const f = C.calibrateFactor(6, 0.1, 1.5);
  near(f, 3);
  const off = C.creepOffsets(Array(6).fill(0.1), f);
  near(off[5], 1.5, 1e-6);
  assert.equal(C.calibrateFactor(1, 0.1, 1), null);
  assert.equal(C.calibrateFactor(5, 0, 1), null);
});

// ------------------------------------------------------------------------ geometria

test('sem refile cada página ocupa a meia-folha inteira', () => {
  const g = C.sheetGeometry(297, 210, { enabled: false, margin: 3 }, 5);
  assert.deepEqual(g.left.art, { x0: 0, y0: 0, x1: 148.5, y1: 210 });
  assert.deepEqual(g.right.art, { x0: 148.5, y0: 0, x1: 297, y1: 210 });
  assert.equal(g.left.cutX, null);
  assert.equal(g.offset, 0); // creep é ignorado sem refile
  assert.deepEqual(C.cropMarks(g), []);
});

test('refile na folha externa: A4 vira 145,5 mm de largura com 3 mm de sobra', () => {
  const g = C.sheetGeometry(297, 210, { enabled: true, margin: 3, topBottom: 0 }, 0);
  near(g.left.art.x0, 3);
  near(g.left.art.x1, 148.5);
  near(g.left.cutX, 3);
  near(g.right.art.x0, 148.5);
  near(g.right.art.x1, 294);
  near(g.right.cutX, 294);
  near(g.left.overhang, 3);
  near(g.right.overhang, 3);
});

test('refile com creep: a arte da folha interna escorrega para a dobra e a sobra cresce', () => {
  const g = C.sheetGeometry(297, 210, { enabled: true, margin: 3, topBottom: 0 }, 0.5);
  near(g.left.art.x0, 3.5);
  near(g.left.art.x1, 149); // passa 0,5 mm da dobra (será recortada)
  near(g.left.overhang, 3.5);
  near(g.right.art.x0, 148); // idem, para o outro lado
  near(g.right.art.x1, 293.5);
  near(g.right.overhang, 3.5); // 297 − 293,5: a sobra cresce junto com o creep
});

test('invariante: depois do corte único, todas as folhas ficam com a mesma largura no bloco', () => {
  const offsets = C.creepOffsets(Array(8).fill(0.11), 1.2);
  const finished = 148.5 - 3;
  offsets.forEach((e) => {
    const g = C.sheetGeometry(297, 210, { enabled: true, margin: 3 }, e);
    // distância da linha de corte até a dobra desta folha + quanto a dobra dela
    // está deslocada em relação à lombada de referência = largura do bloco
    const fromOwnFold = g.half - g.left.cutX;
    near(fromOwnFold + e, finished, 1e-9);
    near(g.right.cutX - g.half + e, finished, 1e-9);
  });
});

test('refile em cima e embaixo reduz a altura da arte', () => {
  const g = C.sheetGeometry(297, 210, { enabled: true, margin: 3, topBottom: 2 }, 0);
  near(g.left.art.y0, 2);
  near(g.left.art.y1, 208);
});

test('resumo do refile', () => {
  const offsets = C.creepOffsets(Array(5).fill(0.1), 1);
  const s = C.trimSummary(297, 210, { enabled: true, margin: 3, topBottom: 0 }, offsets);
  assert.equal(s.finishedW, 145.5);
  assert.equal(s.finishedH, 210);
  assert.equal(s.cutOuter, 3);
  near(s.cutInner, 3.4);
  near(s.creepTotal, 0.4);
  assert.equal(C.trimSummary(297, 210, { enabled: false }, offsets), null);
});

test('marcas de corte ficam exatamente na linha de corte, dentro da altura útil', () => {
  const g = C.sheetGeometry(297, 210, { enabled: true, margin: 3, topBottom: 0 }, 0.3);
  const m = C.cropMarks(g);
  assert.equal(m.length, 6);
  m.forEach((k) => {
    assert.ok([g.left.cutX, g.right.cutX].some((x) => Math.abs(x - k.x) < 1e-9));
    assert.ok(k.y0 >= 0 && k.y1 <= 210 && k.y1 > k.y0);
  });
});

// ---------------------------------------------------------------------- conteúdo

test('contain centraliza sem cortar; cover preenche', () => {
  const box = { x0: 0, y0: 0, x1: 145.5, y1: 210 };
  const c = C.fitContent(148, 210, 0, box, 'contain');
  near(c.w, 145.5);
  near(c.h, 210 * (145.5 / 148));
  near(c.y, (210 - c.h) / 2);
  const v = C.fitContent(148, 210, 0, box, 'cover');
  near(v.h, 210);
  assert.ok(v.w > 145.5);
  near(v.x, (145.5 - v.w) / 2);
});

test('rotação de 90° troca largura e altura no cálculo de encaixe', () => {
  const box = { x0: 0, y0: 0, x1: 100, y1: 100 };
  const r = C.fitContent(200, 100, 90, box, 'contain'); // exibida 100×200 → cabe a 50×100
  near(r.w, 50);
  near(r.h, 100);
});

test('rotatedPlacement: 90° horário', () => {
  const p = C.rotatedPlacement({ x: 10, y: 20, w: 100, h: 50 }, 90);
  assert.deepEqual([p.width, p.height, p.rotateDeg], [50, 100, -90]);
  near(p.x, 10);
  near(p.y, 70);
});

test('rotatedPlacement: a caixa do conteúdo girado é exatamente o retângulo pedido', () => {
  // mesma transformação que o pdf-lib aplica: gira (anti-horário) em torno de (x, y)
  const bbox = (p) => {
    const th = (p.rotateDeg * Math.PI) / 180;
    const pts = [[0, 0], [p.width, 0], [p.width, p.height], [0, p.height]].map(([u, v]) => [
      p.x + u * Math.cos(th) - v * Math.sin(th),
      p.y + u * Math.sin(th) + v * Math.cos(th),
    ]);
    const xs = pts.map((q) => q[0]);
    const ys = pts.map((q) => q[1]);
    return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)];
  };
  for (const rot of [0, 90, 180, 270, 360, -90]) {
    for (const rect of [{ x: 10, y: 20, w: 100, h: 50 }, { x: 0, y: 0, w: 148.5, h: 210 }, { x: 3.3, y: 7.7, w: 12, h: 99 }]) {
      const [x0, y0, x1, y1] = bbox(C.rotatedPlacement(rect, rot));
      near(x0, rect.x, 1e-6);
      near(y0, rect.y, 1e-6);
      near(x1, rect.x + rect.w, 1e-6);
      near(y1, rect.y + rect.h, 1e-6);
    }
  }
});

test('ordem natural de nomes de arquivo', () => {
  const names = ['pag10.jpg', 'pag2.jpg', 'pag1.jpg', 'Pag3.jpg'];
  assert.deepEqual(names.sort(C.naturalCompare), ['pag1.jpg', 'pag2.jpg', 'Pag3.jpg', 'pag10.jpg']);
});

test('planSheets liga imposição, geometria e creep', () => {
  const items = mk(12);
  const offsets = C.creepOffsets([0.1, 0.1, 0.1], 1);
  const plan = C.planSheets(items, { sheetW: 297, sheetH: 210, trim: { enabled: true, margin: 3 }, offsets });
  assert.equal(plan.length, 3);
  assert.equal(plan[0].faces[0].slots[0].item.id, 'p12');
  assert.equal(plan[0].faces[0].slots[1].item.id, 'p1');
  assert.equal(plan[1].faces[1].slots[0].pageNo, 4);
  near(plan[2].offset, 0.2);
  // com capa à parte, o miolo começa na 2ª posição do bloco
  const plan2 = C.planSheets(items, {
    sheetW: 297,
    sheetH: 210,
    trim: { enabled: true, margin: 3 },
    offsets: C.creepOffsets([0.2, 0.1, 0.1, 0.1], 1),
    nestStart: 1,
  });
  near(plan2[0].offset, 0.2);
  near(plan2[2].offset, 0.4);
});

test('sobra absurda não gera página de largura negativa', () => {
  const g = C.sheetGeometry(297, 210, { enabled: true, margin: 500, topBottom: 500 }, 0);
  assert.ok(g.left.art.x1 - g.left.art.x0 >= 10 - 1e-9);
  assert.ok(g.left.art.y1 - g.left.art.y0 >= 20 - 1e-9);
  const s = C.trimSummary(297, 210, { enabled: true, margin: 500, topBottom: 0 }, [0]);
  assert.ok(s.finishedW >= 10);
  const n = C.sheetGeometry(297, 210, { enabled: true, margin: NaN, topBottom: -3 }, 0);
  assert.equal(n.left.overhang, 0);
});
