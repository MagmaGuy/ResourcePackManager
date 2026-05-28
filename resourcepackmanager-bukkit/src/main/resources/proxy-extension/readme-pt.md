# ResourcePackManager — Plugin do Proxy

> **⚠ Este plugin só é útil para servidores rodando Geyser + Floodgate.**
> Seu único propósito é fundir o resource pack convertido para Bedrock de cada
> backend e entregá-lo aos clientes Bedrock via Geyser. **Jogadores de Java
> Edition não precisam deste plugin** — a entrega de packs para Java em
> configurações de rede é tratada diretamente pelos backends individuais. Se
> a sua rede é só Java (sem jogadores Bedrock passando pelo Geyser), você
> pode ignorar esta pasta por completo.

Esta pasta contém os JARs do **plugin de proxy do RSPM**. Eles vão no seu **proxy**
(Velocity / BungeeCord / Waterfall), NÃO no servidor backend do Minecraft. O plugin
do proxy funde o resource pack convertido para Bedrock de cada backend em um único
pack e o entrega aos clientes Bedrock via Geyser.

Se você roda um servidor independente (sem proxy à frente), pode ignorar esta pasta
por completo. Os JARs são extraídos a cada inicialização de qualquer forma, então
ficam prontos caso você adicione um proxy mais tarde.

---

## Qual JAR devo usar?

Escolha exatamente UM, conforme o seu software de proxy:

| Software de proxy        | Use este JAR                             |
|--------------------------|------------------------------------------|
| Velocity                 | `ResourcePackManager-Velocity.jar`       |
| BungeeCord               | `ResourcePackManager-BungeeCord.jar`     |
| Waterfall (fork do Bungee) | `ResourcePackManager-BungeeCord.jar`   |

Não instale os dois — apenas o correspondente. Instalar ambos causará erros de
inicialização na plataforma que tentar carregar o JAR errado.

---

## Instalação — 2 passos

### 1. Copie o JAR para o host do proxy

Copie o JAR correspondente DESTA pasta para o diretório `plugins/` do seu proxy.
Métodos comuns:

- **Mesma máquina**: arrastar e soltar, ou `cp` / `copy`.
- **Proxy remoto**: `scp ResourcePackManager-Velocity.jar usuario@host-proxy:/caminho/para/proxy/plugins/`
- **Painel de proxy hospedado** (Pterodactyl, etc.): envie pelo gerenciador de arquivos do painel.

### 2. Reinicie o proxy

Pronto. Sem configuração para editar, sem chave para colar.

O plugin lê `plugins/floodgate/key.pem` no proxy ao iniciar e deriva a identidade
de rede desse arquivo automaticamente. Como o Floodgate já exige que esse arquivo
seja o mesmo em todos os backends E no proxy (para a autenticação Bedrock
funcionar), a chave derivada coincide automaticamente com a de cada backend.

A primeira fusão geralmente se completa em ~10 segundos depois que todos os
backends estiverem online.

---

## Verificando se funcionou

**Console do proxy** (em ~10s após o reinício, supondo que os backends estão rodando):

```
[ResourcePackManager] Merged pack ready at .../merged/Bedrock.zip (sha1 ...)
[ResourcePackManager] Geyser mappings deployed to .../custom_mappings
[ResourcePackManager] ✔ Network resource pack is now ready (... KB, sha1ABCD1234)
```

**Cliente Bedrock**: conecte-se via Bedrock. Você deve ver o prompt de download do
resource pack antes de chegar ao mundo. Os itens personalizados são renderizados com
seus modelos pretendidos em vez de armor stands simples.

**`/rspm status`** no backend: mostra o estado do pack, o modo de hospedagem e o
fingerprint da network-key. Compare os últimos 4 caracteres com a config do proxy
para confirmar que ambos os lados estão ligados.

---

## Problemas comuns

### "Floodgate key.pem missing" na inicialização do proxy

O plugin do proxy não encontrou `plugins/floodgate/key.pem` e ficou inativo.
Correção:

1. **Instale o Floodgate** no proxy. Ele é necessário para que jogadores
   Bedrock cheguem ao proxy de qualquer forma, então é preciso independentemente
   do RSPM.
2. Garanta que `plugins/floodgate/key.pem` no proxy é **idêntico byte a byte**
   ao mesmo arquivo em cada backend. O Floodgate gera automaticamente chaves
   diferentes por instalação — copie um `key.pem` canônico de qualquer backend
   para todos os outros componentes (outros backends + o proxy) e reinicie tudo.
   O Floodgate já exige isso para a autenticação Bedrock, então se jogadores
   Bedrock funcionam atualmente na sua rede, isso já está feito.

### O Bedrock conecta mas não vê os modelos personalizados

Causa mais comum: o proxy iniciou ANTES de o backend produzir seu primeiro pack
Bedrock. O Geyser registra itens personalizados apenas na inicialização — uma vez
rodando com um arquivo de mappings vazio, fica assim. **Reinicie o proxy** depois
que o backend tiver registrado `Wrote merged Geyser mappings: N entries`. Fusões
subsequentes são captadas automaticamente pela rota de entrega de packs do Geyser,
mas a tabela de itens personalizados é fixada na inicialização.

### Avisos "Duplicate bedrock_identifier" na inicialização do proxy

Dois backends emitiram o mesmo identificador Bedrock para o mesmo item base. O
último a escrever vence; é inofensivo se você só precisa que um backend forneça
aquele item. Se ambos os backends devem hospedar itens personalizados distintos
sob o mesmo item base, há uma colisão real — renomeie um dos modelos Java de origem
para que os hashes autogerados sejam diferentes.

### Atualizando o RSPM

Após atualizar o JAR do backend do RSPM, copie também novamente o
`ResourcePackManager-Velocity.jar` / `-BungeeCord.jar` correspondente desta pasta
para o proxy e reinicie o proxy. O backend os regenera a cada inicialização, então
estão sempre sincronizados com a versão do backend.

---

Gerado pelo ResourcePackManager v${version} na inicialização do backend.
Outros idiomas disponíveis: veja `README.md` (inglês / English), `README - espanol.md`,
`README - francais.md`, `README - zhongwen.md`, `README - hindi.md`,
`README - bangla.md`, `README - arabi.md`, `README - russkij.md`, `README - urdu.md`.
