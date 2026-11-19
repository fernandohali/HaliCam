# Plano de validação — Galaxy S9+ Android 10

## Fase A — Diagnóstico de hardware (versão contida neste ZIP)

1. Verificar `Build.MODEL`, API 29 e autorização de câmera.
2. Listar `cameraIdList` e `LENS_FACING`; não pressupor IDs 0 e 1.
3. Consultar `android.hardware.camera.concurrent`.
4. Abrir câmera traseira e em seguida frontal, antes de configurar sessões.
5. Construir sessões de prévia, em princípio 640x480.
6. Observar resultados reais de captura e atualização das duas TextureViews por duas janelas consecutivas de 3 segundos.
7. Exportar relatório completo.

### Interpretação

- `ERROR_MAX_CAMERAS_IN_USE`: hardware/HAL indisponível para essa combinação nesse cenário.
- `onDisconnected`: câmera foi interrompida, possivelmente por contenção da HAL.
- `onConfigureFailed`: combinação de sessões recusada; tentar tamanhos/formatos alternativos em teste posterior.
- Sem frames de uma câmera: câmera dupla **não demonstrada**, mesmo se ambas abriram.
- Frames de ambas nas duas janelas: sucesso no **teste de prévia dupla**, não na gravação.
- Se o teste falhar, testar cada câmera individualmente antes de classificar o aparelho como incompatível.

## Fase B — Gravação de uma câmera

- 1280x720 e 1920x1080 (perfil inicialmente 30 fps, sujeito a hardware).
- H.264/AAC com MediaCodec/AudioRecord/MediaMuxer.
- Testar 3 gravações contínuas, 5, 20 e 30 minutos.
- Validar sincronismo labial e integridade do MP4 após parar normalmente.
- Exportar vídeo para galeria via MediaStore e compartilhar.

## Fase C — Gravação dupla (somente se a Fase A permitir)

- Testar duas câmeras com saída a encoders, inicialmente 640x480 ou 720p de acordo com a HAL.
- Não abrir novos streams de tamanho alto antes de validar a combinação de saída de vídeo.
- Conferir `MediaCodecList` / capacidades, taxa sustentada e limites de encoder simultâneo.
- Gravar dois MP4 separados primeiro; sincronizar por timestamps, não apenas contagem de frames.
- Testar PiP em composição depois de comprovar que a captura dupla é estável.

## Fase D — Webcam/OBS

- Primeiro transmitir uma câmera via Wi-Fi local.
- Medir atraso ponta-a-ponta, jitter, perda de quadros e sincronismo de áudio.
- Validar receptor OBS (Browser Source com WebRTC ou receptor nativo).
- Testar rede via USB tethering ou receptor específico; evitar depender do ADB em produção.
- Exigir consentimento visível quando câmera/microfone estiverem ativos.

## Se a câmera dupla não for possível

O aplicativo continua viável com gravação de uma câmera, troca frontal/traseira, controles, webcam OBS. Para gravar frente e verso **ao mesmo tempo**, usar uma segunda câmera física (outro telefone, webcam ou câmera USB validada) e combinar no OBS, ou avaliar hardware Android mais novo com suporte concorrente confirmado. Alternar rapidamente frontal e traseira **não produz gravação simultânea**.
