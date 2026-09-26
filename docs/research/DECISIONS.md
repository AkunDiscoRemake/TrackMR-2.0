# Avaliação de fontes — 26/09/2026

Metadados consultados diretamente pela API GitHub, não inferidos de estrelas: [snapshot](github-evaluation-2026-09-26.json). `pushed_at` é atividade do repositório, não garantia de manutenção ou segurança. Licença indicada pelo GitHub é triagem; prevalecem LICENSE/NOTICE por arquivo da revisão utilizada. Nenhum benchmark Android foi inventado.

| Projeto | Licença / atividade consultada | Android / ARM64 | Dependências e custo | Decisão para este código |
|---|---|---|---|---|
| Cardboard | API GitHub NOASSERTION; LICENSE lido: Apache-2.0 no SDK utilizado, Unity separado. Push 31/08/26 | SDK oficial Android, arm64 compilado pelo CI | JNI, sensores, GLES, QR/GMS, protobuf | Manter v1.30 fixa; Unity/Vulkan desativados; verificar perfil físico de lentes |
| MediaPipe | Apache-2.0; 25/09/26 | AAR Android/arm64; compilação CI | Graphs, TFLite, delegado GPU, pesos; bitmap é cópia CPU explícita | Manter; GPU→CPU em erro; pipeline temporal fora da UI; não adicionar segunda CNN redundante |
| OpenXR-SDK | Apache-2.0; 21/09/26 | Loader Android com prefab arm64 | EGL/GLES, runtime externo | Manter loader fixo 1.1.36, validar extensões dinamicamente |
| OpenXR-SDK-Source / hello_xr | Apache-2.0 e avisos por arquivo; 21/09/26 | Samples Android/arm64 | NDK, gráficos, runtime | Referência normativa de ciclo de sessão e teste de integração; sample não fornece compositor |
| Monado canônico | BSL-1.0 predominante; fonte no GitLab freedesktop | Build Android existe; o driver/dispositivo selecionado define suporte | Compositor, IPC, drivers, Vulkan/GLES, Android service | Runtime externo compatível, sem APK aleatório; não declarar runtime próprio sem driver e teste físico |
| Monado-ALVR | BSL-1.0; último push consultado 28/12/24 | README declara Android, mas isso não comprova distribuição arm64 Android do caminho ALVR | ALVR/streaming e compositor, adiciona rede, latência e componentes desktop | NÃO integrar por padrão. Não resolve sozinho runtime MR local em telefone |
| OpenCV | Apache-2.0; 25/09/26 | Suporta Android/arm64 via SDK | Binário nativo grande; múltiplos módulos | Não incluir no hot path para operações escalares já cobertas; avaliar calibração offline quando houver dataset |
| OpenXRLab XRAPI | Apache-2.0; 28/06/24 | Projeto anuncia interfaces móveis; ARM64/build reproduzível não verificados aqui | SLAM/visão e dependências com licenças próprias | Não substituir ARCore sem compilar, calibrar IMU/câmera e medir precisão/consumo |
| ORB_SLAM3 | GPL-3.0; 24/07/24 | Port Android necessário; upstream não é SDK Android plug-and-play | OpenCV, Eigen, Pangolin, vocabulário e calibração | Não copiar/linkar sem revisar GPL e orçamento CPU/memória; não cria escala métrica monocular de graça |
| OpenVINS | GPL-3.0; 30/11/25 | C++ portátil não equivale a backend Android validado | Eigen/OpenCV, IMU calibrada/sincronizada; integração de câmera | Não integrar nesta entrega; exige dataset e avaliação de licença/SLAM |
| Wolvic | MPL-2.0 + terceiros; 23/09/26 | Builds Android existem para headsets; não comprova suporte a Cardboard genérico | Gecko/Chromium, backend de dispositivo, teclado, input, build grande | Manter como opção externa; WebView espacial não é anunciado como Wolvic/WebXR |
| Filament | Apache-2.0; 26/09/26 | Android/arm64 | Engine PBR, materiais, tooling glTF; custos de migração | Não manter duas engines simultâneas; renderer GLES pequeno e instanciado basta para dock atual |
| Oboe | Apache-2.0; 21/09/26 | Android nativo/arm64 | AAudio/OpenSL ES, callbacks RT | Candidato quando houver áudio contínuo/HRTF; não necessário para poucos cues curtos |
| monado-phone | BSL-1.0; 19/09/26 | App Android + driver PC; confirmar ABI dos artefatos escolhidos | Linux Monado, UDP/TCP, HEVC/GStreamer, ARCore/MediaPipe | Estudar ownership da câmera; NÃO copiar streaming/descoberta LAN por padrão, pois objetivo é MR local/privada |

### Fontes complementares consultadas

- [Monado-ALVR](https://github.com/alvr-org/Monado-ALVR): fork de integração ALVR, não promessa de MR Android completo.
- [Monado Phone](https://github.com/ttomf/monado-phone): arquitetura cliente/PC e compartilhamento ARCore/MediaPipe.
- [Extensões Android XR](https://developer.android.com/develop/xr/openxr/extensions): extensões e permissões são específicas do runtime Android XR; telefone Android comum não ganha essas extensões ao incluir o loader.
- [Android OpenXR GLES samples](https://github.com/terryky/android_openxr_gles): exemplos voltados a headsets, não base para alegar funcionamento em qualquer telefone. Não foi incorporado código sem revisão de licença por arquivo.

## Critérios de aceitação para nova dependência

Fixar revisão e checksum; revisar licença/transitivos; compilar arm64 com alinhamento necessário; validar suspend/resume/câmera/segurança; medir delta de frame p95, GPU, inferência, memória e temperatura contra baseline no **mesmo aparelho**; rejeitar regressão sem benefício mensurável. Um projeto open source novo não é uma otimização por si só.
