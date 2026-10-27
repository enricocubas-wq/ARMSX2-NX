'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');
const { jpegOrientation } = require('../js/exif.js');

const haveConvert = spawnSync('convert', ['-version']).status === 0;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'zine-exif-'));

/** Monta um segmento APP1/EXIF com a orientação dada e injeta logo após o SOI de um JPEG real. */
function exifSegment(orientation, littleEndian) {
  const w16 = (v) => (littleEndian ? [v & 255, v >> 8] : [v >> 8, v & 255]);
  const w32 = (v) => (littleEndian ? [v & 255, (v >> 8) & 255, (v >> 16) & 255, v >>> 24] : [v >>> 24, (v >> 16) & 255, (v >> 8) & 255, v & 255]);
  const tiff = [
    ...(littleEndian ? [0x49, 0x49] : [0x4d, 0x4d]), ...w16(42), ...w32(8),
    ...w16(2), // duas entradas na IFD0: uma qualquer (ImageWidth) e a orientação
    ...w16(0x0100), ...w16(3), ...w32(1), ...w16(8), 0, 0,
    ...w16(0x0112), ...w16(3), ...w32(1), ...w16(orientation), 0, 0,
    ...w32(0),
  ];
  const body = [0x45, 0x78, 0x69, 0x66, 0, 0, ...tiff];
  const len = body.length + 2;
  return Uint8Array.from([0xff, 0xe1, len >> 8, len & 255, ...body]);
}

function jpegWith(orientation, littleEndian) {
  const f = path.join(tmp, 'base.jpg');
  execFileSync('convert', ['-size', '8x4', 'xc:red', '-strip', f]);
  const j = new Uint8Array(fs.readFileSync(f));
  if (!orientation) return j;
  const seg = exifSegment(orientation, littleEndian);
  const out = new Uint8Array(j.length + seg.length);
  out.set(j.subarray(0, 2), 0); // SOI
  out.set(seg, 2);
  out.set(j.subarray(2), 2 + seg.length);
  return out;
}

test('lê as 8 orientações EXIF, em little e big endian', { skip: !haveConvert }, () => {
  for (const le of [true, false]) {
    for (let o = 1; o <= 8; o++) assert.equal(jpegOrientation(jpegWith(o, le)), o, `orientação ${o} ${le ? 'LE' : 'BE'}`);
  }
});

test('o JPEG com EXIF injetado continua sendo um JPEG válido', { skip: !haveConvert }, () => {
  const f = path.join(tmp, 'celular.jpg');
  fs.writeFileSync(f, jpegWith(6, false));
  const out = execFileSync('identify', ['-format', '%w x %h %m', f]).toString();
  assert.match(out, /8 x 4 JPEG/);
});

test('JPEG sem EXIF devolve 1', { skip: !haveConvert }, () => {
  assert.equal(jpegOrientation(jpegWith(0)), 1);
});

test('JPEG sem EXIF, PNG e lixo devolvem 1 sem estourar', () => {
  assert.equal(jpegOrientation(new Uint8Array([0xff, 0xd8, 0xff, 0xd9])), 1);
  assert.equal(jpegOrientation(new Uint8Array([0x89, 0x50, 0x4e, 0x47])), 1);
  assert.equal(jpegOrientation(new Uint8Array()), 1);
  assert.equal(jpegOrientation(null), 1);
  // EXIF truncado no meio do cabeçalho
  assert.equal(jpegOrientation(new Uint8Array([0xff, 0xd8, 0xff, 0xe1, 0x00, 0x10, 0x45, 0x78, 0x69, 0x66, 0, 0, 0x49])), 1);
});
