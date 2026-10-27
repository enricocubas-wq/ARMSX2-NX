/*
 * exif.js — lê só a orientação EXIF (1–8) de um JPEG.
 *
 * Fotos de celular guardam os pixels "deitados" e uma etiqueta de orientação.
 * O pdf-lib embute os bytes crus ignorando essa etiqueta; por isso, quando a
 * orientação não é 1, o app recodifica a imagem já girada (ver sources.js).
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.ZineExif = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  function jpegOrientation(u8) {
    if (!u8 || u8.length < 4 || u8[0] !== 0xff || u8[1] !== 0xd8) return 1;
    let off = 2;
    while (off + 4 <= u8.length) {
      if (u8[off] !== 0xff) return 1;
      const marker = u8[off + 1];
      if (marker === 0xd9 || marker === 0xda) return 1; // fim da imagem / início dos dados
      if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd8)) {
        off += 2; // marcadores sem tamanho
        continue;
      }
      const len = (u8[off + 2] << 8) | u8[off + 3];
      const isExif =
        marker === 0xe1 &&
        len >= 16 &&
        u8[off + 4] === 0x45 && // E
        u8[off + 5] === 0x78 && // x
        u8[off + 6] === 0x69 && // i
        u8[off + 7] === 0x66 && // f
        u8[off + 8] === 0 &&
        u8[off + 9] === 0;
      if (isExif) return readTiff(u8, off + 10, Math.min(u8.length, off + 2 + len));
      off += 2 + len;
    }
    return 1;
  }

  function readTiff(u8, t, end) {
    const le = u8[t] === 0x49 && u8[t + 1] === 0x49; // "II" little-endian, "MM" big-endian
    const r16 = (p) => (le ? u8[p] | (u8[p + 1] << 8) : (u8[p] << 8) | u8[p + 1]);
    const r32 = (p) =>
      (le
        ? u8[p] | (u8[p + 1] << 8) | (u8[p + 2] << 16) | (u8[p + 3] << 24)
        : (u8[p] << 24) | (u8[p + 1] << 16) | (u8[p + 2] << 8) | u8[p + 3]) >>> 0;
    if (t + 8 > end || r16(t + 2) !== 42) return 1;
    const ifd = t + r32(t + 4);
    if (ifd + 2 > end) return 1;
    const n = r16(ifd);
    for (let i = 0; i < n; i++) {
      const e = ifd + 2 + i * 12;
      if (e + 12 > end) return 1;
      if (r16(e) === 0x0112) {
        const v = r16(e + 8);
        return v >= 1 && v <= 8 ? v : 1;
      }
    }
    return 1;
  }

  return { jpegOrientation };
});
