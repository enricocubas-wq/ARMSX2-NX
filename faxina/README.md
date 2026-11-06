# Faxina

App Android para **liberar gigas de armazenamento** do celular e da caixa de e-mail, com poucos toques.
100% local: **sem anúncios, sem conta, sem IA e sem gastar token nenhum**. A única conexão de rede é a limpeza
de e-mail, direto entre o celular e o servidor IMAP do seu provedor.

## Como instalar no celular

1. No celular, abra a página de **Releases** do repositório no GitHub e entre em **Faxina — APK mais recente**
   (tag `faxina-latest`).
2. Baixe o arquivo **`Faxina.apk`**.
3. Abra o arquivo baixado. Se o Android pedir, permita "instalar apps desta fonte" para o navegador/gerenciador de arquivos.
4. Abra o **Faxina** e toque em **Permitir acesso aos arquivos** (ative a chave na tela do Android e volte).

> Atualizar: instale o APK novo por cima. A assinatura é fixa, então nada se perde.

## O que ele faz

| Recurso | Como funciona |
|---|---|
| **Limpar agora** | Raio-X do armazenamento em segundos. Já vem marcado só o que é seguro; você confere e toca em **Limpar**. |
| **Lixo seguro** | Arquivos temporários/logs, cache e miniaturas, lixeira da galeria, pastas vazias, status do WhatsApp em cache, instaladores (APK) de apps que você já tem. |
| **Duplicados** | Compara o conteúdo byte a byte (tamanho → amostra → SHA-1). Mantém 1 cópia, preferindo DCIM/Câmera, e sugere apagar o resto. |
| **Arquivos grandes** | Tudo acima de 50 MB, com miniaturas e filtro por vídeos/fotos/outros. |
| **WhatsApp e Telegram** | Fotos, vídeos, áudios, documentos, mídia enviada e backups locais antigos, separados por tipo. |
| **Capturas de tela / Downloads antigos / APKs** | Listas prontas para revisar. |
| **Apps** | Tamanho, cache e último uso de cada app; desinstalar e tentar o botão "limpar cache" do sistema. |
| **Explorar pastas** | Raio-X em árvore: toque nas pastas, veja quem pesa mais e apague direto. |
| **E-mail** | Lê só cabeçalhos (nunca baixa anexos). Mostra quem mais ocupa espaço, promoções/redes sociais do Gmail, newsletters, e-mails grandes e lixeira/spam. E-mails com estrela nunca entram na limpeza em massa. |

### E-mail: como conectar

O provedor não aceita a senha normal em apps. Crie uma **senha de app** (com a verificação em duas etapas ligada):

- **Gmail:** <https://myaccount.google.com/apppasswords>
- **Outlook/Hotmail, Yahoo, iCloud:** o botão dentro do app abre a página certa.

A senha fica cifrada com uma chave do Android Keystore e só é usada para falar com o servidor do seu e-mail.

## Limites do Android (honestidade)

- **Cache de outros apps:** o Android não deixa um app limpar o cache de outro sozinho. O Faxina tenta abrir o botão de
  limpeza do sistema; se o seu celular não tiver, mostra o tamanho do cache de cada app e abre a tela do app para você tocar em *Limpar cache*.
- **Pastas `Android/data`** de outros apps ficam bloqueadas no Android 11+; por isso não aparecem na limpeza (o cache delas entra pelo botão acima).
- Apagar é **definitivo** (não vai para a lixeira). Por isso sempre há uma lista e uma confirmação antes.

## Faxina para Mac

Mesmo espírito do app de celular, feito para o macOS (Apple Silicon e Intel). Interface com barra lateral, leitura da sua pasta pessoal em paralelo e as mesmas ferramentas de duplicados e e-mail.

### Como instalar

1. Descubra o seu Mac: menu  › **Sobre Este Mac**. Se aparecer **Chip Apple M1/M2/M3/M4**, use o arquivo *AppleSilicon*; se aparecer **Processador Intel**, use o *Intel*.
2. Abra o **Terminal** (⌘+Espaço, digite *Terminal*), cole **uma** das linhas abaixo e aperte Enter. Ela baixa o instalador e abre a janela do DMG:

   ```bash
   # Mac com chip Apple (M1, M2, M3, M4)
   curl -L -o ~/Downloads/Faxina-Mac.dmg https://github.com/enricocubas-wq/ARMSX2-NX/releases/download/faxina-mac-latest/Faxina-Mac-AppleSilicon.dmg && open ~/Downloads/Faxina-Mac.dmg

   # Mac com processador Intel
   curl -L -o ~/Downloads/Faxina-Mac.dmg https://github.com/enricocubas-wq/ARMSX2-NX/releases/download/faxina-mac-latest/Faxina-Mac-Intel.dmg && open ~/Downloads/Faxina-Mac.dmg
   ```
3. Arraste o **Faxina** para **Aplicativos** e abra.

> **Por que pelo Terminal?** O app não é assinado pela Apple (isso custa uma conta de desenvolvedor paga). Arquivos baixados pelo navegador ganham uma "marca de quarentena" e o macOS diz que o app "está danificado". Baixando pelo `curl` essa marca não é criada. Se você baixou pelo navegador, depois de arrastar para Aplicativos rode:
> `xattr -cr /Applications/Faxina.app`
> (Mac com chip Apple também roda o instalador *Intel* pelo Rosetta, mas o *AppleSilicon* é bem mais rápido.)
> ou abra **Ajustes do Sistema › Privacidade e Segurança** e toque em **Abrir Mesmo Assim**.

### Permissões (importante)

- Na primeira análise o macOS pergunta se o Faxina pode acessar **Mesa**, **Documentos** e **Downloads**. Aceite.
- Para ver a **Lixeira**, o **Mail** e os caches protegidos, ative o Faxina em **Ajustes do Sistema › Privacidade e Segurança › Acesso Total ao Disco** e abra o app de novo. O próprio app avisa quando falta.
- O macOS também pode perguntar se o Faxina pode acessar **dados de outros apps** (é onde ficam os caches deles). Aceite para vê-los na limpeza.
- Ao esvaziar a Lixeira, o macOS pede permissão para o Faxina controlar o **Finder**.
- Como o app não é assinado, o macOS pode pedir essas permissões de novo depois de uma atualização.

### O que ele limpa no Mac

| Gaveta | O que é |
|---|---|
| **Cache de apps** (seguro) | `~/Library/Caches` (menos os da Apple) e caches de apps Electron/Chromium (Slack, VS Code, Chrome...) |
| **Logs e relatórios de erro** (seguro) | `~/Library/Logs`, relatórios de travamento |
| **Cache de desenvolvimento** (seguro) | Xcode DerivedData, Homebrew, npm, pip, CocoaPods |
| **Anexos do Mail**, **temporários**, **Lixeira** (seguro) | cópias de anexos, `.tmp`/downloads incompletos/`__MACOSX`, itens que você já jogou fora |
| **Duplicados** | mesmo conteúdo byte a byte; ignora a biblioteca do Fotos, `node_modules` e arquivos que ainda estão só no iCloud |
| **Arquivos grandes** | acima de 100 MB, pelo tamanho **real** no disco (discos de máquina virtual e arquivos do iCloud na nuvem não enganam) |
| **Instaladores, capturas de tela, downloads antigos** | `.dmg`/`.pkg`, prints da Mesa, Downloads com mais de 90 dias |
| **Desenvolvimento pesado, backups de iPhone** | simuladores, DeviceSupport, Gradle/Maven, backups do Finder |
| **Apps** | tamanho, último uso e **desinstalação com os restos** da Biblioteca (vai para a Lixeira) |
| **Pastas** | raio-x em árvore; pastas importantes do início ficam protegidas contra seleção |
| **E-mail** | igual ao celular; a senha de app fica no **Keychain** do macOS |

Itens "confira antes" vão para a **Lixeira** por padrão (dá para desfazer); o botão da barra inferior troca para apagar na hora. O espaço só volta depois de esvaziar a Lixeira, e o app oferece esse botão no fim.

## O que foi (e não foi) testado

- **Testado automaticamente a cada build (29 verificações):** varredura e regras de lixo, WhatsApp/Telegram, pastas vazias, duplicados
  (inclusive "mesmo tamanho, miolo diferente"), remoção, leitura de e-mail em lotes, categorias do Gmail, MOVE e fallback sem MOVE,
  esvaziar lixeira, proteção de e-mails com estrela e erro de senha. O e-mail é testado contra um servidor IMAP de brinquedo, não contra uma conta real.
- **Compila em release (R8) no GitHub**, mas as telas ainda não foram exercitadas num aparelho real. Se algo travar ou ficar estranho no seu
  modelo de celular, abra uma *issue* com a versão do Android e o que aconteceu.
- Outlook/Hotmail podem exigir configurações extras da Microsoft para IMAP; o Gmail é o caminho mais bem coberto.

## Para quem for compilar

```bash
cd faxina
./gradlew :core:selfTest        # testes do núcleo (varredura, duplicados, regras do Mac, IMAP contra servidor falso)
./gradlew :app:assembleRelease  # APK em app/build/outputs/apk/release/
```

- `core/` — Kotlin puro (sem Android): varredura paralela com `java.nio`, regras de lixo, duplicados, cliente IMAP próprio.
- `app/` — Jetpack Compose (Material 3), ViewModels, permissões, apps instalados, tela de e-mail (Android).
- `mac/` — Compose Desktop (Material 3) para macOS: mesmo núcleo, regras próprias do Mac, Lixeira, Keychain.
  `./gradlew :mac:previewShots -PshotsDir=...` gera PNGs das telas com dados de mentira.
- Assinatura: `app/faxina.jks` é uma chave **pessoal e pública** só para manter a assinatura estável entre versões.
  Se for publicar o app em loja, gere a sua e guarde fora do repositório.
- O workflow `.github/workflows/faxina-apk.yml` compila e publica o APK sozinho a cada push em `faxina/`.
