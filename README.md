# TrackMR 2.0

**Shell VR Android para celular com lentes Cardboard — alpha, não um substituto pronto do Quest.**
Interface espacial inspirada em painéis/dock de headsets modernos, identidade própria, dois panoramas e ícone originais deste repositório.

## Estado desta entrega

| Recurso | Implementação nesta alpha |
|---|---|
| Cardboard | SDK oficial C/C++; projeções por olho, IPD do perfil, mesh de distorção, previsão de orientação, QR e recenter. **Não é apenas tela dividida.** |
| Ambientes | Os dois panoramas existentes: Horizonte dourado e Noite violeta. Fotos 360° são 3DoF, mesmo quando o tracking de objetos é 6DoF. |
| UI | Launcher Android + painel espacial plano/curvo, seleção por olhar + toque/volume/botão A. Não copia recursos proprietários da Meta. |
| Jogos | 3 minijogos nativos: Órbita, Reflexo, Constelação. Orbes e materiais procedurais próprios, sem modelos baixados. |
| ARCore | Sessão opcional, pose 6DoF de objetos, câmera compartilhada com MediaPipe, fallback 3DoF. Exige suporte de hardware. |
| Mãos | MediaPipe GPU com fallback CPU, 1 mão, worker exclusivo, um frame em voo, downsampling, One Euro, rejeição de saltos, pinça com histerese; Kalman alternativo testado no core. **Esqueleto fino 2D da câmera, não reconstrução estéreo métrica.** |
| Apps 2D | Captura autorizada MediaProjection em painel plano/curvo. Resize da captura no Android 14+. **Sem injeção de toque e sem múltiplos apps independentes.** |
| Shizuku | Permissão + user service AIDL real; comandos limitados de launch/resize em display virtual existente. Criação/gestão independente de displays ainda pendente. |
| Loja | Catálogo local de jogos incluídos e links oficiais, lista de apps instalados. Sem backend, compras ou instalação silenciosa. |
| IA local | Chat por texto com MediaPipe LLM Inference. Importação manual de modelo compatível, sem nuvem. Pesos não incluídos; depende de RAM/GPU/modelo. |
| Desempenho | Política térmica determinística, resolução adaptativa, frame p95. Consultor TFLite **opcional, experimental, sem pesos treinados incluídos**. |
| API de jogos | Contratos Kotlin v1 e exemplo; não é ABI de plugin nem implementação OpenXR. |
| OpenXR | Segundo APK **Runtime Companion** consulta Broker oficial e testa loader, extensões, instância e sistema. **Monado não está embutido; driver/compositor TrackMR pendentes.** |
| WebXR | Rota para Wolvic (Igalia). **Não há navegador WebXR embarcado nesta alpha.** É necessário um port para o runtime/headset. |

Os itens implementados ainda exigem validação em aparelho. Não há números medidos de motion-to-photon, garantia de FPS ou certificação OpenXR.

## Compilar / obter APKs

No GitHub, abra **Actions → Android • TrackMR 2.0**. O workflow roda em push na main e nas branches `arena/**`, em PRs e manualmente. Quando aprovado, baixe `TrackMR-2.0-debug-arm64`:

- `TrackMR-2.0-alpha01-arm64.apk` — launcher e Cardboard;
- `TrackMR-Runtime-Companion-alpha01-arm64.apk` — diagnóstico OpenXR;
- `TrackMR-Dev-API-v1.jar` — contratos e exemplo da API experimental;
- checksums SHA-256 e revisão do código.

São APKs **debug**, não releases assinadas para loja. Nenhum certificado de produção está no repositório. O workflow também publica relatórios de testes/lint. Um arquivo de workflow não prova que o build passou; confira o resultado da execução.

### Local

Requisitos: JDK 17, Android SDK 35, NDK `27.2.12479018`, CMake `3.22.1`, Python 3, curl, git e acesso aos repositórios Maven/Google.

```sh
./scripts/bootstrap.sh
# local.properties: sdk.dir=/caminho/para/Android/Sdk
./gradlew :core:test :app:assembleDebug :runtime:assembleDebug :app:lintDebug :runtime:lintDebug
# Testes geométricos, sem SDK Android:
g++ -std=c++17 -Wall -Wextra -Werror tests/native_math_test.cpp -o /tmp/trackmr-test
/tmp/trackmr-test
```

Android **10+**, **arm64-v8a**, OpenGL ES 3, giroscópio. ARCore e captura por aplicativo têm requisitos adicionais. Emulador x86 não está incluído. Bootstrap busca o Cardboard numa revisão fixa e o modelo oficial MediaPipe versão 1. Pesos, builds e dependências não são versionados.

## Primeira utilização

1. Instale o APK principal. Escolha um ambiente; comece com mãos e 6DoF **desligados**.
2. Entre em VR, retire o telefone do visor para **Lentes / QR**, escaneie o QR do seu Cardboard e centralize. O fallback V1 não serve para todas as lentes.
3. Olhe para um cartão e toque no visor ou pressione volume + / botão A. O botão Menu sai do minijogo. Tire o visor para os controles de operação e permissões.
4. Ative ARCore e mãos em Ajustes, conceda câmera e teste em um local iluminado. Não cubra a câmera com o visor. Mãos são experimentais, sem precisão métrica.
5. Em Apps, use Compartilhar em VR. No Android 14+, prefira capturar um único app. Captura de tela inteira pode mostrar recursão; apps protegidos ficam pretos. A captura **não controla** o app.
6. IA: importe um modelo compatível com MediaPipe LLM Inference. **GGUF não é suportado**. Não execute LLM junto com VR em aparelhos limitados.
7. OpenXR: instale o companion, Broker oficial e um build confiável do Monado. Veja [OPENXR.md](docs/OPENXR.md) antes de esperar compatibilidade com jogos OpenXR.

Use sentado, área livre, sessões curtas. Pare em caso de enjoo, aquecimento ou desconforto.

## Arquitetura e próximos passos

- [Arquitetura e fronteiras de integração](docs/ARCHITECTURE.md)
- [Performance, tracking e medições](docs/PERFORMANCE.md)
- [OpenXR / Monado / Broker / Wolvic](docs/OPENXR.md)
- [API para desenvolvedores](docs/DEV_API.md)
- [Plano de validação em hardware](docs/TESTING.md)
- [Roadmap e recursos ainda não implementados](docs/ROADMAP.md)
- [Projetos open source e licenças](THIRD_PARTY.md)

Privacidade: sem analytics, conta, upload de câmera/conversa ou gravação de frames. Links externos usam seu navegador. Shizuku só é acionado por autorização explícita; conteúdo protegido não é contornado. Código novo sob Apache-2.0; imagens originais e componentes externos têm direitos separados (ver THIRD_PARTY).
