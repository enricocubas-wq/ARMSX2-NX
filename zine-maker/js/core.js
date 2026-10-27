/*
 * core.js — lógica pura do Zine Maker (sem DOM, sem pdf-lib).
 *
 * Tudo aqui é testável no Node (tests/core.test.js) e roda igual no navegador.
 * Convenções:
 *   - medidas em milímetros; origem no canto inferior esquerdo (como no PDF);
 *   - páginas numeradas a partir de 1;
 *   - a folha é sempre paisagem: duas páginas lado a lado, dobra no meio.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.ZineCore = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const MM = 72 / 25.4; // 1 mm em pontos PDF

  /** Folhas suportadas (paisagem: lado maior × lado menor). */
  const SHEETS = {
    a4: { label: 'A4 → livreto A5', w: 297, h: 210 },
    a3: { label: 'A3 → livreto A4', w: 420, h: 297 },
    a5: { label: 'A5 → livreto A6', w: 210, h: 148 },
    letter: { label: 'Carta → livreto meia-carta', w: 279.4, h: 215.9 },
    custom: { label: 'Personalizado…', w: 297, h: 210 },
  };

  /** Espessuras típicas por gramatura (mm). São médias: papéis variam. */
  const PAPERS = [
    { id: '75', label: '75 g/m² (sulfite comum)', mm: 0.1 },
    { id: '90', label: '90 g/m²', mm: 0.11 },
    { id: '120', label: '120 g/m²', mm: 0.14 },
    { id: '150', label: '150 g/m²', mm: 0.17 },
    { id: '180', label: '180 g/m² (capa)', mm: 0.2 },
    { id: '240', label: '240 g/m² (cartão)', mm: 0.28 },
    { id: 'custom', label: 'Outra espessura…', mm: 0.1 },
  ];

  const DEFAULT_SETTINGS = {
    sheet: 'a4',
    customSheet: { w: 297, h: 210 },
    mode: 'separate', // 'separate' = miolo + capa em papel à parte | 'together' = um livreto só
    rtl: false, // leitura da direita p/ esquerda (mangá, árabe, hebraico)
    duplex: 'short', // borda em que a impressora vira a folha: 'short' | 'long'
    paperInner: '75',
    paperCover: '180',
    customInnerMm: 0.1,
    customCoverMm: 0.2,
    creepFactor: 1.2, // folga prática sobre a conta teórica (ver docs/PESQUISA.md)
    trim: {
      enabled: false, // "prever refile": reserva sobra na lateral que será cortada
      margin: 3, // sobra mínima (mm) removida da folha mais externa
      topBottom: 0, // refile também em cima e embaixo (mm por lado; 0 = não refila)
      marks: true, // marcas de corte na linha de refile
      creep: true, // desloca a arte das folhas internas para compensar a espessura do papel
    },
    fit: 'contain', // 'contain' = nada é cortado | 'cover' = preenche e pode cortar bordas
    fill: 'white', // sobra: 'white' = branco | 'mirror' = espelha a borda da página
  };

  // ---------------------------------------------------------------- utilidades

  const collator =
    typeof Intl !== 'undefined' ? new Intl.Collator('pt-BR', { numeric: true, sensitivity: 'base' }) : null;

  /** Ordem "natural" de nomes: pagina2 vem antes de pagina10. */
  function naturalCompare(a, b) {
    return collator ? collator.compare(a, b) : a < b ? -1 : a > b ? 1 : 0;
  }

  function padToMultiple(n, m) {
    m = m || 4;
    return Math.ceil(n / m) * m;
  }

  function round(v, d) {
    const f = Math.pow(10, d == null ? 2 : d);
    return Math.round(v * f) / f;
  }

  function paperThickness(settings, role) {
    const id = role === 'cover' ? settings.paperCover : settings.paperInner;
    if (id === 'custom') return role === 'cover' ? settings.customCoverMm : settings.customInnerMm;
    const p = PAPERS.find((x) => x.id === id);
    return p ? p.mm : 0.1;
  }

  /** Dimensões da folha (paisagem) conforme as configurações. */
  function sheetSize(settings) {
    const s = settings.sheet === 'custom' ? settings.customSheet : SHEETS[settings.sheet] || SHEETS.a4;
    return { w: Math.max(s.w, s.h), h: Math.min(s.w, s.h) };
  }

  // ------------------------------------------------------------------ imposição

  /**
   * Imposição de livreto grampeado ("saddle stitch").
   * Para N páginas (múltiplo de 4) devolve N/4 folhas; cada folha tem frente e verso
   * com [esquerda, direita]. Folha 0 é a mais externa.
   *   N=12 → 12|1 · 2|11   10|3 · 4|9   8|5 · 6|7
   * Com rtl=true as posições esquerda/direita se invertem.
   */
  function imposeSheets(totalPages, rtl) {
    if (!Number.isInteger(totalPages) || totalPages < 4 || totalPages % 4 !== 0) {
      throw new Error('O total de páginas precisa ser múltiplo de 4 (recebido: ' + totalPages + ')');
    }
    const n = totalPages;
    const sheets = [];
    for (let i = 0; i < n / 4; i++) {
      let front = [n - 2 * i, 2 * i + 1];
      let back = [2 * i + 2, n - 2 * i - 1];
      if (rtl) {
        front = front.slice().reverse();
        back = back.slice().reverse();
      }
      sheets.push({ front, back });
    }
    return sheets;
  }

  /**
   * Monta a sequência de páginas do projeto.
   *
   * project = { mode, pages[], front, back, insideFront, insideBack }
   * (itens são objetos opacos; vazio/ausente = página em branco).
   *
   * 'separate': miolo = pages + brancas até múltiplo de 4; capa = folha própria
   *             (4 páginas: capa, 2ª capa, 3ª capa, contracapa).
   * 'together': tudo num livreto só: [capa, 2ª capa] + pages + brancas + [3ª capa, contracapa].
   *             As brancas entram antes da contracapa, que continua sendo a última página.
   */
  function planBooklet(project) {
    const blank = () => ({ kind: 'blank', auto: true });
    const pages = project.pages || [];
    const { front, back, insideFront, insideBack } = project;
    const hasCover = !!(front || back || insideFront || insideBack);

    if (project.mode === 'together') {
      const head = [front, insideFront].filter(Boolean);
      const tail = [insideBack, back].filter(Boolean);
      const body = head.concat(pages);
      const used = body.length + tail.length;
      if (used === 0) return { interior: [], cover: null, padCount: 0 };
      const total = padToMultiple(Math.max(used, 1), 4);
      const padCount = total - used;
      const pad = Array.from({ length: padCount }, blank);
      return { interior: body.concat(pad, tail), cover: null, padCount };
    }

    const total = pages.length ? padToMultiple(pages.length, 4) : 0;
    const padCount = total - pages.length;
    const interior = pages.concat(Array.from({ length: padCount }, blank));
    const cover = hasCover
      ? [front || blank(), insideFront || blank(), insideBack || blank(), back || blank()]
      : null;
    return { interior, cover, padCount };
  }

  // --------------------------------------------------------------------- creep

  /**
   * Deslocamento (mm) de cada folha em relação à mais externa.
   * Cada folha que "abraça" as de dentro empurra as internas para fora na margem
   * de corte (creep). A folha k fica deslocada pela soma da espessura das k folhas
   * externas a ela, vezes um fator prático (>1 compensa dobras reais).
   *
   *   offsets[0] = 0; offsets[k] = offsets[k-1] + espessura[k-1] * fator
   */
  function creepOffsets(thicknessesMm, factor) {
    const f = factor == null ? 1 : factor;
    const out = [];
    let acc = 0;
    for (let k = 0; k < thicknessesMm.length; k++) {
      out.push(acc);
      acc += thicknessesMm[k] * f;
    }
    return out;
  }

  /** Espessuras por folha: [capa?, ...miolo] ou só miolo. */
  function sheetThicknesses(innerSheets, coverSheets, tInner, tCover) {
    return Array.from({ length: coverSheets }, () => tCover).concat(Array.from({ length: innerSheets }, () => tInner));
  }

  /**
   * Calibração com um zine real: depois de grampear e dobrar um zine de `sheets` folhas
   * do mesmo papel, mede-se quanto a folha mais interna sobra além da mais externa.
   * Devolve o fator que faz o modelo bater com a medida.
   */
  function calibrateFactor(sheets, thicknessMm, measuredMm) {
    if (!(sheets >= 2) || !(thicknessMm > 0) || !(measuredMm >= 0)) return null;
    return round(measuredMm / ((sheets - 1) * thicknessMm), 3);
  }

  // ------------------------------------------------------------------ geometria

  const MIN_PAGE = 10; // mm: a página final nunca fica menor que isso, por mais sobra que se peça
  function clampTrim(v, max) {
    v = Number(v);
    if (!isFinite(v)) return 0;
    return Math.min(Math.max(0, v), Math.max(0, max));
  }

  /**
   * Geometria de uma folha (as duas faces usam a mesma).
   *
   * Sem refile: cada página ocupa a meia-folha inteira.
   * Com refile: a página final tem largura (meia-folha − margem). A arte fica
   * alinhada à linha de corte; nas folhas internas ela "escorrega" `offset` mm
   * para dentro (em direção à dobra) para compensar o creep, de modo que, depois
   * de cortar o bloco inteiro de uma vez, todas as páginas tenham a mesma margem.
   *
   * Devolve { half:[x0,x1], art:{x0,y0,x1,y1}, cutX, overhang } por página.
   */
  function sheetGeometry(sheetW, sheetH, trim, offset) {
    const h = sheetW / 2;
    const t = trim && trim.enabled ? trim : null;
    const off = t ? Math.max(0, offset || 0) : 0;
    const margin = t ? clampTrim(t.margin, h - MIN_PAGE) : 0;
    const tb = t ? clampTrim(t.topBottom, sheetH / 2 - MIN_PAGE) : 0;
    const finishedW = h - margin;
    const y0 = tb;
    const y1 = sheetH - tb;

    const left = {
      side: 'left',
      half: [0, h],
      art: { x0: h + off - finishedW, y0, x1: h + off, y1 },
      cutX: t ? h + off - finishedW : null,
    };
    const right = {
      side: 'right',
      half: [h, sheetW],
      art: { x0: h - off, y0, x1: h - off + finishedW, y1 },
      cutX: t ? h - off + finishedW : null,
    };
    if (!t) {
      left.art = { x0: 0, y0: 0, x1: h, y1: sheetH };
      right.art = { x0: h, y0: 0, x1: sheetW, y1: sheetH };
    }
    left.overhang = t ? left.art.x0 : 0;
    right.overhang = t ? sheetW - right.art.x1 : 0;
    return { sheetW, sheetH, half: h, trimmed: !!t, left, right, offset: off, topBottom: tb };
  }

  /** Resumo do que será cortado, para mostrar na interface. */
  function trimSummary(sheetW, sheetH, trim, offsets) {
    if (!trim || !trim.enabled) return null;
    const h = sheetW / 2;
    const margin = clampTrim(trim.margin, h - MIN_PAGE);
    const tb = clampTrim(trim.topBottom, sheetH / 2 - MIN_PAGE);
    const maxOff = offsets.length ? Math.max.apply(null, offsets) : 0;
    return {
      finishedW: round(h - margin, 2),
      finishedH: round(sheetH - 2 * tb, 2),
      cutOuter: round(margin, 2), // sai da folha mais externa
      cutInner: round(margin + maxOff, 2), // sai da folha mais interna
      creepTotal: round(maxOff, 2),
      gutterLoss: round(maxOff, 2), // quanto da margem interna das páginas centrais "some" na lombada
      topBottom: tb,
    };
  }

  /**
   * Posiciona o conteúdo (já com a rotação aplicada) dentro de uma caixa.
   * Devolve o retângulo exibido { x, y, w, h } em mm.
   *   contain → cabe inteiro, centralizado;  cover → preenche a caixa (pode passar).
   */
  function fitContent(srcW, srcH, rotation, box, mode) {
    const swap = ((rotation % 180) + 180) % 180 === 90;
    const dw = swap ? srcH : srcW;
    const dh = swap ? srcW : srcH;
    const bw = box.x1 - box.x0;
    const bh = box.y1 - box.y0;
    const s = mode === 'cover' ? Math.max(bw / dw, bh / dh) : Math.min(bw / dw, bh / dh);
    const w = dw * s;
    const h = dh * s;
    return { x: box.x0 + (bw - w) / 2, y: box.y0 + (bh - h) / 2, w, h };
  }

  /**
   * Converte "retângulo exibido + rotação horária" nos parâmetros de desenho do
   * pdf-lib (largura/altura do conteúdo sem girar, ponto de origem e ângulo anti-horário).
   */
  function rotatedPlacement(rect, rotationCW) {
    const r = ((Math.round(rotationCW / 90) * 90) % 360 + 360) % 360;
    const swap = r === 90 || r === 270;
    const cw = swap ? rect.h : rect.w; // dimensões do conteúdo antes de girar
    const ch = swap ? rect.w : rect.h;
    const theta = (-r * Math.PI) / 180; // pdf-lib gira no sentido anti-horário
    const c = Math.round(Math.cos(theta));
    const s = Math.round(Math.sin(theta));
    const xs = [0, cw, cw, 0].map((u, i) => u * c - [0, 0, ch, ch][i] * s);
    const ys = [0, cw, cw, 0].map((u, i) => u * s + [0, 0, ch, ch][i] * c);
    const minX = Math.min.apply(null, xs);
    const minY = Math.min.apply(null, ys);
    return { width: cw, height: ch, x: rect.x - minX, y: rect.y - minY, rotateDeg: -r };
  }

  // ---------------------------------------------------------------- plano de folhas

  /**
   * Plano completo de um PDF: para cada folha, o item de cada posição nas duas faces.
   *
   *   items:      sequência de páginas (múltiplo de 4)
   *   nestStart:  posição da primeira folha no bloco inteiro (miolo com capa à parte começa em 1)
   *   offsets:    deslocamento de creep por posição de folha no bloco inteiro
   */
  function planSheets(items, opts) {
    const o = opts || {};
    const sheetW = o.sheetW;
    const sheetH = o.sheetH;
    const nestStart = o.nestStart || 0;
    const offsets = o.offsets || [];
    const imp = imposeSheets(items.length, !!o.rtl);
    return imp.map((s, i) => {
      const nest = nestStart + i;
      const offset = offsets[nest] || 0;
      const geom = sheetGeometry(sheetW, sheetH, o.trim, offset);
      const face = (pair, name) => ({
        name,
        slots: [
          { side: 'left', pageNo: pair[0], item: items[pair[0] - 1], geom: geom.left },
          { side: 'right', pageNo: pair[1], item: items[pair[1] - 1], geom: geom.right },
        ],
      });
      return {
        index: i,
        nest,
        offset: geom.offset,
        geom,
        faces: [face(s.front, 'frente'), face(s.back, 'verso')],
      };
    });
  }

  /** Marcas de corte: pequenos traços sobre a linha de refile (útil p/ guilhotina). */
  function cropMarks(geom) {
    if (!geom.trimmed) return [];
    const marks = [];
    const H = geom.sheetH;
    const len = Math.min(8, H / 6);
    [0.25, 0.5, 0.75].forEach((f) => {
      const cy = geom.left.art.y0 + (geom.left.art.y1 - geom.left.art.y0) * f;
      [geom.left, geom.right].forEach((p) => {
        marks.push({ x: p.cutX, y0: cy - len / 2, y1: cy + len / 2 });
      });
    });
    return marks;
  }

  /** Texto "12 | 1" para legendas de pré-visualização. */
  function pairLabel(pair) {
    return pair[0] + ' | ' + pair[1];
  }

  return {
    MM,
    SHEETS,
    PAPERS,
    DEFAULT_SETTINGS,
    naturalCompare,
    padToMultiple,
    round,
    paperThickness,
    sheetSize,
    imposeSheets,
    planBooklet,
    creepOffsets,
    sheetThicknesses,
    calibrateFactor,
    sheetGeometry,
    trimSummary,
    fitContent,
    rotatedPlacement,
    planSheets,
    cropMarks,
    pairLabel,
  };
});
