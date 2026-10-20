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

## Para quem for compilar

```bash
cd faxina
./gradlew :core:selfTest        # testes do núcleo (varredura, duplicados, IMAP contra servidor falso)
./gradlew :app:assembleRelease  # APK em app/build/outputs/apk/release/
```

- `core/` — Kotlin puro (sem Android): varredura paralela com `java.nio`, regras de lixo, duplicados, cliente IMAP próprio.
- `app/` — Jetpack Compose (Material 3), ViewModels, permissões, apps instalados, tela de e-mail.
- Assinatura: `app/faxina.jks` é uma chave **pessoal e pública** só para manter a assinatura estável entre versões.
  Se for publicar o app em loja, gere a sua e guarde fora do repositório.
- O workflow `.github/workflows/faxina-apk.yml` compila e publica o APK sozinho a cada push em `faxina/`.
