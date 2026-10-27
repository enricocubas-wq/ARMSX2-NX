/*
 * sources.js — entrada de arquivos no navegador.
 *
 * Cada "fonte" é uma página do projeto:
 *   { id, contentId, kind: 'image'|'pdf'|'blank', label, rotation, thumbUrl, previewBytes, ... }
 * Guardamos uma versão pequena (preview, ~1000 px) para a pré-visualização ficar rápida,
 * e a referência ao arquivo original para a exportação final, que usa os bytes originais
 * (JPEG/PNG sem perda; PDF como vetor).
 */
(function (root) {
  'use strict';
  const Exif = root.ZineExif;

  const PREVIEW_EDGE = 1000;
  const THUMB_EDGE = 300;
  const MAX_FINAL_EDGE = 6000; // só quando é preciso recodificar (foto girada, WebP, etc.)
const MAX_FINAL_PIXELS = 16e6; // Safari/iOS não desenha canvas maiores que ~16 MP
  let seq = 0;
  const uid = (p) => p + ++seq;

  const IMAGE_RE = /\.(jpe?g|png|webp|gif|bmp|avif|heic|heif|tiff?)$/i;
  const PDF_RE = /\.pdf$/i;

  function isPdf(file) {
    return file.type === 'application/pdf' || PDF_RE.test(file.name);
  }
  function isImage(file) {
    return /^image\//.test(file.type) || IMAGE_RE.test(file.name);
  }

  function canvasToBlob(canvas, type, q) {
    return new Promise((resolve, reject) =>
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Não consegui codificar a imagem'))), type, q)
    );
  }

  function newCanvas(w, h) {
    const c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(w));
    c.height = Math.max(1, Math.round(h));
    const ctx = c.getContext('2d');
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, c.width, c.height);
    return c;
  }

  function scaledCanvas(srcCanvas, maxEdge) {
    const s = Math.min(1, maxEdge / Math.max(srcCanvas.width, srcCanvas.height));
    const c = newCanvas(srcCanvas.width * s, srcCanvas.height * s);
    c.getContext('2d').drawImage(srcCanvas, 0, 0, c.width, c.height);
    return c;
  }

  /** A partir de um canvas "grande", gera preview (bytes JPEG) e miniatura (URL). */
  async function makePreviews(canvas) {
    const pv = scaledCanvas(canvas, PREVIEW_EDGE);
    const previewBlob = await canvasToBlob(pv, 'image/jpeg', 0.86);
    const th = scaledCanvas(pv, THUMB_EDGE);
    const thumbBlob = await canvasToBlob(th, 'image/jpeg', 0.8);
    return {
      previewBytes: new Uint8Array(await previewBlob.arrayBuffer()),
      thumbUrl: URL.createObjectURL(thumbBlob),
    };
  }

  async function decodeBitmap(file) {
    try {
      return await createImageBitmap(file, { imageOrientation: 'from-image' });
    } catch (e) {
      try {
        return await createImageBitmap(file); // navegadores sem a opção
      } catch (e2) {
        throw new Error('Formato de imagem não suportado neste navegador: ' + file.name);
      }
    }
  }

  async function fromImageFile(file) {
    const bmp = await decodeBitmap(file);
    const w = bmp.width;
    const h = bmp.height;
    try {
      // A prévia só precisa de ~1000 px: nunca desenhamos a imagem inteira num canvas
      // (acima de ~16 MP o Safari/iOS devolve canvas em branco).
      const k = Math.min(1, PREVIEW_EDGE / Math.max(w, h));
      const pv = newCanvas(w * k, h * k);
      pv.getContext('2d').drawImage(bmp, 0, 0, pv.width, pv.height);
      const { previewBytes, thumbUrl } = await makePreviews(pv);

      // Decide se dá para embutir o arquivo original ou se precisa recodificar.
      let blob = file;
      let mime = file.type;
      let needsReencode = false;
      if (mime === 'image/jpeg') {
        const head = new Uint8Array(await file.slice(0, 131072).arrayBuffer());
        needsReencode = Exif.jpegOrientation(head) !== 1; // pdf-lib ignora a etiqueta de orientação
      } else if (mime !== 'image/png') {
        needsReencode = true; // WebP, GIF, AVIF, HEIC…: o PDF só aceita JPEG/PNG
      }
      if (needsReencode) {
        const s = Math.min(1, MAX_FINAL_EDGE / Math.max(w, h), Math.sqrt(MAX_FINAL_PIXELS / (w * h)));
        const full = newCanvas(w * s, h * s);
        full.getContext('2d').drawImage(bmp, 0, 0, full.width, full.height);
        blob = await canvasToBlob(full, 'image/jpeg', 0.93);
        mime = 'image/jpeg';
      }

      const id = uid('s');
      return {
        id,
        contentId: id,
        kind: 'image',
        label: file.name,
        name: file.name,
        mime,
        blob,
        width: w,
        height: h,
        rotation: 0,
        previewBytes,
        thumbUrl,
      };
    } finally {
      if (bmp.close) bmp.close();
    }
  }

  /**
   * Abre um PDF no pdf.js com um Worker exclusivo (fechado em close()). Um worker por documento
   * evita que um destroy() em andamento atrapalhe o próximo getDocument. No arquivo único o worker
   * vem de um Blob (window.__ZINE_PDF_WORKER_URL), o que também funciona por file://.
   * Devolve { pdf, close() }; quem abre é responsável por chamar (e aguardar) close().
   */
  async function openPdf(bytes) {
    const lib = root.pdfjsLib;
    if (!lib) throw new Error('Leitor de PDF não carregou. Recarregue a página.');
    let port = null;
    let worker = null;
    if (root.__ZINE_PDF_WORKER_URL) {
      try {
        port = new Worker(root.__ZINE_PDF_WORKER_URL);
        worker = new lib.PDFWorker({ port });
      } catch (e) {
        port = worker = null; // sem Worker real: o pdf.js usa o fake worker
      }
    }
    const release = async (pdf) => {
      try {
        if (pdf) await pdf.destroy();
      } finally {
        if (worker) worker.destroy();
        if (port) port.terminate();
      }
    };
    let pdf;
    try {
      pdf = await lib.getDocument(worker ? { data: bytes, worker } : { data: bytes }).promise;
    } catch (e) {
      await release(null);
      throw e;
    }
    return { pdf, close: () => release(pdf) };
  }

  async function fromPdfFile(file, onPage) {
    const data = new Uint8Array(await file.arrayBuffer());
    let handle;
    try {
      handle = await openPdf(data.slice());
    } catch (e) {
      if (e && e.name === 'PasswordException') throw new Error(file.name + ' está protegido por senha.');
      if (e && /Leitor de PDF/.test(e.message)) throw e;
      throw new Error('Não consegui abrir ' + file.name + ' (PDF inválido?).');
    }
    const pdf = handle.pdf;
    const fileId = uid('f');
    const out = [];
    try {
      for (let i = 1; i <= pdf.numPages; i++) {
        const page = await pdf.getPage(i);
        const v1 = page.getViewport({ scale: 1 }); // já considera /Rotate
        const scale = PREVIEW_EDGE / Math.max(v1.width, v1.height);
        const vp = page.getViewport({ scale });
        const canvas = newCanvas(vp.width, vp.height);
        await page.render({ canvasContext: canvas.getContext('2d'), viewport: vp }).promise;
        const { previewBytes, thumbUrl } = await makePreviews(canvas);
        const id = uid('s');
        out.push({
          id,
          contentId: id,
          kind: 'pdf',
          label: pdf.numPages > 1 ? file.name + ' · p.' + i : file.name,
          name: file.name,
          file,
          fileId,
          pageIndex: i - 1,
          width: v1.width,
          height: v1.height,
          rotation: 0,
          previewBytes,
          thumbUrl,
        });
        page.cleanup();
        if (onPage) onPage(i, pdf.numPages);
      }
    } finally {
      await handle.close();
    }
    return out;
  }

  /**
   * Lê uma lista de arquivos. Imagens e PDFs entram em ordem "natural" de nome
   * (pagina2 antes de pagina10); as páginas de um PDF ficam na ordem do PDF.
   * Devolve { sources, errors }.
   */
  async function addFiles(fileList, onProgress) {
    const files = Array.from(fileList).sort((a, b) => root.ZineCore.naturalCompare(a.name, b.name));
    const sources = [];
    const errors = [];
    let n = 0;
    for (const f of files) {
      try {
        if (isPdf(f)) sources.push(...(await fromPdfFile(f)));
        else if (isImage(f)) sources.push(await fromImageFile(f));
        else errors.push(f.name + ': tipo de arquivo não suportado (use JPG, PNG ou PDF)');
      } catch (e) {
        errors.push(e.message || String(e));
      }
      if (onProgress) onProgress(++n, files.length);
    }
    return { sources, errors };
  }

  function blank() {
    const id = uid('b');
    return { id, contentId: id, kind: 'blank', label: 'em branco', auto: false, rotation: 0 };
  }

  function clone(src) {
    // mesma imagem em outro lugar: novo id de interface, mesmo contentId (embutida uma vez só no PDF)
    return Object.assign({}, src, { id: uid(src.kind === 'blank' ? 'b' : 's') });
  }

  /** Adapta uma fonte ao formato que o pdfbuilder entende. */
  function toBuilderItem(src, quality) {
    if (!src || src.kind === 'blank') return { kind: 'blank', id: (src && src.id) || uid('b'), auto: !!(src && src.auto) };
    const rotation = src.rotation || 0;
    if (quality === 'preview') {
      return { kind: 'image', id: src.contentId + ':preview', mime: 'image/jpeg', getBytes: async () => src.previewBytes, rotation };
    }
    if (src.kind === 'image') {
      return {
        kind: 'image',
        id: src.contentId,
        mime: src.mime,
        getBytes: async () => new Uint8Array(await src.blob.arrayBuffer()),
        rotation,
      };
    }
    return {
      kind: 'pdf',
      id: src.contentId,
      fileId: src.fileId,
      pageIndex: src.pageIndex,
      getBytes: async () => new Uint8Array(await src.file.arrayBuffer()),
      rotation,
    };
  }

  // ------------------------------------------------------------ páginas de exemplo

  /** Gera páginas A5 de exemplo com cara de zine (para testar sem ter scans à mão). */
  async function makeSample(count) {
    const W = 888;
    const H = 1260; // 148 × 210 mm a 6 px/mm
    const papers = ['#fff6d6', '#ffe0e6', '#dff3ff', '#e6ffd9', '#f1e6ff', '#fff'];
    const out = [];
    const total = count + 2; // + capa e contracapa
    for (let i = 0; i < total; i++) {
      const isCover = i === 0;
      const isBack = i === total - 1;
      const c = document.createElement('canvas');
      c.width = W;
      c.height = H;
      const g = c.getContext('2d');
      g.fillStyle = isCover ? '#ff4d2e' : isBack ? '#16130f' : papers[i % papers.length];
      g.fillRect(0, 0, W, H);
      const ink = isBack ? '#f4efe4' : '#16130f';
      // moldura rente à borda: mostra o que o refile come
      g.lineWidth = 14;
      g.strokeStyle = ink;
      g.strokeRect(7, 7, W - 14, H - 14);
      // meio-tom
      g.fillStyle = isCover ? '#16130f' : isBack ? '#ff4d2e' : 'rgba(22,19,15,.18)';
      for (let y = 0; y < 9; y++) for (let x = 0; x < 6; x++) {
        const r = 9 + ((x * 7 + y * 5 + i * 3) % 14);
        g.beginPath();
        g.arc(150 + x * 118 + (y % 2) * 40, H - 360 + y * 30, r, 0, 7);
        g.fill();
      }
      g.fillStyle = ink;
      g.textAlign = 'center';
      if (isCover) {
        g.font = '900 150px "Arial Black", Arial, sans-serif';
        g.fillText('ZINE', W / 2, 420);
        g.fillText('EXEMPLO', W / 2, 570);
        g.font = '36px ui-monospace, Menlo, monospace';
        g.fillText('feito com o Zine Maker', W / 2, 660);
      } else if (isBack) {
        g.font = '900 90px "Arial Black", Arial, sans-serif';
        g.fillText('FIM', W / 2, 420);
        g.font = '34px ui-monospace, Menlo, monospace';
        g.fillText('xerox, grampo e coragem', W / 2, 500);
      } else {
        g.font = '900 360px "Arial Black", Arial, sans-serif';
        g.fillText(String(i), W / 2, 560);
        g.font = '36px ui-monospace, Menlo, monospace';
        g.fillText('página ' + i + ' do miolo', W / 2, 650);
        // linhas de texto "lorem"
        g.fillStyle = 'rgba(22,19,15,.55)';
        for (let l = 0; l < 6; l++) g.fillRect(110, 720 + l * 34, W - 220 - ((l * 97 + i * 53) % 220), 12);
      }
      const blob = await canvasToBlob(c, 'image/jpeg', 0.92);
      const { previewBytes, thumbUrl } = await makePreviews(c);
      const id = uid('s');
      out.push({
        id,
        contentId: id,
        kind: 'image',
        label: isCover ? 'exemplo-capa.jpg' : isBack ? 'exemplo-contracapa.jpg' : 'exemplo-' + String(i).padStart(2, '0') + '.jpg',
        name: 'exemplo-' + i + '.jpg',
        mime: 'image/jpeg',
        blob,
        width: W,
        height: H,
        rotation: 0,
        previewBytes,
        thumbUrl,
      });
    }
    return { cover: out[0], back: out[total - 1], pages: out.slice(1, total - 1) };
  }

  root.ZineSources = { addFiles, openPdf, blank, clone, toBuilderItem, makeSample, isPdf, isImage };
})(typeof self !== 'undefined' ? self : this);
