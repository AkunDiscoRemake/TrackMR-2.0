# TRACKMR2.0 · MR-first alpha06

**Shell espacial Android para celular com lentes Cardboard. Abre diretamente no renderer XR e solicita MR real, sem launcher/menu 2D intermediário.** Não é um sistema pronto equivalente a um headset dedicado.

O projeto preserva o SDK Cardboard, panoramas e ícone do repositório. Não usa recursos proprietários do Quest. Autorizações de câmera/captura, seletor de arquivo, leitor QR e instalação/configurações continuam sendo telas do Android: retire o visor para operá-las com segurança.

## Câmera e mãos na alpha06

**[Baixar alpha06](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36284687600/artifacts/10920341568)** · **[Build + inferência Android aprovados](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36284687600)** · [Correções, evidência e limites](docs/ALPHA06-HANDS.md).

Após o relato de câmera invertida e mãos sem funcionar na alpha05: separação das transformações OES/YUV, entrada Bitmap com ownership correto, autoteste nativo no aparelho e diagnóstico espacial legível. Primeira abertura desta revisão restaura **mãos/esqueleto ligados, Camera2 + CPU, sem ARCore**; 6DoF continua opcional em Tracking.

O tracker de produção passou em teste Android com MediaPipe ARM64 real via tradução: **24 frames YUV com mãos nas quatro rotações + 4 frames pretos sem mãos**. Isso é mais que compilação/teste de buffer, mas **não substitui teste da câmera e interação no celular do usuário**. Não houve benchmark físico.

Hand Landmarker oficial embutido (7.819.105 bytes), hash fixado e comparação com o asset dentro do APK. Aponte com a mão, faça pinça e segure para arrastar. **Toque físico na tela não seleciona o shell XR.** Não é necessária importação do modelo. [Gestos e input contínuo introduzidos na alpha04](docs/ALPHA04-HANDS.md).

## Revisão anterior após feedback do aparelho

Veja [ALPHA03: mãos, MR, nitidez, UI e Shizuku](docs/ALPHA03.md). Biblioteca em grade com ícones reais, dock compacto paginado, texto com proporção corrigida, orientação da inferência e overlay visível de mãos, MR preenchendo as viewports e novo caminho de **um app Android em janela curva com input Shizuku**. Ainda requer validação no celular; não é garantia de que todos os problemas físicos foram resolvidos.

## O que está implementado

| Área | Implementação e limites |
|---|---|
| Cardboard | API oficial C/C++, projeção e viewport de cada olho, IPD/perfil, distorção, orientação prevista, QR e recenter. **Não é apenas tela dividida.** |
| MR na abertura | Camera2 por padrão na alpha06 para isolar mãos. ARCore opcional: câmera real, pose 6DoF, planos, luz e até oito âncoras da sessão. Camera2: passthrough real mono, explicitamente **sem SLAM/6DoF**. Câmera negada/indisponível → espaço espacial neutro, nunca panorama apresentado como MR. VR continua selecionável. |
| Dock e janelas | 14 destinos com ícones e rótulos no hover; até cinco janelas espaciais, mover, redimensionar por duas mãos, minimizar/reabrir, fechar, fixar, girar, maximizar/restaurar, snap e layout salvo. Layout relativo à sessão não é âncora física persistente. |
| Mãos | MediaPipe, até duas mãos/21 landmarks cada, CPU por padrão e GPU opcional com fallback CPU, worker exclusivo, um frame em voo, associação temporal, filtros e gestos independentes da UI. Profundidade relativa **não é posição métrica**. Ponteiro com extrapolação limitada; gestos não usam amostras previstas. |
| Profundidade | Oclusão opcional dos objetos procedurais usando ARCore Depth, somente se suportada; mapeamento UV próprio e expiração de amostra. Não é malha de sala, oclusão das mãos/UI nem calibração validada em hardware. |
| Apps/loja | Apps instalados, pesquisa, recentes locais, favoritos/fixados, detalhes, permissões e desinstalação pelo Android. Catálogo local com três jogos e links oficiais. **Sem downloader, instalador/update verificado ou marketplace remoto.** |
| Apps 2D/captura | MediaProjection autorizado → uma superfície espacial; resize do conteúdo no Android 14+. **Sem injeção de toque ou múltiplos apps independentes.** Shizuku agora tem caminho separado de display de app + toque/scroll/voltar/texto e curvatura; **um app ativo, experimental por OEM**, sem múltiplos apps simultâneos. |
| Browser | WebView num display privado do próprio app, janela espacial, HTTPS, toque pelo ponteiro, rolagem e teclado espacial manual. **Uma página/superfície ativa; não é um browser WebXR.** Compartilha o transporte de textura com captura, sem uso simultâneo. |
| IA | Perguntas/respostas na UI espacial; MediaPipe LLM local, modelo importado explicitamente. Sem pesos incluídos, sem nuvem, GGUF não suportado. Custo de RAM/GPU depende do modelo. |
| Áudio/captura de imagem | Sons originais de seleção opt-in com pan estéreo/atenuação, **não HRTF**. Screenshot estéreo por PixelCopy com segunda confirmação, inclui MR visível e salva em Pictures/TrackMR. |
| Performance | Política térmica/bateria/cadência/resolução; câmera desligada em temperatura crítica. Diagnósticos CPU, inferência, pré-processamento, filtros, RAM e callbacks; timer GPU assíncrono quando a extensão existe. NN de qualidade opcional/importada, sem ganho demonstrado. |
| OpenXR | Módulo cliente independente com sessão GLES, swapchains estéreo, espaços, actions/poses/háptica, EXT hands, eventos e submissão de frames. Companion consulta Broker e abre o cliente. **Exige runtime externo: nenhum APK aqui é Monado/runtime/compositor. A sessão OpenXR ainda renderiza um alvo de integração e pontos rastreados, não todo o shell.** |
| Conteúdo/API | Dois panoramas originais e três minijogos procedurais preservados; contratos Dev API v1 e exemplo, não ABI de plugins. |

**Build aprovado não comprova funcionamento em visor, latência, conforto, economia de bateria ou certificação OpenXR.** Não houve teste físico nesta entrega. As funcionalidades acima têm implementação em código, com cobertura automatizada descrita em [TESTING](docs/TESTING.md); veja as pendências no [roadmap](docs/ROADMAP.md).

## Compilar / baixar

Entrega anterior alpha02 (histórico): [APKs e proveniência](docs/DELIVERY-alpha02.md). **Alpha03: [baixar APKs](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36279394546/artifacts/10917828874) · [build aprovado](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36279394546) · [notas e validação](docs/ALPHA03.md).**

No GitHub: **Actions → Android • TrackMR 2.0 → execução verde → TrackMR-2.0-debug-arm64**.

- `TrackMR-2.0-alpha06-arm64.apk` — shell MR/VR/Cardboard e cliente OpenXR opcional;
- `TrackMR-Runtime-Companion-alpha06-arm64.apk` — diagnóstico/Broker e acesso ao cliente;
- `TrackMR-Dev-API-v1.jar`, `SHA256SUMS.txt`, `SOURCE_REVISION.txt`.

APKs **debug**, não release assinada para loja. Artefatos expiram em 14 dias. O workflow também publica relatórios JVM/lint, compila shaders ESSL e executa testes nativos. Consulte a execução da revisão desejada; a existência do YAML não prova aprovação.

### Local

JDK 17, SDK 35, NDK `27.2.12479018`, CMake `3.22.1`, Python 3, curl, git, glslangValidator e acesso aos repositórios Google/Maven:

```sh
./scripts/bootstrap.sh
# local.properties: sdk.dir=/caminho/para/Android/Sdk
./scripts/ci-build.sh
python3 scripts/check_shaders.py
g++ -std=c++17 -Wall -Wextra -Werror tests/native_math_test.cpp -o /tmp/trackmr-math && /tmp/trackmr-math
g++ -std=c++17 -Wall -Wextra -Werror tests/openxr_protocol_test.cpp -o /tmp/trackmr-xr && /tmp/trackmr-xr
```

Android **10+**, **arm64-v8a**, GLES 3; giroscópio para orientação Cardboard. ARCore/Depth e runtimes têm requisitos próprios. Não há APK x86. Dependências/pesos/builds não são versionados.

## Primeira utilização

1. Abra o app: aparece a superfície XR e é solicitada autorização de câmera. Com consentimento, MR e mãos são iniciados automaticamente quando disponíveis. Sem câmera, a UI informa o espaço seguro e continua espacial.
2. Retire o visor para **Configurações → QR das lentes**. Use o perfil correto; o fallback Cardboard V1 não serve para todas as lentes. Deixe a câmera traseira desobstruída.
3. Mostre indicador/polegar separados; aponte e selecione com pinça. Segurar a pinça arrasta conteúdo. Toque físico na tela não seleciona; volume + / botão A são recuperação opcional. Palma aberta alterna o dock. Janela em MOVER + pinça arrasta; duas pinças escalam/giram. Menu/Voltar sai do jogo ou fecha a janela focada.
4. Tracking mostra backend/planos/âncoras, permite religar câmera/mãos e habilitar depth quando suportado. Sistema permite desligar câmera explicitamente; não religa apenas por voltar do background.
5. VR/ambientes selecionam os dois panoramas do projeto; desligue mãos se não quiser manter câmera no modo VR.
6. Para apps com input: inicie Shizuku, conecte em Captura, depois Biblioteca → app → Abrir janela Shizuku. Browser oferece URL HTTPS/teclado; Captura pede consentimento do Android. Ao fechar/minimizar a janela ativa, a fonte é encerrada. Apps lançados pela biblioteca abrem **fora do VR**.
7. IA está em Home, exige modelo MediaPipe LLM confiável importado. Use modelos pequenos e não pressuponha que o telefone suporte inferência junto com MR.
8. Sistema → Sessão OpenXR real abre um cliente separado, suspendendo o shell/câmera. Leia [OPENXR](docs/OPENXR.md): loader/broker não substituem um runtime compatível.

**Segurança:** use sentado, área livre e sessões curtas. Não há guardian físico confiável. Pare em caso de enjoo, calor ou desconforto; passthrough de câmera não torna seguro caminhar de visor.

## Documentação

- [Arquitetura e ownership](docs/ARCHITECTURE.md)
- [Desempenho e limites das medições](docs/PERFORMANCE.md)
- [OpenXR / Monado / Broker / WebXR](docs/OPENXR.md)
- [Testes e validação física pendente](docs/TESTING.md)
- [Roadmap: o que ainda falta](docs/ROADMAP.md)
- [Dev API](docs/DEV_API.md)
- [Pesquisa de projetos OSS](docs/research/DECISIONS.md) · [Licenças/atribuições](THIRD_PARTY.md)

Privacidade: sem analytics/telemetria remota, upload automático de câmera/landmarks/conversas ou gravação de vídeo. Browser e links acessam rede conforme navegação do usuário; não recebem câmera/microfone automaticamente. Screenshot só após confirmação explícita. Shizuku não contorna DRM/FLAG_SECURE. Código novo Apache-2.0; imagens e dependências têm direitos próprios.
