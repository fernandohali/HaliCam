# Arquitetura alvo do HaliCam

## Escopo

- Android nativo Kotlin (S9+ Android 10 como primeiro alvo; compatível com Android mais recentes quando possível).
- Gravação local para YouTube (16:9) e Instagram (9:16).
- Prévia e controles locais, modo câmera frontal, modo câmera traseira, eventual modo dual.
- Streaming de uma ou duas câmeras ao computador (somente se o teste provar a capacidade).
- Controle remoto pelo PC, com autenticação e operação restrita à rede local.

## Decisões de engenharia

1. **Camera2 como camada de hardware**. É necessário testar e, mais tarde, ajustar streams, controles, foco/ISO/exposição, resultados e sessão para aparelhos antigos. CameraX poderá ser usado em telas mais simples.
2. **Capability-first**. UI habilita recursos por capability testado (`singlePreview`, `dualPreview`, `singleRecord`, `dualRecord`, `wifiStream`, `usbStream`) em vez de inferir pelo modelo ou versão do SO.
3. **Independência entre gravar e transmitir**. O app pode gravar MP4 local enquanto um módulo separado distribui frames codificados; desenho final dependerá do número de encoders e de superfícies suportadas.
4. **Encoder de vídeo único para modo simples**. Primeiro gravar H.264 em MP4, microfone AAC, timestamp monotônico e fechamento correto do muxer.
5. **Dual não é simplesmente abrir duas câmeras**. Aprovado somente após duas sessões entregarem frames; gravação dual requer prova adicional com duas superfícies de encoder, sincronismo e estabilidade.
6. **Modo OBS**. Primeiro suporte Wi-Fi local usando WebRTC com sessão autenticada e receptor no PC. Receptor pode fornecer visualização ao OBS via Browser Source ou outro tipo de fonte que implemente corretamente a recepção e o áudio. Não confundir com webcam USB UVC nativa: Android 10 não fornece isso como serviço padrão.
7. **USB**. Etapa posterior: avaliar transporte IP por USB tethering ou um canal dedicado no host. ADB forwarding é útil para testes, mas não será requisito permanente para usuários comuns.
8. **Segurança**. Solicitar permissões sob demanda; pareamento por QR/PIN; token de sessão; não publicar streaming na Internet sem autorização explícita.

## Componentes planejados

### Android

- `CameraInventory`: IDs, LENS_FACING, CameraCharacteristics, resoluções, níveis.
- `ConcurrentCameraProbe`: abertura de devices, duas sessões e contadores de frames.
- `CameraController`: estado do hardware e transições de modo.
- `Recorder`: vídeo + áudio codificados e MediaMuxer.
- `CaptureSettings`: perfis (YouTube 16:9, Instagram 9:16), resoluções e fps permitidos.
- `StreamPublisher`: transporte em rede local para PC.
- `RemoteControlServer`: comandos `start`, `stop`, `switchCamera`, `setSettings` e telemetria.

### Computador

- `Receiver`: decodificar e exibir streaming, com endpoint compatível com OBS.
- `ControlPanel`: iniciar/parar e ajustar parâmetros.
- `SceneProfiles`: composição horizontal e vertical, PiP somente se houver 2 fontes simultâneas.

## Máquina de estados

```
IDLE -> PERMISSION_GRANTED -> CAMERA_READY
CAMERA_READY -> SINGLE_PREVIEW -> SINGLE_RECORDING
CAMERA_READY -> DUAL_PROBING -> DUAL_PREVIEW (se suportado)
DUAL_PREVIEW -> DUAL_RECORDING (somente após novo teste)
SINGLE_PREVIEW -> STREAMING
ALL_STATES -> ERROR -> CLEANUP -> IDLE
```

## Observabilidade

- Eventos de erros da HAL e API (código exato).
- Taxa de captura / prévia por câmera; quadros perdidos.
- Latência de rede e áudio; utilização do armazenamento.
- Bateria, estado térmico, espaço livre, bitrate efetivo.
- Log exportável sem incluir quadros de pessoas nem dados de redes locais.
