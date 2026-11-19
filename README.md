# HaliCam

Câmera e webcam profissional para Android com **Dual Capture**: a câmera traseira ocupa a tela e a frontal aparece numa janela flutuante que você move, redimensiona e reposiciona **durante a gravação**, e o vídeo final (MP4) sai composto exatamente como na tela.

**Aparelho de referência:** Samsung Galaxy S9+ (SM-G965) · Android 10 (API 29). Mínimo: Android 8.0 (API 26).

## Modos

| Modo | O que faz |
|---|---|
| **Vídeo** | Uma câmera (traseira ou frontal), H.264 + AAC, 720p/1080p/4K conforme o hardware. |
| **Dual** | Traseira + frontal ao mesmo tempo, compostas em GPU num único MP4. Janela PiP arrastável, redimensionável (pinça), quadrada / retangular / circular, espelhável, ocultável, e troca de câmera principal (toque duplo na janela). Opcional: salvar também os originais de cada câmera. |
| **Foto** | JPEG em resolução total (4:3) com flash automático/ligado/desligado e orientação EXIF. |
| **Webcam** | Publica a composição (traseira, frontal ou dual) na rede Wi-Fi local para o **OBS Studio**, com painel de controle remoto no navegador. |

### Controles

- **Barra superior:** resolução · FPS · proporção (toque para alternar), flash/lanterna, opções da janela PiP, configurações. Abaixo: bateria, armazenamento livre com minutos restantes estimados e temperatura.
- **Barra inferior:** miniatura da última mídia, botão de gravação (branco/vermelho em repouso, quadrado vermelho gravando, laranja pausado, giro ao finalizar), alternância de câmera. Durante a gravação: cronômetro REC, pausar/continuar e trocar a câmera principal (no Dual).
- **Gestos na prévia:** pinça = zoom; toque = foco/medição no ponto; deslizar na horizontal = trocar de modo.
- **Configurações:** resolução, FPS, proporção (9:16, 16:9, 1:1), estabilização, foco manual, exposição manual (ISO/obturador), compensação de exposição, balanço de branco, zoom, espelhamento, microfone, qualidade de áudio, resolução da webcam e o diagnóstico de câmeras original. **Só aparecem como ativas as opções que o hardware anuncia**; as demais ficam desabilitadas com o motivo.

### Webcam no OBS

1. Celular e computador na mesma rede Wi-Fi; abra o modo **WEBCAM** (o endereço aparece no cartão, toque para copiar).
2. No OBS: **Fonte → Navegador** com `http://IP:8080/obs?k=TOKEN`, ou **Fonte de mídia** (desmarque "Arquivo local") com `http://IP:8080/stream.mjpg?k=TOKEN`.
3. `http://IP:8080/?k=TOKEN` abre o painel remoto: prévia, gravar/parar, pausar, traseira/frontal/dual e trocar principal.

O token de 6 dígitos é gerado no aparelho e exigido em todas as rotas (sem ele: HTTP 403). O servidor só escuta na rede local.

## Arquitetura

```
ui/            CameraActivity, PipOverlayView (gestos), Sheet (configurações), Views (botão REC, seletor de modos), Icons
core/          CaptureController (orquestra tudo), Model (configurações, PiP, proporções), DeviceStatus
camera/        CameraCatalog (capacidades reais), CameraUnit (sessão/controles Camera2), CameraRig (simples ou par dual com fallback)
gl/            Renderer (composição OpenGL ES 2.0), EglCore
record/        VideoEncoder (MediaCodec H.264, superfície de entrada), AudioEncoder (AudioRecord→AAC), MuxerSink, RecordingSession, MediaOutput
stream/        WebcamServer (HTTP/MJPEG + API de controle)
DiagnosticActivity + DualCameraProbe   diagnóstico original, acessível em Configurações
```

Fluxo: `Camera2 → SurfaceTexture (OES) → Renderer (GPU) → { tela · encoder H.264 · leitura RGBA para webcam }`.
A mesma composição alimenta a prévia, a gravação e a webcam, por isso **o que se vê é o que se grava**. A câmera principal dita o ritmo dos quadros; a outra contribui com o quadro mais recente. O áudio e o vídeo usam o mesmo relógio monotônico, e a pausa remove o intervalo dos timestamps.

## Compatibilidade e limitações do Galaxy S9+ (medidas no aparelho)

- **Dual funciona somente com o par traseira `0` + frontal `2`.** A frontal `1` (selfie padrão) fecha a traseira quando aberta junto (o HAL despeja a outra câmera). O app testa os pares automaticamente, valida que **as duas** entregam quadros e memoriza o par que funcionou. Se nenhum funcionar, mostra o motivo e volta para o modo Vídeo; os demais modos continuam disponíveis.
- **Dual limitado a 1080p.** Traseira em 1920×1080 + frontal em 1280×720; se a sessão for recusada, tenta 1280×720 + 640×480. Quando a frontal vira principal, o fundo usa o stream de 720p ampliado.
- **Sem 60 fps:** as câmeras anunciam no máximo 30 fps para o Camera2; o app oferece só 24 e 30.
- **Zoom apenas digital:** o Camera2 expõe só uma câmera traseira a apps de terceiros; a teleobjetiva não fica acessível.
- **Sincronismo entre câmeras:** por "último quadro disponível", com defasagem máxima de ~1 quadro (33 ms a 30 fps). Não há sincronização por hardware entre os sensores.
- **Orientação:** a interface fica em retrato; a orientação do arquivo é a do aparelho no início da gravação (16:9 com o celular em pé é um recorte; gire o celular para 16:9 horizontal).
- **Webcam:** MJPEG por Wi-Fi (sem áudio no stream, sem USB/UVC). O servidor para quando o app sai da tela, porque ainda não roda como serviço em primeiro plano.
- **Ocultar a janela PiP** a remove da prévia e do vídeo, mas a frontal continua capturando para voltar na hora.
- **Temperatura:** o app avisa quando o sistema reporta aquecimento severo e encerra a gravação em nível crítico.

### Validado no aparelho (19/11/2026)

Dual ao vivo; gravação Dual 1080p30 com a janela arrastada durante a gravação (o movimento aparece no MP4); pausa/retomada (áudio e vídeo alinhados); três arquivos simultâneos (composição + originais); foto JPEG; webcam MJPEG a ~25 fps em 720p, token (403 sem token) e gravação remota pela API.

**Ainda não validado no aparelho:** gravação com o celular na horizontal, foco/exposição manuais, gravação 4K e uso prolongado (≥ 20 min: temperatura, bateria, perda de quadros).

## Compilar e instalar

Requisitos: JDK 17, Android SDK Platform 35, Gradle 8.11.1 (AGP 8.9.2, Kotlin 2.1.20). Sem dependências externas além do Android SDK.

```bash
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s HaliCam
```

Se a cópia não tiver `gradle-wrapper.jar`, gere-o com `gradle wrapper --gradle-version 8.11.1`.

## Roadmap

- [x] Diagnóstico Camera2 de câmera dupla (mantido em Configurações → Diagnóstico).
- [x] Interface de câmera profissional + ícone adaptativo.
- [x] Dual Capture com janela flutuante e composição gravada em MP4.
- [x] Gravação H.264 + AAC com pausa; originais separados; foto.
- [x] Controles avançados filtrados pelas capacidades reais.
- [x] Webcam Wi-Fi para OBS + controle remoto.
- [ ] Webcam como serviço em primeiro plano (continuar com a tela desligada) e áudio no stream.
- [ ] Transporte por USB (tethering/ADB) e WebRTC com menor latência.
- [ ] Testes de estabilidade de longa duração e telemetria térmica no relatório.
- [ ] Interface rotacionável em paisagem.
