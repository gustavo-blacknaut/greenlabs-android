# GreenLabs Mobile

Cliente Android do [GreenLabs Live Streaming](https://github.com/gustavo-blacknaut/greenlabs-live-streaming).

Permite entrar nas salas pelo celular para **assistir** as transmissões e
participar com **câmera e microfone**.

> **Estado: não compilado ainda.** O código está escrito, mas o APK nunca foi
> gerado nem testado em aparelho. Veja [O que falta](#o-que-falta).

---

## O que funciona (por design)

| Recurso | Android |
|---|---|
| Assistir transmissões | ✅ |
| Câmera | ✅ |
| Microfone | ✅ |
| Entrar em sala / servidor | ✅ |
| Transmitir a tela | ❌ |
| Áudio do sistema sem Discord | ❌ |

As duas últimas dependem de APIs que só existem no Windows
(`getDisplayMedia` e WASAPI process loopback). A interface esconde esses
controles automaticamente quando não estão disponíveis.

---

## Como funciona

O app é um WebView que carrega o mesmo cliente React do desktop, servido
localmente:

```
┌─────────────────────────────────────────┐
│  MainActivity (WebView)                 │
│                                         │
│   http://127.0.0.1:<porta aleatória>    │
│                    ▲                    │
│                    │                    │
│   AssetHttpServer ─┘                    │
│   serve app/src/main/assets/web/        │
└─────────────────────────────────────────┘
                    │
              WebRTC + ws://
                    ▼
         servidor de sinalização
```

### Por que um servidor HTTP local

Foi a única das três opções que atende os dois requisitos ao mesmo tempo:

| Origem | `getUserMedia` | `ws://` para a LAN |
|---|---|---|
| `file://` | ❌ não é secure context | ✅ |
| `https://appassets.androidplatform.net` | ✅ | ❌ mixed content bloqueia |
| **`http://127.0.0.1`** | ✅ exceção de loopback | ✅ |

O `AssetHttpServer` é um servidor mínimo (~180 linhas, sem dependências) que
serve os arquivos de `assets/web/` numa porta aleatória do loopback.

---

## Compilando

```bash
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/`.

Para release (precisa configurar assinatura):

```bash
./gradlew assembleRelease
```

### Atualizando o cliente web

Os arquivos em `app/src/main/assets/web/` são o build do repositório principal.
Para atualizar:

```bash
# no repositório greenlabs-live-streaming
npm run build

# copie dist/ para cá
cp -r dist/. ../greenlabsapp/app/src/main/assets/web/
```

---

## Permissões

| Permissão | Motivo |
|---|---|
| `INTERNET` | conectar no servidor de sinalização e nos peers |
| `ACCESS_NETWORK_STATE` | detectar rede disponível |
| `CAMERA` | transmitir vídeo |
| `RECORD_AUDIO` | transmitir voz |
| `MODIFY_AUDIO_SETTINGS` | roteamento de áudio em chamada |

`usesCleartextTraffic="true"` é necessário porque o app usa `http://127.0.0.1`
internamente e `ws://` para servidores na rede local.

Câmera e microfone são pedidos em tempo de execução na primeira abertura. Sem
eles o app ainda funciona, mas só para assistir.

---

## O que falta

- [ ] Gerar e testar o APK em aparelho real
- [ ] Verificar WebRTC no WebView em versões diferentes do Android
- [ ] Ajustar a interface para telas pequenas em uso real
- [ ] Ícone próprio (hoje usa o padrão do template)
- [ ] Assinatura de release

---

## Requisitos

- Android 7.0 (API 24) ou superior
- WebView atualizado (vem pela Play Store na maioria dos aparelhos)

---

## Créditos

Desenvolvido com auxílio do **Claude Code** (Anthropic).

Faz parte do projeto [GreenLabs Live Streaming](https://github.com/gustavo-blacknaut/greenlabs-live-streaming).
