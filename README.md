# PingLab (Android)

App Android para ping, traceroute, diagnóstico de conexão e teste de velocidade, feito para uso em campo.

## Abas

**Terminal**
- Ping contínuo ou com quantidade fixa, para um ou até 4 alvos ao mesmo tempo (separados por vírgula).
- Gráfico de latência em tempo real, estatísticas (mín, méd, máx, jitter, perda e quedas) e console.
- **Limpar** apaga o console e o gráfico (parado, zera também as estatísticas).
- Traceroute com 3 sondas por salto.
- Favoritos, histórico e atalhos para o gateway e o DNS da rede atual.
- Enviar o resultado como texto ou imagem, ou copiar o console.

**Diagnóstico**
- Verificação em etapas: conexão, IP (DHCP), roteador, internet, DNS, navegação e IP público, com uma dica do que pode ser em cada etapa que falhar.
- Teste de velocidade (download, upload, ping e jitter) com velocímetro.

**Condomínio** (casinha): as ONUs por quadra e lote, como no Monitor de ONUs do PC (IP `172.16.quadra.lote`).
- Cadastro de ONU (quadra, lote, identificação opcional), editar, remover e importar CSV/TXT (`quadra,lote[,identificação]` ou o IP).
- Toque numa ONU: ping individual (1 por segundo) numa janela, que para ao fechar. Toque longo: editar ou remover.
- **Pingar quadra**: 8 pings em cada ONU da quadra, resultado no console; o ponto vermelho indica ONU que perdeu pacote.
- **Pingar tudo**: monitoramento contínuo de todas as ONUs a cada 3 s, com a regra das 3 falhas seguidas (Online, Instável, Sem resposta/Erro).
- Resumo (total, respondendo, sem resposta, enviados, recebidos, perda), busca e filtro "só com erro".

**Opções** (botão ⋮ no topo): manter a tela ligada durante o ping, vibrar quando a conexão cair e voltar, e tema claro. A versão instalada aparece nesse menu.

## Visual

Identidade tirada do mundo da fibra: amarelo do cabo monomodo como cor da marca, verde (conector APC) para ok, laranja para atenção e vermelho (caneta VFL) para falha, sobre azul-tinta (tema escuro) ou cinza-claro (tema claro). Fontes: Barlow (interface), Barlow Semi Condensed (números) e IBM Plex Mono (console), todas em `app/src/main/res/font` (licença SIL Open Font License). Cores em `Palette.kt`; componentes em `UiKit.kt`.

## Sintaxe do campo de alvo

| Exemplo | O que faz |
|---|---|
| `172.16.1.1` | Ping contínuo |
| `172.16.1.1 -n 10` | 10 pacotes (`-c` também funciona) |
| `172.16.1.1 -l 1472` | Pacotes de 1472 bytes (`-s` também funciona) |
| `8.8.8.8, 1.1.1.1` | Vários alvos ao mesmo tempo (até 4) |

O app usa o `/system/bin/ping` do próprio Android; as respostas vêm do processo real.

## Gerar o APK

O workflow `.github/workflows/build-apk.yml` compila a cada push na `main` (ou manualmente em **Actions → Build Android APK → Run workflow**). Ao terminar, baixe o artefato **PingLab-APK** e instale o `app-debug.apk`.

O APK é assinado sempre com a mesma chave (`app/pingreal.keystore`), então cada versão nova instala por cima da anterior, sem desinstalar e sem perder favoritos e histórico.

## Testes

`gradle testDebugUnitTest` roda os testes da regra das 3 falhas, do importador, do monitoramento e do "pingar quadra" (com um ping falso, sem rede).

## Versões

A cada mudança, subir em `app/build.gradle.kts`:
- `versionCode` em 1 (2, 3, 4…)
- `versionName` (1.1, 1.2, 1.3…)

A versão instalada aparece no menu ⋮.
