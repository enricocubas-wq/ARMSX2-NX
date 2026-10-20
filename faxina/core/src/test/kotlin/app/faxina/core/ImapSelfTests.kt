package app.faxina.core

import app.faxina.core.mail.FolderRole
import app.faxina.core.mail.HeaderBlock
import app.faxina.core.mail.ImapClient
import app.faxina.core.mail.ImapConfig
import app.faxina.core.mail.ImapException
import app.faxina.core.mail.ImapUtf7
import app.faxina.core.mail.MailActions
import app.faxina.core.mail.MailFlag
import app.faxina.core.mail.MailPreset
import app.faxina.core.mail.MailScanner
import app.faxina.core.mail.Rfc2047
import app.faxina.core.mail.Security

private fun cfg(s: FakeImapServer, user: String = "eu@gmail.com", pass: String = "abcdabcdabcdabcd") =
    ImapConfig("127.0.0.1", s.port, user, pass, Security.NONE, timeoutMs = 10_000)

private fun gmailServer(n: Int = 1200, move: Boolean = true, pass: String = "abcdabcdabcdabcd"): FakeImapServer {
    val s = FakeImapServer("eu@gmail.com", pass, gmail = true, move = move)
    s.folders["INBOX"] = FakeImapServer.Folder("INBOX", "")
    val all = FakeImapServer.Folder("[Gmail]/All Mail", "\\All")
    s.folders[all.name] = all
    s.folders["[Gmail]/Trash"] = FakeImapServer.Folder("[Gmail]/Trash", "\\Trash")
    s.folders["[Gmail]/Spam"] = FakeImapServer.Folder("[Gmail]/Spam", "\\Junk")
    s.folders["[Gmail]/&AOE-rea"] = FakeImapServer.Folder("[Gmail]/&AOE-rea", "")
    var uid = 100L
    for (i in 1..n) {
        val m = when {
            i % 10 == 0 -> FakeMsg(uid, 3_000_000, "05-Mar-2021 10:00:00 +0000", mutableSetOf("\\Seen"), "\"=?UTF-8?B?TG9qYSBDYXLDo28=?=\" <ofertas@loja.com>", "=?UTF-8?Q?Promo=C3=A7=C3=A3o_gigante?=", true, "promotions")
            i % 7 == 0 -> FakeMsg(uid, 90_000, "10-Jan-2019 08:00:00 -0300", mutableSetOf("\\Seen"), "Rede <social@rede.com>", "Alguém te marcou", true, "social")
            i == 3 -> FakeMsg(uid, 8_000_000, "01-Feb-2022 08:00:00 +0000", mutableSetOf("\\Flagged"), "Chefe <chefe@empresa.com>", "Contrato assinado (guardar!)", false)
            else -> FakeMsg(uid, 20_000, "15-Jun-2023 12:00:00 +0000", if (i % 2 == 0) mutableSetOf("\\Seen") else mutableSetOf(), "amigo@x.com", "oi $i", false, if (i % 5 == 0) "updates" else null)
        }
        all.msgs.add(m)
        uid++
    }
    for (i in 1..4) s.folder("[Gmail]/Trash").msgs.add(FakeMsg(900L + i, 1_000_000, "01-Jan-2020 00:00:00 +0000", mutableSetOf(), "a@b.com", "lixo", false))
    for (i in 1..3) s.folder("[Gmail]/Spam").msgs.add(FakeMsg(950L + i, 500_000, "01-Jan-2020 00:00:00 +0000", mutableSetOf(), "spam@b.com", "spam", false))
    return s
}

internal fun imapTests(h: Harness) {
    h.test("Rfc2047: base64, quoted-printable, palavras coladas") {
        expectEq("Loja Carão", Rfc2047.decode("=?UTF-8?B?TG9qYSBDYXLDo28=?="))
        expectEq("Promoção gigante", Rfc2047.decode("=?UTF-8?Q?Promo=C3=A7=C3=A3o_gigante?="))
        expectEq("Olá mundo", Rfc2047.decode("=?utf-8?Q?Ol=C3=A1?= =?utf-8?Q?_mundo?="))
        expectEq("João <j@x.com>", Rfc2047.decode("=?ISO-8859-1?Q?Jo=E3o?= <j@x.com>"))
        expectEq("sem codificação", Rfc2047.decode("sem codificação"))
    }
    h.test("ImapUtf7 e cabeçalhos") {
        expectEq("[Gmail]/área", ImapUtf7.decode("[Gmail]/&AOE-rea"))
        expectEq("R&D", ImapUtf7.decode("R&-D"))
        val hb = HeaderBlock.parse("From: A <a@x.com>\r\nList-Unsubscribe: <a>,\r\n <b>\r\nSubject: oi\r\n\r\n")
        expectEq("<a>, <b>", hb["list-unsubscribe"], "desdobrar")
        val (n, a) = HeaderBlock.parseAddress("\"Fulano, de Tal\" <FULANO@Exemplo.com>")
        expectEq("Fulano, de Tal", n); expectEq("fulano@exemplo.com", a)
        expectEq("x@y.com", HeaderBlock.parseAddress("x@Y.com").second)
    }
    h.test("uidSets comprime intervalos e quebra comandos longos") {
        expectEq(listOf("1:3,7,9:10"), ImapClient.uidSets(longArrayOf(10, 1, 2, 3, 7, 9)))
        expectEq(listOf("5"), ImapClient.uidSets(longArrayOf(5, 5)))
        val many = LongArray(3000) { it * 2L + 1 } // sem vizinhos: não comprime
        val sets = ImapClient.uidSets(many, maxLen = 500)
        expect(sets.size > 1 && sets.all { it.length <= 500 }) { "partes=${sets.size}" }
        expectEq(3000, sets.sumOf { it.split(',').size }, "nenhum uid perdido")
    }
    h.test("data INTERNALDATE") {
        expectEq(0, ImapClient.parseInternalDateDay("lixo"))
        expectEq(java.time.LocalDate.of(2021, 3, 5).toEpochDay().toInt(), ImapClient.parseInternalDateDay("05-Mar-2021 10:00:00 +0000"))
    }
    h.test("login recusado vira erro de autenticação") {
        gmailServer(5).use { s ->
            val c = ImapClient(cfg(s, pass = "errada"))
            c.use {
                it.connect()
                val e = runCatching { it.login() }.exceptionOrNull() as ImapException
                expectEq(ImapException.Kind.AUTH, e.kind)
                expect(e.message!!.contains("Invalid credentials")) { e.message!! }
            }
        }
    }
    h.test("senha com acento usa AUTHENTICATE PLAIN") {
        gmailServer(5, pass = "sênha-é-ç").use { s ->
            ImapClient(cfg(s, pass = "sênha-é-ç")).use {
                it.connect(); it.login()
                expect(s.log.any { l -> l.contains("AUTHENTICATE PLAIN") }) { "não usou PLAIN" }
            }
        }
    }
    h.test("pastas: papéis e nomes legíveis") {
        gmailServer(5).use { s ->
            ImapClient(cfg(s)).use {
                it.connect(); it.login()
                expect(it.isGmail && it.hasMove && it.hasUidPlus)
                val f = it.listFolders()
                expectEq("[Gmail]/All Mail", f.first { x -> x.role == FolderRole.ALL }.raw)
                expectEq("[Gmail]/Trash", f.first { x -> x.role == FolderRole.TRASH }.raw)
                expectEq("[Gmail]/Spam", f.first { x -> x.role == FolderRole.SPAM }.raw)
                expectEq("INBOX", f.first { x -> x.role == FolderRole.INBOX }.raw)
                expect(f.any { x -> x.display == "[Gmail]/área" && x.raw == "[Gmail]/&AOE-rea" }) { "utf7 não decodificado" }
            }
        }
    }
    h.test("análise de 1.200 e-mails em lotes: remetentes, categorias, grandes, estrela") {
        gmailServer(1200).use { s ->
            ImapClient(cfg(s)).use { c ->
                c.connect(); c.login()
                val progress = ArrayList<Int>()
                val r = MailScanner.scan(c, "eu@gmail.com", todayDay = java.time.LocalDate.of(2026, 1, 1).toEpochDay().toInt()) { done, _ -> progress.add(done) }
                expectEq(1200, r.totalCount, "total")
                expectEq(listOf(500, 1000, 1200), progress, "progresso em lotes")
                expect(r.isGmail && r.scanFolder.raw == "[Gmail]/All Mail")
                // remetentes ordenados por bytes; a loja manda 120 × 3 MB
                val top = r.senders.first()
                expectEq("ofertas@loja.com", top.address, "top remetente")
                expectEq("Loja Carão", top.name, "nome decodificado")
                expectEq(120, top.count)
                expect(top.hasUnsubscribe)
                // categorias do Gmail via X-GM-RAW
                expectEq(120, r.countFor(MailPreset.PROMOTIONS), "promoções")
                expect(r.countFor(MailPreset.SOCIAL) > 100) { "social=${r.countFor(MailPreset.SOCIAL)}" }
                // estrela nunca é sugerida, mesmo sendo grande
                val big = r.uidsFor(MailPreset.BIG)
                expect(big.none { u -> u == 102L }) { "mensagem com estrela apareceu na limpeza" }
                expectEq(0, r.countFor(MailPreset.BIG), "só 8 MB com estrela é >=5MB; os de 3 MB não são")
                expect(r.big.first().size == 8_000_000L && r.big.first().subject.startsWith("Contrato")) { "grandes: ${r.big.firstOrNull()?.subject}" }
                // lixeira e spam
                expectEq(4, r.trashCount); expectEq(4_000_000L, r.trashBytes)
                expectEq(3, r.spamCount); expectEq(1_500_000L, r.spamBytes)
                // antigos e lidos (≥2 anos até 2026-01-01): 2019 (social, lidos)
                expect(r.countFor(MailPreset.OLD_READ) > 0)
            }
        }
    }
    h.test("apagar com MOVE vai para a lixeira e esvaziar libera de vez") {
        gmailServer(300).use { s ->
            ImapClient(cfg(s)).use { c ->
                c.connect(); c.login()
                val r = MailScanner.scan(c, "eu@gmail.com")
                val promo = r.uidsFor(MailPreset.PROMOTIONS)
                expectEq(30, promo.size)
                val before = s.folder("[Gmail]/All Mail").msgs.size
                expect(MailActions.delete(c, r, promo)) { "deveria ter ido para a lixeira" }
                expectEq(before - 30, s.folder("[Gmail]/All Mail").msgs.size, "All Mail")
                expectEq(4 + 30, s.folder("[Gmail]/Trash").msgs.size, "Trash")
                val r2 = r.afterDeleting(promo.toList(), movedToTrash = true)
                expectEq(270, r2.totalCount); expectEq(34, r2.trashCount)
                MailActions.emptyTrashAndSpam(c, r2)
                expectEq(0, s.folder("[Gmail]/Trash").msgs.size, "lixeira vazia")
                expectEq(0, s.folder("[Gmail]/Spam").msgs.size, "spam vazio")
                expectEq(0, r2.afterEmptying().trashCount)
            }
        }
    }
    h.test("servidor sem MOVE: copia, marca e expurga só os escolhidos") {
        gmailServer(100, move = false).use { s ->
            ImapClient(cfg(s)).use { c ->
                c.connect(); c.login()
                expect(!c.hasMove)
                val r = MailScanner.scan(c, "eu@gmail.com")
                val sel = r.uidsFor(MailPreset.PROMOTIONS)
                val keep = s.folder("[Gmail]/All Mail").msgs.size - sel.size
                MailActions.delete(c, r, sel)
                expectEq(keep, s.folder("[Gmail]/All Mail").msgs.size, "restantes")
                expectEq(4 + sel.size, s.folder("[Gmail]/Trash").msgs.size)
            }
        }
    }
    h.test("remetente: apagar tudo de um remetente respeita estrela") {
        gmailServer(50).use { s ->
            ImapClient(cfg(s)).use { c ->
                c.connect(); c.login()
                val r = MailScanner.scan(c, "eu@gmail.com")
                val chefe = r.senders.first { it.address == "chefe@empresa.com" }
                expectEq(0, r.uidsForSenders(setOf(chefe.index)).size, "mensagem do chefe tem estrela")
                val loja = r.senders.first { it.address == "ofertas@loja.com" }
                expectEq(loja.count, r.uidsForSenders(setOf(loja.index)).size)
            }
        }
    }
    h.test("flags: UNSUB e SEEN chegam no índice") {
        gmailServer(20).use { s ->
            ImapClient(cfg(s)).use { c ->
                c.connect(); c.login()
                val r = MailScanner.scan(c, "x")
                val p = r.index.position(110L) // i = 11? uid = 100 + i - 1
                expect(p >= 0)
                expect(r.index.flags.any { it and MailFlag.UNSUB != 0 })
                expect(r.index.flags.any { it and MailFlag.SEEN != 0 })
            }
        }
    }
}
