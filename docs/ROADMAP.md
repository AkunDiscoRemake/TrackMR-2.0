# Roadmap honesto

## Alpha atual

Shell Cardboard real; ambientes existentes; controles por olhar+gatilho; três minijogos procedurais; ARCore opcional; caminho MediaPipe com backpressure e filtros; captura em painel; autorização/serviço Shizuku; chat local com modelo importado; governor térmico; consultor neural opcional; contratos Dev API; diagnóstico de Broker/OpenXR; build de dois APKs no Actions.

## P0 — validar antes de expandir

- Testar aparelhos e perfis de lente, shader/hand mapping, orientação e retorno de background.
- Medir latência real, GPU, bateria e temperatura. Ajustar filtros por dados, não por quantidade.
- Completar testes de lifecycle, QR com ARCore usando câmera, captura Android 14 e perda de EGL.
- Melhorar transição/relocalização 3DoF ↔ 6DoF e espaço de referência.

## P1 — funcionalidades solicitadas ainda incompletas

- Monado runtime APK de verdade: driver HMD, ownership câmera/display, compositor, swapchains e ações; integração com Broker oficial e hello_xr.
- Port Wolvic para o runtime, engine atualizada, WebXR real, navegação/teclado em VR.
- Displays de apps independentes via Shizuku com launch, Surface, auto-resize e injeção de input por display; revisão por versão Android/OEM e política de segurança.
- API de jogos: host Android, transporte de cena, exemplo APK e SDK versionado.
- Tracking métrico de duas mãos, matching de identidade, camera calibration, profundidade/oclusão e gestures robustos. Overlay 2D não equivale a isso.
- Loja com manifesto assinado, compatibilidade/capabilities, download verificado, instalador PackageInstaller com confirmação e revisão de conteúdo.

## P2 — expansão depois de medir

- glTF/KTX2 e modelos de jogo mais complexos; áudio espacial; acessibilidade e interação por controles Bluetooth.
- Vídeos 360/180/estéreo; importação de panoramas pelo Storage Access Framework.
- STT/TTS estritamente offline para IA; gestão de memória e suspensão da inferência sob carga VR.
- Dataset real, treino e validação por aparelho do governador neural; entregar pesos somente com evidência de benefício e licença.
- Ancoragem AR, passthrough com UI de privacidade, teclado virtual, múltiplas janelas e persistência.
- Render pacing/Swappy, late latching/timewarp quando arquitetura permitir, qualidade/foveation por capacidade.
- Releases assinadas, atualização segura, CI com aparelhos físicos.

Adicionar bibliotecas sem utilizá-las aumenta APK, superfície de ataque, consumo e conflitos. A lista em THIRD_PARTY é um mapa de integrações, não a alegação de que dezenas de projetos já estão embarcados.
