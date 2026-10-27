/*
 * pdfbuilder.js — transforma um plano de imposição (core.js) em PDF pronto para imprimir.
 *
 * Itens aceitos (ver sources.js):
 *   { kind: 'blank' }
 *   { kind: 'image', id, mime: 'image/jpeg'|'image/png', getBytes(): Promise<Uint8Array>, rotation }
 *   { kind: 'pdf',   id, fileId, pageIndex, getBytes(): Promise<Uint8Array>, rotation }
 *
 * Páginas de PDF são embutidas como objetos vetoriais (sem rasterizar);
 * JPEG/PNG entram com os bytes originais.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require('pdf-lib'), require('./core.js'));
  else root.ZineBuilder = factory(root.PDFLib, root.ZineCore);
})(typeof self !== 'undefined' ? self : this, function (PDFLib, Core) {
  'use strict';

  const {
    PDFDocument,
    StandardFonts,
    rgb,
    grayscale,
    degrees,
    pushGraphicsState,
    popGraphicsState,
    concatTransformationMatrix,
    rectangle,
    clip,
    endPath,
  } = PDFLib;
  const MM = Core.MM;

  // --------------------------------------------------------------- embutir itens

  /**
   * O pdf-lib lê `bytes.buffer` inteiro; um Uint8Array/Buffer que é só uma "fatia" de um
   * buffer maior (ex.: pool do Node) quebra a leitura. Copia só quando é fatia.
   */
  async function bytesOf(item) {
    const b = await item.getBytes();
    return b.byteOffset === 0 && b.byteLength === b.buffer.byteLength ? b : new Uint8Array(b);
  }

  /** Embute um item no documento de saída, com cache por item. */
  function makeEmbedder(doc) {
    const cache = new Map();
    const srcDocs = new Map();

    async function loadSrcDoc(item) {
      if (!srcDocs.has(item.fileId)) {
        srcDocs.set(
          item.fileId,
          bytesOf(item).then((b) => PDFDocument.load(b, { ignoreEncryption: true, updateMetadata: false }))
        );
      }
      return srcDocs.get(item.fileId);
    }

    async function embed(item) {
      if (item.kind === 'image') {
        const bytes = await bytesOf(item);
        const img = item.mime === 'image/png' ? await doc.embedPng(bytes) : await doc.embedJpg(bytes);
        return { obj: img, isPage: false, w: img.width, h: img.height, baseRot: 0 };
      }
      if (item.kind === 'pdf') {
        const src = await loadSrcDoc(item);
        const srcPage = src.getPage(item.pageIndex);
        // página sem conteúdo (comum em PDFs exportados): o pdf-lib não consegue embutir
        if (!srcPage.node.Contents()) return { blank: true };
        const ep = await doc.embedPage(srcPage);
        // o pdf-lib não aplica /Rotate ao embutir: somamos à rotação do usuário
        const baseRot = ((srcPage.getRotation().angle % 360) + 360) % 360;
        return { obj: ep, isPage: true, w: ep.width, h: ep.height, baseRot };
      }
      throw new Error('Tipo de item desconhecido: ' + item.kind);
    }

    return function get(item) {
      const key = item.id;
      if (!cache.has(key)) cache.set(key, embed(item));
      return cache.get(key);
    };
  }

  // ----------------------------------------------------------------- desenho

  function drawContent(page, emb, rectMM, rotation, opts) {
    const rect = { x: rectMM.x * MM, y: rectMM.y * MM, w: rectMM.w * MM, h: rectMM.h * MM };
    const draw = () => {
      const p = Core.rotatedPlacement(rect, rotation);
      const o = { x: p.x, y: p.y, width: p.width, height: p.height, rotate: degrees(p.rotateDeg) };
      if (emb.isPage) page.drawPage(emb.obj, o);
      else page.drawImage(emb.obj, o);
    };

    // 3×3 de reflexos em volta da página: preenche a sobra com a própria borda
    // (sem isso a sobra fica branca, o que é ok para scans de fundo claro).
    const range = opts.fill === 'mirror' ? [-1, 0, 1] : [0];
    for (const dy of range) {
      for (const dx of range) {
        page.pushOperators(pushGraphicsState());
        if (dx || dy) {
          const ax = dx < 0 ? rect.x : rect.x + rect.w;
          const ay = dy < 0 ? rect.y : rect.y + rect.h;
          page.pushOperators(
            concatTransformationMatrix(dx ? -1 : 1, 0, 0, dy ? -1 : 1, dx ? 2 * ax : 0, dy ? 2 * ay : 0)
          );
        }
        draw();
        page.pushOperators(popGraphicsState());
      }
    }
  }

  /** Quadros a 1…8 mm da borda: mostram até onde a impressora realmente imprime. */
  function drawPrinterFrames(page, W, H, font) {
    for (let mm = 1; mm <= 8; mm++) {
      const inset = mm * MM;
      page.drawRectangle({
        x: inset,
        y: inset,
        width: W * MM - 2 * inset,
        height: H * MM - 2 * inset,
        borderColor: grayscale(0.35),
        borderWidth: 0.3,
      });
      page.drawText(String(mm), { x: inset + 1.2, y: H * MM - inset - 5.5, size: 5, font, color: grayscale(0.35) });
    }
  }

  async function drawFace(page, face, getEmb, geom, settings, opts) {
    const W = geom.sheetW * MM;
    const H = geom.sheetH * MM;
    const flip = opts.flip180;
    if (flip) page.pushOperators(pushGraphicsState(), concatTransformationMatrix(-1, 0, 0, -1, W, H));

    for (const slot of face.slots) {
      const item = slot.item;
      if (!item || item.kind === 'blank') continue;
      const emb = await getEmb(item);
      if (emb.blank) continue;
      const rot = ((item.rotation || 0) + emb.baseRot) % 360;
      const rect = Core.fitContent(emb.w, emb.h, rot, slot.geom.art, settings.fit);
      const [hx0, hx1] = slot.geom.half;

      page.pushOperators(pushGraphicsState(), rectangle(hx0 * MM, 0, (hx1 - hx0) * MM, H), clip(), endPath());
      drawContent(page, emb, rect, rot, { fill: settings.fill });
      page.pushOperators(popGraphicsState());
    }

    if (geom.trimmed && settings.trim.marks) {
      for (const m of Core.cropMarks(geom)) {
        page.drawLine({
          start: { x: m.x * MM, y: m.y0 * MM },
          end: { x: m.x * MM, y: m.y1 * MM },
          thickness: 0.3,
          color: grayscale(0),
        });
      }
    }
    if (flip) page.pushOperators(popGraphicsState());
  }

  // ------------------------------------------------------------------ API

  /**
   * Gera o PDF imposto.
   *   items:  sequência de páginas (múltiplo de 4)
   *   opts:   { settings, offsets, nestStart, title, printerFrames, onProgress }
   */
  async function buildPdf(items, opts) {
    const settings = opts.settings;
    const { w: sheetW, h: sheetH } = Core.sheetSize(settings);
    const plan = Core.planSheets(items, {
      sheetW,
      sheetH,
      trim: settings.trim,
      rtl: settings.rtl,
      offsets: opts.offsets || [],
      nestStart: opts.nestStart || 0,
    });

    const doc = await PDFDocument.create();
    doc.setTitle(opts.title || 'Zine');
    doc.setCreator('Zine Maker');
    doc.setProducer('Zine Maker');
    const getEmb = makeEmbedder(doc);
    const frameFont = opts.printerFrames ? await doc.embedFont(StandardFonts.Helvetica) : null;

    for (let i = 0; i < plan.length; i++) {
      const sheet = plan[i];
      for (const face of sheet.faces) {
        const page = doc.addPage([sheetW * MM, sheetH * MM]);
        const flip180 = settings.duplex === 'long' && face.name === 'verso';
        await drawFace(page, face, getEmb, sheet.geom, settings, { flip180 });
        if (opts.printerFrames) drawPrinterFrames(page, sheetW, sheetH, frameFont);
      }
      if (opts.onProgress) opts.onProgress(i + 1, plan.length);
    }
    return doc.save();
  }

  /**
   * Folha de teste: 4 páginas numeradas com seta "TOPO" e régua de milímetros na borda
   * de refile, impostas com as mesmas configurações do projeto. Serve para conferir
   * (1) o lado em que a impressora vira a folha, (2) a margem não imprimível e
   * (3) quanto sobra no refile.
   */
  async function buildTestSheet(settings, title) {
    const { w: sheetW, h: sheetH } = Core.sheetSize(settings);
    const g = Core.sheetGeometry(sheetW, sheetH, settings.trim, 0);
    const aw = g.left.art.x1 - g.left.art.x0;
    const ah = g.left.art.y1 - g.left.art.y0;

    const src = await PDFDocument.create();
    const bold = await src.embedFont(StandardFonts.HelveticaBold);
    const reg = await src.embedFont(StandardFonts.Helvetica);
    for (let p = 1; p <= 4; p++) {
      const page = src.addPage([aw * MM, ah * MM]);
      const W = aw * MM;
      const H = ah * MM;
      const ink = grayscale(0.1);
      page.drawRectangle({ x: 0.6, y: 0.6, width: W - 1.2, height: H - 1.2, borderColor: ink, borderWidth: 1 });
      const num = String(p);
      const size = H * 0.34;
      page.drawText(num, {
        x: W / 2 - bold.widthOfTextAtSize(num, size) / 2,
        y: H / 2 - size * 0.36,
        size,
        font: bold,
        color: ink,
      });
      // seta "TOPO"
      const cx = W / 2;
      const top = H - 14 * MM;
      page.drawLine({ start: { x: cx, y: top - 14 * MM }, end: { x: cx, y: top }, thickness: 1.5, color: ink });
      page.drawLine({ start: { x: cx, y: top }, end: { x: cx - 3 * MM, y: top - 4 * MM }, thickness: 1.5, color: ink });
      page.drawLine({ start: { x: cx, y: top }, end: { x: cx + 3 * MM, y: top - 4 * MM }, thickness: 1.5, color: ink });
      page.drawText('TOPO', { x: cx - 8 * MM / 2 - 3, y: top + 3, size: 9, font: bold, color: ink });
      page.drawText('página ' + p + ' de 4', {
        x: W / 2 - reg.widthOfTextAtSize('página ' + p + ' de 4', 8) / 2,
        y: 20 * MM,
        size: 8,
        font: reg,
        color: ink,
      });

      // régua em mm a partir da borda de refile (a que fica do lado de fora do livro)
      const foreEdgeRight = (p % 2 === 1) !== !!settings.rtl;
      const rulerY = H - 5 * MM;
      for (let mm = 0; mm <= 25; mm++) {
        const x = foreEdgeRight ? W - mm * MM : mm * MM;
        const len = mm % 5 === 0 ? 3.5 * MM : 1.8 * MM;
        page.drawLine({ start: { x, y: rulerY }, end: { x, y: rulerY - len }, thickness: 0.4, color: ink });
        if (mm % 5 === 0 && mm > 0) {
          const t = String(mm);
          page.drawText(t, {
            x: x - reg.widthOfTextAtSize(t, 6) / 2,
            y: rulerY - 3.5 * MM - 7,
            size: 6,
            font: reg,
            color: ink,
          });
        }
      }
    }
    const bytes = await src.save();
    const items = [1, 2, 3, 4].map((n) => ({
      kind: 'pdf',
      id: 'teste' + n,
      fileId: 'teste',
      pageIndex: n - 1,
      getBytes: async () => bytes,
      rotation: 0,
    }));
    return buildPdf(items, {
      settings,
      offsets: [0],
      nestStart: 0,
      title: (title || 'Zine') + ' — folha de teste',
      printerFrames: true,
    });
  }

  return { buildPdf, buildTestSheet };
});
