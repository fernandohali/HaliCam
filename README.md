# HaliCam — Diagnóstico Camera2 (v0.1)

**Alvo inicial:** Samsung Galaxy S9+ (SM-G965) com Android 10 / API 29. Este projeto contém **somente diagnóstico**, sem gravação MP4 e sem webcam/OBS ainda.

## Objetivo

Testar corretamente se as câmeras frontal e traseira podem produzir quadros **simultaneamente** no Galaxy S9+. O fato de existir uma lente teleobjetiva traseira, ou de ambas as câmeras serem listadas, **não comprova** o recurso.

## Abrir no Android Studio

1. Abra a pasta **HaliCam** como projeto no Android Studio.
2. Configure **JDK 17** e instale o **Android SDK Platform 35**. O `compileSdk=35` permite executar também em Android 10 (`minSdk=26`).
3. O projeto usa **AGP 8.9.2**, **Kotlin 2.1.20** e **Gradle 8.11.1**. Se o Android Studio pedir sincronização/download de dependências, autorize.
4. Conecte o S9+ por cabo USB, habilite **Opções de desenvolvedor → Depuração USB** e aceite a autorização no aparelho.
5. Selecione o dispositivo e execute **app** (Run ▶). Conceda permissão de câmera quando solicitado.
6. Toque em **Inventariar câmeras** e depois em **Testar câmera dupla (6 s)**. Aguarde o teste finalizar, sem fechar a tela.
7. Toque em **Compartilhar relatório** e salve/envie o conteúdo do diagnóstico.

**Nota sobre Gradle Wrapper:** se você recebeu uma cópia sem o arquivo `gradle-wrapper.jar`, instale/selecione Gradle 8.11.1 pelo Android Studio ou gere um wrapper com `gradle wrapper --gradle-version 8.11.1`. Não há binários de terceiros adicionais neste projeto.

## O que realmente é testado?

1. Lista IDs `CameraManager.cameraIdList`, direção `LENS_FACING`, hardware level e FPS anunciados.
2. Consulta `android.hardware.camera.concurrent`, que pode ser anunciado mesmo em versões anteriores à API 30; essa checagem **não é prova suficiente** isoladamente.
3. Tenta abrir **traseira primeiro e frontal depois**, mantendo a primeira aberta. Somente após ambas abertas configura as duas sessões.
4. Inicializa duas sessões Camera2 com alvos `TextureView` em resolução baixa (preferencialmente 640x480).
5. Durante duas janelas consecutivas de 3 segundos, verifica contagens de `onCaptureCompleted` e `onSurfaceTextureUpdated` em **ambas**.
6. Se houver erros, timeout, desconexão, sessão rejeitada ou ausência de frames, relata **NÃO COMPROVADO** com motivo. Não é uma sentença universal de impossibilidade: outras combinações de formatos/tamanhos podem merecer testes.

**Resultado positivo:** significa **prévia simultânea viável no teste**, e **não** significa que o S9+ consiga gravar 2 streams com encoders ou transmiti-los com áudio. Essa é a próxima etapa.

## Diagnosticando pelo ADB

```bash
adb devices
adb logcat -s HaliCam
```

Os logs podem ser compartilhados pelo próprio aplicativo sem usar o terminal.

## Estrutura de evolução (módulos previstos)

```text
HaliCam/
  app/                        # Entrada do aplicativo, telas
  camera-core/                # Camera2, inventário, dispositivos, prévias
  recording/                  # MediaCodec, AudioRecord, MediaMuxer
  streaming/                  # WebRTC / transporte de vídeo para o PC
  remote-control/             # Comandos locais e WebSocket autenticado
  desktop-receiver/           # Aplicativo de apoio no computador
  docs/                       # Testes, protocolos, compatibilidade
```

Os diretórios de expansão são **propostos**; não existem nesta versão mínima. Antes de habilitar câmera dupla como recurso comercial, testar estabilidade ≥20 min, transição para gravação, bitrates, consumo, temperatura, sync áudio/vídeo, perda de quadros e desconexão da câmera.

## Roadmap

- [x] Diagnóstico Camera2 para API 29 com tentativa de abrir frente+traseira.
- [x] Relatório de dispositivos, limites, sessões e frames.
- [ ] Validar resultado no Galaxy S9+ físico.
- [ ] Gravação de **uma câmera** em MP4 H.264 + áudio AAC.
- [ ] Controles manuais suportados: foco, exposição, FPS, zoom, flash.
- [ ] Transmissão de **uma câmera** por Wi-Fi ao OBS; depois USB com receptor no PC.
- [ ] Se o teste for positivo, avaliar gravação simultânea com dois codificadores.
- [ ] Layouts Picture-in-Picture, split-screen, vídeo vertical/horizontal.
- [ ] Interface de produção em Jetpack Compose e controle remoto.

## Documentação de referência

- CameraManager: https://developer.android.com/reference/android/hardware/camera2/CameraManager
- Capture sessions: https://developer.android.com/media/camera/camera2/capture-sessions-requests
- Camera API Multi-camera: https://developer.android.com/media/camera/camera2/multi-camera
- OBS Browser Source: https://obsproject.com/kb/browser-source

## Documentação de engenharia

- [Arquitetura e decisões técnicas](docs/ARCHITECTURE.md)
- [Plano de testes e critérios de aprovação](docs/TEST_PLAN.md)
