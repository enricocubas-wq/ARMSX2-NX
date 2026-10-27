/* app.js — interface do Zine Maker. */
(function () {
  'use strict';

  const Core = window.ZineCore;
  const Builder = window.ZineBuilder;
  const Sources = window.ZineSources;
  const $ = (sel, el) => (el || document).querySelector(sel);
  const $$ = (sel, el) => Array.from((el || document).querySelectorAll(sel));

  // pdf.js: em file:// a origem é "null" e ele sempre cai no "fake worker" (lê o PDF na thread da
  // interface e trava a tela com PDFs grandes). No arquivo único o worker vem embutido em base64 e
  // cada documento recebe um Worker próprio criado a partir de um Blob (ver Sources.openPdf), o que
  // funciona até por file://. Na pasta normal (http/https) o worker é carregado de vendor/.
  if (window.pdfjsLib) {
    const b64 = window.__ZINE_PDF_WORKER_B64;
    if (b64) {
      const url = URL.createObjectURL(new Blob([Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))], { type: 'text/javascript' }));
      window.__ZINE_PDF_WORKER_URL = url;
      window.pdfjsLib.GlobalWorkerOptions.workerSrc = url; // usado só se o Worker real não puder ser criado
    } else {
      window.pdfjsLib.GlobalWorkerOptions.workerSrc = 'vendor/pdf.worker.min.js';
    }
  }

  const STORE_KEY = 'zinemaker.v1';
  const SLOT_DEFS = [
    { key: 'front', label: 'Capa', hint: '1ª capa' },
    { key: 'insideFront', label: '2ª capa', hint: 'verso da capa · opcional' },
    { key: 'insideBack', label: '3ª capa', hint: 'verso da contracapa · opcional' },
    { key: 'back', label: 'Contracapa', hint: '4ª capa' },
  ];

  const state = {
    title: '',
    pages: [], // miolo
    slots: { front: null, insideFront: null, insideBack: null, back: null },
    settings: clone(Core.DEFAULT_SETTINGS),
    tab: 'miolo',
  };

  // ------------------------------------------------------------- utilidades

  function clone(o) {
    return JSON.parse(JSON.stringify(o));
  }
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const fmt = (n, max) => Number(n).toLocaleString('pt-BR', { minimumFractionDigits: 0, maximumFractionDigits: max == null ? 2 : max });
  const plural = (n, one, many) => (n === 1 ? one : many);
  const slug = (s) =>
    String(s || '')
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .replace(/[^a-zA-Z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .toLowerCase() || 'zine';

  function getPath(o, path) {
    return path.split('.').reduce((a, k) => (a == null ? a : a[k]), o);
  }
  function setPath(o, path, v) {
    const ks = path.split('.');
    const last = ks.pop();
    ks.reduce((a, k) => a[k], o)[last] = v;
  }

  let toastTimer;
  function toast(msg, isErr, ms) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.toggle('err', !!isErr);
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), ms || (isErr ? 6000 : 3200));
  }

  // ------------------------------------------------------------ persistência

  function merge(base, saved) {
    if (!saved || typeof saved !== 'object') return base;
    Object.keys(base).forEach((k) => {
      if (!(k in saved)) return;
      if (base[k] && typeof base[k] === 'object' && !Array.isArray(base[k])) merge(base[k], saved[k]);
      else if (typeof saved[k] === typeof base[k]) base[k] = saved[k];
    });
    return base;
  }

  function load() {
    try {
      const raw = JSON.parse(localStorage.getItem(STORE_KEY) || 'null');
      if (raw) {
        state.title = typeof raw.title === 'string' ? raw.title : '';
        merge(state.settings, raw.settings);
      }
    } catch (e) {
      /* sem storage: segue com os padrões */
    }
  }
  function save() {
    try {
      localStorage.setItem(STORE_KEY, JSON.stringify({ title: state.title, settings: state.settings }));
    } catch (e) {
      /* modo privado etc. */
    }
  }

  // ---------------------------------------------------------------- derivados

  function sheetName(s) {
    if (s.sheet === 'custom') return fmt(s.customSheet.w, 1) + '×' + fmt(s.customSheet.h, 1) + ' mm';
    return Core.SHEETS[s.sheet].label.split(' →')[0];
  }

  /** Tudo que a interface precisa saber, calculado a partir do estado. */
  function derive() {
    const s = state.settings;
    const sheet = Core.sheetSize(s);
    const plan = Core.planBooklet({ mode: s.mode, pages: state.pages, ...state.slots });
    const innerSheets = plan.interior.length / 4;
    const coverSheets = plan.cover ? 1 : 0;
    const tIn = Core.paperThickness(s, 'inner');
    const tCover = s.mode === 'together' ? tIn : Core.paperThickness(s, 'cover');
    const thick = Core.sheetThicknesses(innerSheets, coverSheets, tIn, tCover);
    const useCreep = s.trim.enabled && s.trim.creep;
    const offsets = useCreep ? Core.creepOffsets(thick, s.creepFactor) : thick.map(() => 0);
    const trim = Core.trimSummary(sheet.w, sheet.h, s.trim, offsets);
    const geom = Core.sheetGeometry(sheet.w, sheet.h, s.trim, 0);
    const box = geom.left.art;
    return { s, sheet, plan, innerSheets, coverSheets, tIn, tCover, offsets, trim, boxW: box.x1 - box.x0, boxH: box.y1 - box.y0 };
  }

  // ---------------------------------------------------------------- controles

  function readControl(el) {
    if (el.type === 'checkbox') return el.checked;
    if (el.type === 'number') return el.value === '' ? NaN : parseFloat(el.value);
    if (el.dataset.type === 'bool') return el.value === 'true';
    return el.value;
  }

  function clampNum(el, v) {
    const min = el.min !== '' ? parseFloat(el.min) : -Infinity;
    const max = el.max !== '' ? parseFloat(el.max) : Infinity;
    return Math.min(max, Math.max(min, v));
  }

  function syncControls() {
    $$('[data-k]').forEach((el) => {
      const v = getPath(state.settings, el.dataset.k);
      if (el.type === 'checkbox') el.checked = !!v;
      else if (el.type === 'radio') el.checked = String(v) === el.value;
      else if (el !== document.activeElement) el.value = String(v);
    });
    $$('[data-show-if]').forEach((el) => {
      const [k, want] = el.dataset.showIf.split('=');
      const v = getPath(state.settings, k);
      el.hidden = want === undefined ? !v : String(v) !== want;
    });
    $('#title').value = state.title;
    const d = derive();
    $('#h-sheet').textContent =
      'Cada página fica com ' + fmt(d.sheet.w / 2, 1) + ' × ' + fmt(d.sheet.h, 1) + ' mm' + (d.s.trim.enabled ? ' antes do refile.' : '.');
  }

  function onInput(e) {
    const el = e.target.closest('[data-k]');
    if (!el) return;
    if (el.type === 'radio' && !el.checked) return;
    let v = readControl(el);
    if (typeof v === 'number') {
      if (!isFinite(v)) return;
      v = clampNum(el, v);
    }
    setPath(state.settings, el.dataset.k, v);
    onSettings();
  }

  function onSettings() {
    save();
    syncControls();
    renderAll();
    schedulePreview();
  }

  // ------------------------------------------------------------------- render

  function renderAll() {
    const d = derive();
    renderTray(d);
    renderSlots(d);
    renderSummary(d);
    renderExport(d);
    $('#pages').classList.toggle('compact', state.pages.length > 0);
  }

  function thumbOf(src) {
    return src.thumbShown || src.thumbUrl;
  }

  function renderTray(d) {
    const num = new Map();
    d.plan.interior.forEach((it, i) => num.set(it, i + 1));
    const html = [];
    state.pages.forEach((p, i) => {
      const n = num.get(p);
      const thumb =
        p.kind === 'blank' ? '<span>em branco</span>' : '<img alt="" draggable="false" src="' + thumbOf(p) + '">';
      html.push(
        '<li class="pg' + (p.kind === 'blank' ? ' blank' : '') + '" draggable="true" data-id="' + p.id + '">' +
          '<span class="pg-num" title="Página ' + n + ' do livreto">' + n + '</span>' +
          '<div class="pg-thumb">' + thumb + '</div>' +
          '<div class="pg-name" title="' + esc(p.label) + '">' + esc(p.label) + '</div>' +
          '<div class="pg-tools" role="group" aria-label="Ações da página ' + n + '">' +
          '<button type="button" data-act="left" title="Mover para trás" aria-label="Mover para trás"' + (i === 0 ? ' disabled' : '') + '>◀</button>' +
          '<button type="button" data-act="right" title="Mover para frente" aria-label="Mover para frente"' + (i === state.pages.length - 1 ? ' disabled' : '') + '>▶</button>' +
          (p.kind === 'blank' ? '' : '<button type="button" data-act="rot" title="Girar 90°" aria-label="Girar 90 graus">↻</button>') +
          '<button type="button" data-act="dup" title="Duplicar" aria-label="Duplicar página">⧉</button>' +
          '<button type="button" class="rm" data-act="rm" title="Remover" aria-label="Remover página">✕</button>' +
          '</div></li>'
      );
    });
    d.plan.interior.forEach((it) => {
      if (!it.auto) return;
      html.push(
        '<li class="pg auto" aria-label="Página em branco automática"><span class="pg-num">' + num.get(it) + '</span>' +
          '<div class="pg-thumb">em branco<br>(automática)</div><div class="pg-name">completa o múltiplo de 4</div></li>'
      );
    });
    $('#tray').innerHTML = html.join('');

    const note = $('#pad-note');
    const total = d.plan.interior.length;
    if (!state.pages.length) {
      note.innerHTML = '';
    } else if (d.plan.padCount > 0) {
      note.innerHTML =
        'Livreto só fecha em <b>múltiplos de 4</b>: entram ' + d.plan.padCount + ' ' + plural(d.plan.padCount, 'página em branco', 'páginas em branco') +
        (d.s.mode === 'together' ? ' antes da contracapa' : ' no final') + '. Total: ' + total + '.';
    } else {
      note.textContent = 'Total: ' + total + ' páginas, múltiplo de 4. ✓';
    }
  }

  function renderSlots() {
    $('#slots').innerHTML = SLOT_DEFS.map((def) => {
      const src = state.slots[def.key];
      if (src) {
        return (
          '<div class="slot filled" data-slot="' + def.key + '">' +
            '<div class="slot-thumb"><img alt="" src="' + thumbOf(src) + '"></div>' +
            '<b>' + def.label + '</b><small title="' + esc(src.label) + '">' + esc(src.label) + '</small>' +
            '<div class="slot-tools"><button type="button" data-act="rot" title="Girar 90°" aria-label="Girar ' + def.label + '">↻</button>' +
            '<button type="button" class="rm" data-act="rm" title="Remover" aria-label="Remover ' + def.label + '">✕</button></div></div>'
        );
      }
      return (
        '<div class="slot" role="button" tabindex="0" data-slot="' + def.key + '" aria-label="Adicionar ' + def.label + '">' +
          '<div class="slot-thumb">+</div><b>' + def.label + '</b><small>' + def.hint + '</small></div>'
      );
    }).join('');
  }

  function renderSummary(d) {
    const s = d.s;
    const body = $('#summary-body');
    if (!d.plan.interior.length && !d.plan.cover) {
      body.innerHTML = '<p class="empty">Nada por aqui ainda.</p>';
      return;
    }
    const name = sheetName(s);
    const rows = [];
    const total = d.plan.interior.length;
    if (total) {
      const real = total - d.plan.padCount;
      const label = s.mode === 'together' ? 'Livreto' : 'Miolo';
      rows.push(
        '<dt>' + label + '</dt><dd><b>' + real + '</b> ' + plural(real, 'página', 'páginas') +
          (d.plan.padCount ? ' + ' + d.plan.padCount + ' em branco = <b>' + total + '</b>' : '') + '<br>' +
          d.innerSheets + ' ' + plural(d.innerSheets, 'folha', 'folhas') + ' ' + esc(name) + ' (' + fmt(Core.paperThickness(s, 'inner') * 1000, 0) + ' µm)</dd>'
      );
    }
    if (d.plan.cover) rows.push('<dt>Capa</dt><dd>1 folha ' + esc(name) + ' (capa, 2ª, 3ª e contracapa)</dd>');
    const fw = d.trim ? d.trim.finishedW : d.sheet.w / 2;
    const fh = d.trim ? d.trim.finishedH : d.sheet.h;
    const scale = Math.min(d.boxW / (d.sheet.w / 2), d.boxH / d.sheet.h);
    const scaleNote =
      d.trim && s.fit === 'contain' && scale < 0.9995
        ? '<br>As páginas entram a <b>' + fmt(scale * 100, 1) + '%</b> do tamanho da meia-folha.'
        : d.trim && s.fit === 'cover'
          ? '<br>Páginas preenchem a área; as bordas podem ser cortadas.'
          : '';
    rows.push('<dt>Tamanho final</dt><dd><b>' + fmt(fw, 1) + ' × ' + fmt(fh, 1) + ' mm</b>' + (d.trim ? ' depois de refilar' : '') + scaleNote + '</dd>');
    if (d.trim) {
      rows.push(
        '<dt>Corte</dt><dd>a <b>' + fmt(d.trim.finishedW, 1) + ' mm</b> da lombada.<br>Sai de <b>' + fmt(d.trim.cutOuter, 1) +
          ' mm</b> (folha de fora) até <b>' + fmt(d.trim.cutInner, 1) + ' mm</b> (folha de dentro).</dd>'
      );
    }

    const msgs = [];
    if (d.innerSheets > 8)
      msgs.push(['warn', 'Mais de 8 folhas (' + total + ' páginas): em casa o miolo fica grosso para dobrar e grampear. Considere dividir em duas edições.']);
    if (d.trim && s.trim.margin < 4)
      msgs.push(['warn', 'Impressoras caseiras costumam deixar de imprimir os 3–5 mm da borda. Com sobra de ' + fmt(s.trim.margin, 1) + ' mm, arte até a lateral pode sair branca: faça a folha de teste.']);
    if (d.trim && d.trim.gutterLoss >= 0.8)
      msgs.push(['warn', 'Nas folhas de dentro cerca de ' + fmt(d.trim.gutterLoss, 1) + ' mm da margem interna some na lombada. Deixe pelo menos ' + fmt(Math.ceil(d.trim.gutterLoss * 2) / 2 + 2, 1) + ' mm de margem interna nas páginas.']);
    const odd = state.pages.concat(Object.values(state.slots)).filter((p) => p && p.width && p.height && oddAspect(p, d)).length;
    if (odd)
      msgs.push(['warn', odd + ' ' + plural(odd, 'página tem', 'páginas têm') + ' proporção bem diferente do livreto e vão ficar com faixas brancas. Se for um scan deitado, use ↻ para girar.']);
    if (!msgs.length && total) msgs.push(['ok', 'Tudo certo para gerar.']);

    let table = '';
    if (d.trim && d.offsets.length > 1 && d.offsets.length <= 14) {
      const lines = d.offsets.map((off, k) => {
        const isCover = d.coverSheets === 1 && k === 0;
        const label = isCover ? 'Capa' : 'Miolo ' + (k - d.coverSheets + 1);
        return '<tr><td>' + label + '</td><td>' + fmt(d.trim.cutOuter + off, 2) + ' mm</td></tr>';
      });
      table =
        '<table class="cut-table"><caption class="hint" style="text-align:left">Quanto sai de cada folha no refile</caption>' +
        '<thead><tr><th>Folha</th><th>Corte</th></tr></thead><tbody>' + lines.join('') + '</tbody></table>';
    }

    body.innerHTML =
      '<dl class="sum">' + rows.join('') + '</dl>' + table +
      '<ul class="msgs">' + msgs.map(([c, t]) => '<li class="' + c + '">' + esc(t) + '</li>').join('') + '</ul>';
  }

  function oddAspect(p, d) {
    const swap = p.rotation % 180 === 90;
    const w = swap ? p.height : p.width;
    const h = swap ? p.width : p.height;
    const r = w / h / (d.boxW / d.boxH);
    return Math.abs(r - 1) > 0.12;
  }

  const lbl = (long, short) => '<span class="lbl-l">' + long + '</span><span class="lbl-s">' + short + '</span>';

  function renderExport(d) {
    const s = d.s;
    const name = sheetName(s);
    const btns = [];
    const sheets = d.innerSheets;
    if (d.plan.interior.length) {
      btns.push(
        '<button type="button" class="btn primary" data-export="miolo">' +
          lbl(s.mode === 'together' ? 'Baixar o zine (PDF)' : 'Baixar o miolo (PDF)', s.mode === 'together' ? '↓ Zine' : '↓ Miolo') +
          '<small>' + sheets + ' ' + plural(sheets, 'folha', 'folhas') + ' · frente e verso</small></button>'
      );
    }
    if (d.plan.cover) {
      btns.push(
        '<button type="button" class="btn' + (d.plan.interior.length ? '' : ' primary') + '" data-export="capa">' + lbl('Baixar a capa (PDF)', '↓ Capa') +
          '<small>1 folha · papel ' + fmt(d.tCover * 1000, 0) + ' µm</small></button>'
      );
    }
    btns.push('<button type="button" class="btn ghost" data-export="teste">' + lbl('Folha de teste', 'Teste') + '<small>lado do verso e margem da impressora</small></button>');
    $('#export-btns').innerHTML = btns.join('');

    const parts = [];
    if (d.plan.interior.length) {
      parts.push('<b>' + (s.mode === 'together' ? 'Livreto' : 'Miolo') + '</b> ' + d.plan.interior.length + ' págs · ' + sheets + ' ' + plural(sheets, 'folha', 'folhas') + ' ' + esc(name));
    }
    if (d.plan.cover) parts.push('<b>Capa</b> 1 folha');
    const fw = d.trim ? d.trim.finishedW : d.sheet.w / 2;
    const fh = d.trim ? d.trim.finishedH : d.sheet.h;
    if (parts.length) parts.push('<b>' + fmt(fw, 1) + ' × ' + fmt(fh, 1) + ' mm</b>' + (d.trim ? ' após refile' : ''));
    $('#ab-sum').innerHTML = parts.length ? parts.join(' · ') : 'Adicione páginas para gerar o PDF.';
    renderHow(d);
  }

  function renderHow(d) {
    const s = d.s;
    const name = sheetName(s);
    const flip = s.duplex === 'short' ? 'borda curta' : 'borda longa';
    const items = [];
    items.push(
      '<b>Imprima em tamanho real.</b> No diálogo de impressão escolha <code>100%</code> / “tamanho real”, nunca “ajustar à página”. Papel ' + esc(name) +
        ', <b>frente e verso</b>, virando pela <b>' + flip + '</b>.'
    );
    items.push(
      'Sem frente e verso automático? A página 1 do PDF é a frente da folha 1 e a página 2 é o verso dela. Use a <b>folha de teste</b> para descobrir como a sua impressora puxa o papel de volta.'
    );
    items.push(
      'Dobre cada folha ao meio (a dobra é exatamente o centro). ' +
        (d.plan.cover && s.mode === 'separate' ? 'Encaixe as folhas do miolo uma dentro da outra (a folha 1 por fora) e envolva tudo com a capa.' : 'Encaixe uma dentro da outra, com a folha 1 por fora.') +
        ' Grampeie na dobra.'
    );
    if (d.trim) {
      items.push(
        '<b>Refile:</b> corte a lateral a <b>' + fmt(d.trim.finishedW, 1) + ' mm</b> medidos a partir da lombada (não da borda do papel). ' +
          (s.trim.marks ? 'As marcas pretas indicam a linha. ' : '') +
          'Assim todas as folhas ficam com a mesma largura.' +
          (d.trim.topBottom ? ' Depois corte também ' + fmt(d.trim.topBottom, 1) + ' mm em cima e embaixo.' : '')
      );
    }
    $('#how-body').innerHTML = '<ol>' + items.map((t) => '<li>' + t + '</li>').join('') + '</ol>';
  }

  // ------------------------------------------------------------ páginas e capa

  const findPage = (id) => state.pages.findIndex((p) => p.id === id);

  function movePage(id, to) {
    const from = findPage(id);
    if (from < 0) return;
    const [it] = state.pages.splice(from, 1);
    if (from < to) to -= 1;
    state.pages.splice(Math.max(0, Math.min(state.pages.length, to)), 0, it);
    changed();
  }

  function loadImg(url) {
    return new Promise((res, rej) => {
      const i = new Image();
      i.onload = () => res(i);
      i.onerror = rej;
      i.src = url;
    });
  }

  async function rotateSource(src) {
    src.rotation = (src.rotation + 90) % 360;
    const img = await loadImg(src.thumbUrl);
    const swap = src.rotation % 180 === 90;
    const c = document.createElement('canvas');
    c.width = swap ? img.naturalHeight : img.naturalWidth;
    c.height = swap ? img.naturalWidth : img.naturalHeight;
    const g = c.getContext('2d');
    g.translate(c.width / 2, c.height / 2);
    g.rotate((src.rotation * Math.PI) / 180);
    g.drawImage(img, -img.naturalWidth / 2, -img.naturalHeight / 2);
    const blob = await new Promise((r) => c.toBlob(r, 'image/jpeg', 0.85));
    if (src.thumbShown) URL.revokeObjectURL(src.thumbShown);
    src.thumbShown = URL.createObjectURL(blob);
    changed();
  }

  function changed() {
    renderAll();
    schedulePreview();
  }

  function assignToSlots(startKey, sources) {
    const keys = SLOT_DEFS.map((d) => d.key);
    let order;
    if (sources.length === 2) order = ['front', 'back'];
    else if (sources.length === 4) order = keys;
    else order = keys.slice(keys.indexOf(startKey), keys.indexOf(startKey) + sources.length);
    order.forEach((k, i) => (state.slots[k] = sources[i]));
    if (sources.length > order.length) toast('Só ' + order.length + ' ' + plural(order.length, 'arquivo coube', 'arquivos couberam') + ' na capa; o resto foi ignorado.', true);
  }

  async function ingest(files, target) {
    const list = Array.from(files || []);
    if (!list.length) return;
    toast('Lendo ' + list.length + ' ' + plural(list.length, 'arquivo', 'arquivos') + '…', false, 60000);
    try {
      const { sources, errors } = await Sources.addFiles(list, (n, t) => toast('Lendo arquivos… ' + n + '/' + t, false, 60000));
      if (sources.length) {
        if (target === 'pages') state.pages.push(...sources);
        else assignToSlots(target, sources);
        changed();
      }
      if (errors.length) toast(errors.join(' · '), true);
      else if (sources.length) toast(sources.length + ' ' + plural(sources.length, 'página adicionada', 'páginas adicionadas') + '.');
      else $('#toast').classList.remove('show');
    } catch (e) {
      toast(e.message || String(e), true);
    }
  }

  let fileTarget = 'pages';
  function pickFiles(target) {
    fileTarget = target;
    const inp = $('#file-input');
    inp.value = '';
    inp.click();
  }

  // ------------------------------------------------------------------ prévia

  let previewTimer;
  let previewToken = 0;

  function schedulePreview() {
    clearTimeout(previewTimer);
    $('#preview-body').classList.add('busy');
    previewTimer = setTimeout(renderPreview, 280);
  }

  /** Só na prévia: hachura vermelha onde será cortado e linha tracejada no corte. */
  function paintTrimOverlay(ctx, canvas, d, offset) {
    const g = Core.sheetGeometry(d.sheet.w, d.sheet.h, d.s.trim, offset);
    const k = canvas.width / g.sheetW;
    const hatch = document.createElement('canvas');
    hatch.width = hatch.height = 8;
    const hc = hatch.getContext('2d');
    hc.fillStyle = 'rgba(209,47,32,.16)';
    hc.fillRect(0, 0, 8, 8);
    hc.strokeStyle = 'rgba(209,47,32,.55)';
    hc.lineWidth = 1.2;
    hc.beginPath();
    hc.moveTo(0, 8);
    hc.lineTo(8, 0);
    hc.stroke();
    ctx.save();
    ctx.fillStyle = ctx.createPattern(hatch, 'repeat');
    const left = g.left.cutX * k;
    const right = g.right.cutX * k;
    ctx.fillRect(0, 0, left, canvas.height);
    ctx.fillRect(right, 0, canvas.width - right, canvas.height);
    const tb = g.topBottom * k;
    if (tb > 0) {
      ctx.fillRect(left, 0, right - left, tb);
      ctx.fillRect(left, canvas.height - tb, right - left, tb);
    }
    ctx.strokeStyle = '#d12f20';
    ctx.lineWidth = Math.max(1, canvas.width / 400);
    ctx.setLineDash([6, 4]);
    ctx.beginPath();
    [left, right].forEach((x) => {
      ctx.moveTo(x, 0);
      ctx.lineTo(x, canvas.height);
    });
    if (tb > 0) {
      ctx.moveTo(left, tb);
      ctx.lineTo(right, tb);
      ctx.moveTo(left, canvas.height - tb);
      ctx.lineTo(right, canvas.height - tb);
    }
    ctx.stroke();
    ctx.restore();
  }

  async function renderGroup(g, d, token) {
    const s = d.s;
    const bytes = await Builder.buildPdf(g.items.map((x) => Sources.toBuilderItem(x, 'preview')), {
      settings: s,
      offsets: d.offsets,
      nestStart: g.nestStart,
      title: 'prévia',
    });
    if (token !== previewToken) return null;
    const handle = await Sources.openPdf(bytes);
    try {
      return await paintGroup(handle.pdf, g, d, token);
    } finally {
      await handle.close();
    }
  }

  async function paintGroup(pdf, g, d, token) {
    const s = d.s;
    const imp = Core.imposeSheets(g.items.length, s.rtl);
    const wrap = document.createElement('div');
    wrap.className = 'sheets';
    wrap.dataset.group = g.key;
    wrap.style.display = 'grid';
    wrap.style.gap = '26px';
    const dpr = Math.min(2, window.devicePixelRatio || 1);

    for (let i = 0; i < imp.length; i++) {
      const sheet = document.createElement('div');
      sheet.className = 'sheet';
      const off = d.offsets[g.nestStart + i] || 0;
      const title =
        g.key === 'capa'
          ? 'Capa · folha única'
          : 'Folha ' + (i + 1) + ' de ' + imp.length;
      const cut = d.trim ? '<em>corta ' + fmt(d.trim.cutOuter + off, 2) + ' mm</em>' : '';
      sheet.innerHTML = '<div class="sheet-h">' + title + cut + '</div><div class="faces"></div>';
      const faces = $('.faces', sheet);
      for (let f = 0; f < 2; f++) {
        const page = await pdf.getPage(i * 2 + f + 1);
        const v1 = page.getViewport({ scale: 1 });
        const vp = page.getViewport({ scale: (460 * dpr) / v1.width });
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(vp.width);
        canvas.height = Math.round(vp.height);
        const ctx2d = canvas.getContext('2d');
        await page.render({ canvasContext: ctx2d, viewport: vp }).promise;
        if (d.trim) paintTrimOverlay(ctx2d, canvas, d, off);
        const pair = f === 0 ? imp[i].front : imp[i].back;
        const fig = document.createElement('figure');
        fig.className = 'face';
        fig.appendChild(canvas);
        const cap = document.createElement('figcaption');
        if (g.key === 'capa') {
          const names = { 4: 'contracapa', 1: 'capa', 2: '2ª capa', 3: '3ª capa' };
          cap.innerHTML = (f === 0 ? 'por fora' : 'por dentro') + ' · <b>' + names[pair[0]] + ' | ' + names[pair[1]] + '</b>';
        } else {
          cap.innerHTML = (f === 0 ? 'frente' : 'verso') + ' · <b>' + Core.pairLabel(pair) + '</b>';
        }
        fig.appendChild(cap);
        faces.appendChild(fig);
        if (token !== previewToken) return null;
      }
      wrap.appendChild(sheet);
    }
    return wrap;
  }

  async function renderPreview() {
    const token = ++previewToken;
    const body = $('#preview-body');
    const tabs = $('#tabs');
    const d = derive();
    const groups = [];
    if (d.plan.interior.length)
      groups.push({ key: 'miolo', label: d.s.mode === 'together' ? 'Livreto' : 'Miolo', items: d.plan.interior, nestStart: d.coverSheets, n: d.innerSheets });
    if (d.plan.cover) groups.push({ key: 'capa', label: 'Capa', items: d.plan.cover, nestStart: 0, n: 1 });

    if (!groups.length) {
      body.innerHTML = '<p class="empty">Adicione páginas para ver as folhas montadas.</p>';
      body.classList.remove('busy');
      tabs.innerHTML = '';
      return;
    }
    try {
      const done = [];
      for (const g of groups) {
        const el = await renderGroup(g, d, token);
        if (!el) return; // chegou uma versão mais nova
        done.push(el);
      }
      if (token !== previewToken) return;
      if (!groups.some((g) => g.key === state.tab)) state.tab = groups[0].key;
      if (d.trim) {
        const legend = document.createElement('p');
        legend.className = 'note';
        legend.innerHTML = 'Área <b style="background:#ffd9d3">hachurada em vermelho</b> = sobra que vai ser cortada. A hachura e a linha tracejada aparecem só aqui, não no PDF.';
        done.unshift(legend);
      }
      body.replaceChildren(...done);
      tabs.innerHTML =
        groups.length > 1
          ? groups.map((g) => '<button type="button" class="tab" role="tab" data-tab="' + g.key + '">' + g.label + ' · ' + g.n + ' ' + plural(g.n, 'folha', 'folhas') + '</button>').join('')
          : '';
      showTab();
      body.classList.remove('busy');
    } catch (e) {
      console.error(e);
      body.innerHTML = '<p class="empty">Não consegui montar a prévia: ' + esc(e.message || e) + '</p>';
      body.classList.remove('busy');
    }
  }

  function showTab() {
    $$('#preview-body .sheets').forEach((el) => (el.hidden = el.dataset.group !== state.tab));
    $$('#tabs .tab').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.tab === state.tab)));
  }

  // --------------------------------------------------------------- exportação

  function download(bytes, filename) {
    const url = URL.createObjectURL(new Blob([bytes], { type: 'application/pdf' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  }

  async function doExport(kind, btn) {
    const d = derive();
    const s = d.s;
    const base = slug(state.title || 'meu-zine');
    const size = slug(sheetName(s));
    const old = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = 'Gerando…<small>pode levar alguns segundos com scans grandes</small>';
    try {
      const titleTxt = state.title || 'Meu zine';
      let bytes;
      let name;
      if (kind === 'miolo') {
        bytes = await Builder.buildPdf(d.plan.interior.map((x) => Sources.toBuilderItem(x, 'final')), {
          settings: s, offsets: d.offsets, nestStart: d.coverSheets, title: titleTxt + (s.mode === 'together' ? '' : ' — miolo'),
        });
        name = base + (s.mode === 'together' ? '' : '-miolo') + '-' + size + '.pdf';
      } else if (kind === 'capa') {
        bytes = await Builder.buildPdf(d.plan.cover.map((x) => Sources.toBuilderItem(x, 'final')), {
          settings: s, offsets: d.offsets, nestStart: 0, title: titleTxt + ' — capa',
        });
        name = base + '-capa-' + size + '.pdf';
      } else {
        bytes = await Builder.buildTestSheet(s, titleTxt);
        name = base + '-folha-de-teste-' + size + '.pdf';
      }
      download(bytes, name);
      toast('Pronto: ' + name);
    } catch (e) {
      console.error(e);
      toast('Erro ao gerar o PDF: ' + (e.message || e), true);
    } finally {
      btn.disabled = false;
      btn.innerHTML = old;
    }
  }

  // ------------------------------------------------------------------- eventos

  function bind() {
    // configurações
    document.addEventListener('input', onInput);
    document.addEventListener('change', (e) => {
      if (e.target.closest('[data-k]')) syncControls();
    });
    $('#title').addEventListener('input', (e) => {
      state.title = e.target.value;
      save();
    });

    // botões do cabeçalho do bloco de páginas
    $('#btn-add').onclick = () => pickFiles('pages');
    $('#btn-blank').onclick = () => {
      state.pages.push(Sources.blank());
      changed();
    };
    $('#btn-clear').onclick = () => {
      if (!state.pages.length && !Object.values(state.slots).some(Boolean)) return;
      if (!confirm('Remover todas as páginas e a capa do projeto?')) return;
      state.pages = [];
      state.slots = { front: null, insideFront: null, insideBack: null, back: null };
      changed();
    };
    $('#btn-sample').onclick = async () => {
      if ((state.pages.length || state.slots.front) && !confirm('Substituir o projeto atual pelo exemplo?')) return;
      toast('Gerando páginas de exemplo…', false, 20000);
      const sm = await Sources.makeSample(8);
      state.pages = sm.pages;
      state.slots = { front: sm.cover, insideFront: null, insideBack: null, back: sm.back };
      changed();
      toast('Exemplo carregado: 8 páginas + capa e contracapa.');
    };
    $('#btn-help').onclick = () => $('#help').showModal();
    $('#btn-reset').onclick = () => {
      state.settings = clone(Core.DEFAULT_SETTINGS);
      onSettings();
    };
    $('#btn-cal').onclick = () => {
      const d = derive();
      const f = Core.calibrateFactor(parseFloat($('#cal-n').value), d.tIn, parseFloat($('#cal-mm').value));
      if (f == null) return toast('Informe o número de folhas (2 ou mais) e a sobra medida.', true);
      state.settings.creepFactor = Math.min(10, Math.max(0.2, f));
      onSettings();
      toast('Creep calibrado: ' + fmt(state.settings.creepFactor, 2) + '×');
    };

    // arquivos
    $('#file-input').addEventListener('change', (e) => ingest(e.target.files, fileTarget));
    const drop = $('#drop');
    drop.addEventListener('click', () => pickFiles('pages'));
    drop.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        pickFiles('pages');
      }
    });

    // bandeja: ações dos cartões
    $('#tray').addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-act]');
      const li = e.target.closest('.pg[data-id]');
      if (!btn || !li) return;
      const i = findPage(li.dataset.id);
      if (i < 0) return;
      const act = btn.dataset.act;
      if (act === 'left' && i > 0) [state.pages[i - 1], state.pages[i]] = [state.pages[i], state.pages[i - 1]];
      else if (act === 'right' && i < state.pages.length - 1) [state.pages[i + 1], state.pages[i]] = [state.pages[i], state.pages[i + 1]];
      else if (act === 'rm') state.pages.splice(i, 1);
      else if (act === 'dup') state.pages.splice(i + 1, 0, Sources.clone(state.pages[i]));
      else if (act === 'rot') return rotateSource(state.pages[i]);
      changed();
    });

    // bandeja: reordenar arrastando
    let dragId = null;
    const tray = $('#tray');
    const clearMarks = () => $$('.drop-before,.drop-after,.dragging', tray).forEach((el) => el.classList.remove('drop-before', 'drop-after', 'dragging'));
    function target(e) {
      const cards = $$('.pg[data-id]', tray);
      let best = null;
      let bd = Infinity;
      cards.forEach((c, i) => {
        const r = c.getBoundingClientRect();
        const dx = e.clientX - (r.left + r.width / 2);
        const dy = e.clientY - (r.top + r.height / 2);
        const dist = dx * dx + dy * dy;
        if (dist < bd) {
          bd = dist;
          best = { el: c, index: i, after: dx > 0 };
        }
      });
      return best;
    }
    tray.addEventListener('dragstart', (e) => {
      const li = e.target.closest('.pg[data-id]');
      if (!li) return;
      dragId = li.dataset.id;
      li.classList.add('dragging');
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', dragId);
    });
    tray.addEventListener('dragover', (e) => {
      if (!dragId) return;
      e.preventDefault();
      const t = target(e);
      $$('.drop-before,.drop-after', tray).forEach((el) => el.classList.remove('drop-before', 'drop-after'));
      if (t) t.el.classList.add(t.after ? 'drop-after' : 'drop-before');
    });
    tray.addEventListener('drop', (e) => {
      if (!dragId) return;
      e.preventDefault();
      const t = target(e);
      const id = dragId;
      dragId = null;
      clearMarks();
      if (t) movePage(id, t.index + (t.after ? 1 : 0));
    });
    tray.addEventListener('dragend', () => {
      dragId = null;
      clearMarks();
    });

    // slots da capa
    const slots = $('#slots');
    slots.addEventListener('click', (e) => {
      const slot = e.target.closest('.slot');
      if (!slot) return;
      const key = slot.dataset.slot;
      const btn = e.target.closest('button[data-act]');
      if (btn) {
        if (btn.dataset.act === 'rm') {
          state.slots[key] = null;
          changed();
        } else rotateSource(state.slots[key]);
      } else if (!slot.classList.contains('filled')) pickFiles(key);
    });
    slots.addEventListener('keydown', (e) => {
      const slot = e.target.closest('.slot:not(.filled)');
      if (slot && (e.key === 'Enter' || e.key === ' ')) {
        e.preventDefault();
        pickFiles(slot.dataset.slot);
      }
    });

    // soltar arquivos em qualquer lugar da página
    let depth = 0;
    const hasFiles = (e) => e.dataTransfer && Array.from(e.dataTransfer.types || []).includes('Files');
    document.addEventListener('dragenter', (e) => {
      if (!hasFiles(e)) return;
      depth++;
      document.body.classList.add('dragging-files');
    });
    document.addEventListener('dragleave', (e) => {
      if (!hasFiles(e)) return;
      depth = Math.max(0, depth - 1);
      if (!depth) document.body.classList.remove('dragging-files');
    });
    document.addEventListener('dragover', (e) => {
      if (hasFiles(e)) e.preventDefault();
    });
    document.addEventListener('drop', (e) => {
      if (!hasFiles(e)) return;
      e.preventDefault();
      depth = 0;
      document.body.classList.remove('dragging-files');
      const slot = e.target.closest && e.target.closest('.slot');
      ingest(e.dataTransfer.files, slot ? slot.dataset.slot : 'pages');
    });

    // prévia e exportação
    $('#tabs').addEventListener('click', (e) => {
      const b = e.target.closest('.tab');
      if (!b) return;
      state.tab = b.dataset.tab;
      showTab();
    });
    $('#export-btns').addEventListener('click', (e) => {
      const b = e.target.closest('button[data-export]');
      if (b) doExport(b.dataset.export, b);
    });
  }

  // ------------------------------------------------------------------- início

  function init() {
    load();
    $('#f-sheet').innerHTML = Object.entries(Core.SHEETS).map(([k, v]) => '<option value="' + k + '">' + v.label + '</option>').join('');
    const papers = Core.PAPERS.map((p) => '<option value="' + p.id + '">' + p.label + (p.id === 'custom' ? '' : ' · ~' + fmt(p.mm * 1000, 0) + ' µm') + '</option>').join('');
    $('#f-paper-in').innerHTML = papers;
    $('#f-paper-cv').innerHTML = papers;
    bind();
    syncControls();
    renderAll();
    schedulePreview();
    if ('serviceWorker' in navigator && /^https?:$/.test(location.protocol)) {
      navigator.serviceWorker.register('sw.js').catch(() => {});
    }
    window.__zine = { state, derive, ingest }; // ajuda em testes automatizados
  }

  init();
})();
