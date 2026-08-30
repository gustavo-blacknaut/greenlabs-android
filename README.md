<div align="center">

<img src="app/src/main/assets/web/logo.png" width="96" alt="GreenLabs">

# GreenLabs para Android

**Entre na chamada pelo celular. Assista, apareça, mostre sua tela.**

Sem conta e sem limite de tempo.

[![Baixar](https://img.shields.io/badge/Baixar-APK-16A34A?style=for-the-badge)](https://github.com/gustavo-blacknaut/greenlabs-android/releases/latest)
&nbsp;
![Android 7+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square)
![WebRTC](https://img.shields.io/badge/WebRTC-P2P-6B7280?style=flat-square)

<img src="docs/greenlabs-android.png" width="300" alt="GreenLabs rodando no Android" />

</div>

---

## O que dá para fazer

| | |
| --- | :---: |
| Assistir quem está transmitindo | ✅ |
| Aparecer na câmera | ✅ |
| Falar pelo microfone | ✅ |
| Mostrar a tela do celular | ✅ |
| Mandar o som do sistema sem o Discord | ❌ |

A última linha depende do WASAPI, que só existe no Windows. Para isso existe o
[aplicativo de Windows](https://github.com/gustavo-blacknaut/greenlabs-windows).

## Como usar

1. Baixe o APK na [página de versões](https://github.com/gustavo-blacknaut/greenlabs-android/releases/latest).
2. Instale (o Android vai pedir para permitir a instalação dessa origem).
3. Escreva seu apelido, o endereço do servidor e o nome da sala.

---

## Mostrar a tela do celular

Nenhum navegador Android implementa `getDisplayMedia` — não é limitação do
WebView, é da plataforma inteira
([caniuse](https://caniuse.com/mdn-api_mediadevices_getdisplaymedia)). Então
esse recurso não vem do WebView: vem de `MediaProjection`, a API nativa que
Discord, Zoom e Meet também usam.

```
MainActivity ──(Intent de permissão)──► sistema
     │
     ▼
ScreenCaptureService (foreground, tipo mediaProjection)
     │  MediaProjection → VirtualDisplay → ImageReader → JPEG
     ▼
ScreenStreamServer (http://127.0.0.1:<porta>/stream)
     │  quadros JPEG enquadrados (4 bytes de tamanho + conteúdo) — o mesmo
     │  formato que o áudio WASAPI do aplicativo de Windows já usa
     ▼
WebView: canvas + captureStream() → MediaStream real → addLocalStream()
```

O canvas é o que faz a ponte: os quadros chegam por HTTP local, são desenhados
nele, e `canvas.captureStream()` devolve uma `MediaStream` de verdade — daí em
diante é o mesmo caminho de código que uma câmera usa, e o resto do WebRTC não
precisa saber a diferença.

A captura respeita a qualidade escolhida, com piso de 16 ms entre quadros
(60 por segundo). Vale saber que cada quadro é comprimido em JPEG por software:
resolução e taxa altas custam bateria de verdade num celular.

**A partir do Android 14 o sistema exige uma notificação persistente** enquanto
a tela está sendo compartilhada. É a mesma notificação que Discord e Zoom
mostram — política da plataforma, não é opcional.

---

## Como o aplicativo é montado

Um WebView carregando o mesmo cliente do site, servido localmente, com o que
precisa ser nativo escrito em Kotlin ao redor.

```
┌─────────────────────────────────────────┐
│  MainActivity (WebView)                 │
│                                         │
│   http://127.0.0.1:47869                │
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

| Arquivo | O que faz |
| --- | --- |
| `MainActivity.kt` | a janela, as permissões e a ponte com o WebView |
| `ScreenCaptureService.kt` | `MediaProjection` → quadros JPEG |
| `ScreenStreamServer.kt` | entrega os quadros ao WebView por HTTP local |
| `AssetHttpServer.kt` | serve o cliente web de `assets/web/` |

### Por que um servidor HTTP local

Foi a única das três opções que atende os dois requisitos ao mesmo tempo:

| Origem | `getUserMedia` | `ws://` para a rede local |
| --- | :---: | :---: |
| `file://` | ❌ não é secure context | ✅ |
| `https://appassets.androidplatform.net` | ✅ | ❌ mixed content bloqueia |
| **`http://127.0.0.1`** | ✅ exceção de loopback | ✅ |

O `AssetHttpServer` é um servidor mínimo, sem dependência nenhuma, que serve os
arquivos de `assets/web/`. **A porta é fixa de propósito:** o `localStorage` é
preso à origem, e a porta faz parte dela — uma porta nova a cada abertura
significaria perder as configurações salvas toda vez.

---

## Compilando

```bash
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/`.

Para release, assinado e minificado:

```bash
./gradlew assembleRelease
```

Isso exige uma keystore em `keystore/greenlabs-release.jks` referenciada por
`keystore/keystore.properties` — nenhum dos dois vai para o git. Para gerar a
sua:

```bash
keytool -genkeypair -v -keystore keystore/greenlabs-release.jks \
  -alias greenlabs -keyalg RSA -keysize 2048 -validity 10950
```

E crie `keystore/keystore.properties`:

```properties
storeFile=greenlabs-release.jks
storePassword=SUA_SENHA
keyAlias=greenlabs
keyPassword=SUA_SENHA
```

> **Guarde essa keystore em lugar seguro.** Perdê-la significa não conseguir
> mais publicar atualizações assinadas com a mesma chave — quem já instalou não
> consegue atualizar por cima, só desinstalar e reinstalar.

### Atualizando o cliente web

Os arquivos em `app/src/main/assets/web/` são o build do repositório principal:

```bash
# no repositório greenlabs-desktop
npm run build
cp -r dist/. ../greenlabs-android/app/src/main/assets/web/
```

---

## Permissões

| Permissão | Motivo |
| --- | --- |
| `INTERNET` | conectar no servidor e nas outras pessoas |
| `ACCESS_NETWORK_STATE` | detectar rede disponível |
| `CAMERA` | aparecer na câmera |
| `RECORD_AUDIO` | falar |
| `MODIFY_AUDIO_SETTINGS` | roteamento de áudio em chamada |
| `FOREGROUND_SERVICE` / `..._MEDIA_PROJECTION` | exigidas pelo Android para capturar a tela em segundo plano |
| `POST_NOTIFICATIONS` | a notificação obrigatória durante o compartilhamento (Android 13+) |

`usesCleartextTraffic="true"` é necessário porque o aplicativo usa
`http://127.0.0.1` internamente e `ws://` para servidores na rede local.

Câmera e microfone são pedidos na primeira abertura. Sem eles o aplicativo ainda
funciona — só para assistir.

**Requisitos:** Android 7.0 (API 24) ou superior, com o WebView atualizado (vem
pela Play Store na maioria dos aparelhos).

---

## O resto do GreenLabs

| | |
| --- | --- |
| [greenlabs-desktop](https://github.com/gustavo-blacknaut/greenlabs-desktop) | Aplicativo para Windows e Linux |
| [greenlabs-windows](https://github.com/gustavo-blacknaut/greenlabs-windows) | Cliente nativo em C++, mais leve |
| [greenlabs-server](https://github.com/gustavo-blacknaut/greenlabs-server) | Servidor, um binário só |
| [greenlabs-site](https://github.com/gustavo-blacknaut/greenlabs-site) | Site e cliente pelo navegador |

O que mudou em cada versão está no [CHANGELOG.md](CHANGELOG.md).

---

Um projeto [GreenCodes](https://greencodes.com.br).
