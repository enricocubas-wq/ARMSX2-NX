'use strict';
/*
 * Zine Maker (desktop): janela do Electron que abre o app (um único HTML com tudo embutido).
 * Sem acesso à rede, sem permissões extras: tudo roda localmente.
 */
const { app, BrowserWindow, Menu, session, shell } = require('electron');
const path = require('node:path');

const APP_FILE = path.join(__dirname, 'app', 'index.html');
const isMac = process.platform === 'darwin';
let mainWindow = null;

function openExternal(url) {
  if (/^https?:\/\//i.test(url)) shell.openExternal(url);
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1360,
    height: 900,
    minWidth: 380,
    minHeight: 560,
    title: 'Zine Maker',
    backgroundColor: '#f2ecdf',
    show: false,
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, spellcheck: false },
  });
  mainWindow.once('ready-to-show', () => mainWindow.show());
  mainWindow.loadFile(APP_FILE);

  // links externos abrem no navegador; o app nunca navega para outro lugar
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    openExternal(url);
    return { action: 'deny' };
  });
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (url !== mainWindow.webContents.getURL()) {
      event.preventDefault();
      openExternal(url);
    }
  });
  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

function buildMenu() {
  const template = [
    ...(isMac ? [{ role: 'appMenu' }] : []),
    { label: 'Arquivo', submenu: [isMac ? { role: 'close' } : { role: 'quit', label: 'Sair' }] },
    { role: 'editMenu' }, // sem isto o copiar/colar não funciona nos campos de texto no Mac
    {
      label: 'Exibir',
      submenu: [{ role: 'resetZoom' }, { role: 'zoomIn' }, { role: 'zoomOut' }, { type: 'separator' }, { role: 'togglefullscreen' }],
    },
    { role: 'windowMenu' },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

// só uma janela do app por vez: abrir de novo traz a existente para frente
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(() => {
    // o app não precisa de câmera, microfone, localização etc.
    session.defaultSession.setPermissionRequestHandler((_wc, _perm, callback) => callback(false));

    // Ao baixar um PDF o Electron mostra o diálogo "Salvar como…". Nos testes automáticos
    // ZINE_TEST_DOWNLOAD_DIR define a pasta para pular o diálogo.
    const testDir = process.env.ZINE_TEST_DOWNLOAD_DIR;
    if (testDir) {
      session.defaultSession.on('will-download', (_e, item) => item.setSavePath(path.join(testDir, item.getFilename())));
    }

    buildMenu();
    createWindow();
    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  app.on('window-all-closed', () => {
    if (!isMac) app.quit();
  });
}
