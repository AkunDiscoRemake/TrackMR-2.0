# Alpha03 — correções após teste no celular

Esta revisão responde ao relato de mãos sem funcionar, MR incompleto nas lentes, texto ilegível e ausência de janelas Android reais. **Não pressupõe que o relato esteja resolvido só porque o build passou.** Modelo/Android do aparelho e novo teste continuam necessários.

## Mãos

- Correção da orientação da imagem **antes** da inferência (0/90/180/270), com transformação inversa dos landmarks para o espaço da câmera e depois da viewport. Testes de ida/volta das quatro rotações.
- CPU como padrão inicial; seletor CPU/GPU em Tracking, com fallback de GPU para CPU. Nenhum ganho de velocidade é presumido.
- Esqueleto de até duas mãos visível e alinhado à projeção por olho. É **overlay da câmera**, não uma malha de mão 3D métrica/oclusora.
- Contador de frames processados, quantidade de mãos, erro concreto e pausa térmica explícita.
- Expiração deixa de ser fixa em 150 ms: acompanha custo de processamento, com limite absoluto de 350 ms. Permite enxergar resultados em aparelhos mais lentos, **não transforma inferência lenta em tracking de baixa latência**.
- Expiração usa relógio local da aquisição; timestamp do sensor fica separado para diagnóstico. Buffer Camera2 com espaço para descartar imagens antigas enquanto uma está em processamento.
- Recuperação após encerramentos não desliga mãos silenciosamente; evita restaurar o layout problemático. Desligamento manual/térmico continua respeitado.

## MR e nitidez

- Novo modo **preencher**, padrão: imagem real cobre cada viewport Cardboard, antes da distorção oficial. Evita o retângulo pequeno/áreas vazias da reprojeção óptica anterior.
- **Preencher amplia/remapeia a imagem mono**: pode alterar perspectiva/escala aparente; não cria FOV extra, disparidade estéreo ou calibração olho↔câmera. Não usar para caminhar.
- Sistema → MR alterna preencher/óptico. Depth fica desligado no modo preencher para evitar oclusão usando projeção incompatível.
- Render começa em escala 1.0, perfil Qualidade. Limites térmicos/desempenho ainda podem reduzir a escala. A câmera de preview pode usar tamanho maior que a entrada de inferência, sem elevar o modelo de mãos para essa resolução.

## UI implementada no renderer

- Biblioteca 4×2, oito apps por página, ícones dos apps instalados (até 128 ícones residentes), pesquisa/favoritos/paginação no rodapé.
- Configurações e demais páginas em grade de cartões; títulos em português, janelas iniciais maiores e botões separados.
- Dock compacto de sete ícones por página; **•••** mostra os outros sete destinos, sem remover funções.
- Rasterização de texto considera a proporção física do cartão; word wrap, limites de linha e reticências. Evita esmagar bitmap 2:1 em qualquer retângulo. Atlas continua limitado a 2048²/128 tiles.
- Texto informativo não é escurecido como se fosse ilegível; painéis frontais bloqueiam cliques em controles que estão atrás.
- Disposição antiga é migrada uma vez. Permissões, modelos e preferências de privacidade não são apagados.
- As imagens fornecidas são referência de estrutura visual. Nenhum screenshot/capa/asset da Meta foi embutido como UI falsa.

## App Android em janela curva via Shizuku

Novo caminho separado de MediaProjection:

```
SurfaceTexture do renderer
  ← VirtualDisplay pertencente ao TrackMR, OWN_CONTENT_ONLY (sem espelhar telefone)
  ← Activity do app lançada com am start --display via Shizuku
  ← toque/rolagem/voltar/texto destinados ao mesmo display
```

- Biblioteca → app → **Abrir janela Shizuku**.
- Curva/plana, toque pelo ponteiro/pinça, rolagem com gesto de dois dedos, Voltar, teclado manual e encerramento.
- Curvatura real por tesselação; equação de interseção CPU corresponde à superfície/UV. Testes nativos cobrem tamanhos, coordenadas e misses.
- Resize do display produtor acompanha a proporção da janela, limitado em cadência/dimensões.
- Serviço verifica ID > 0, nome, UID do proprietário, UID do chamador Binder e pacote **a cada operação**. Não injeta input no display principal nem em display de outro app.
- Comandos são argumentos de ProcessBuilder, não strings interpretadas por shell; tempo/fila/saída limitados. Sem bypass de DRM/FLAG_SECURE.
- Display pede DESTROY_CONTENT_ON_REMOVAL (flag AOSP `1 << 8`), para não mover o app para a tela principal ao fechar; confirmar comportamento no OEM. [Definição AOSP Android 15](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/hardware/display/DisplayManager.java).
- Serviço é versionado; autorização Shizuku agora liga o serviço no callback, e desconexão permite reconectar.

**Limites:** um app Android ativo por vez nesta revisão; compartilha a Surface de conteúdo com browser/captura, portanto abrir um encerra o outro. Não são múltiplos apps Android simultâneos. Texto Shizuku limitado a ASCII; comandos input por processo têm latência, não equivalem a injeção contínua a 60 Hz. Apps não redimensionáveis, singleTask, OEMs, bloqueios de displays não confiáveis e conteúdo protegido podem impedir uso. Reflexão de DisplayInfo ocorre no serviço shell; incompatibilidade falha fechada. Falta teste físico Android/Shizuku.

### Preparação no celular

1. Instale/inicie o Shizuku pelo próprio fluxo de **depuração sem fio** (Android 11+) ou outro método oficial suportado. Pareamento/autorização são feitos no aparelho, nunca em chat. Use rede confiável e desative a depuração quando terminar.
2. No TrackMR, dock **••• → Captura → Conectar Shizuku** e autorize.
3. Volte à Biblioteca, escolha o app, **Abrir janela Shizuku**. Não escolha “Compartilhar um app” se quiser este caminho: esse outro botão continua sendo MediaProjection visual.
4. Use barra da janela para Voltar/Teclado/Curva-Parar; fechar/minimizar/background libera o display.
5. Se o app não aparecer, veja Notificações. Recusa do Android não é tratada como sucesso de compatibilidade.

## Diagnóstico para regressões

Sistema → **Copiar diagnóstico** copia versão, fabricante/modelo/Android, backend/status/frame count, tempos, idade local, escala e estado Shizuku. Não inclui imagens, landmarks, conteúdo dos apps ou URLs. Cole esse texto ao relatar falha; não envie códigos de pareamento, credenciais ou conteúdo privado.

Testar: câmera permitida/negada, CPU/GPU, pouca luz, rotacionar telefone antes da abertura, quente/frio, reiniciar, parar Shizuku, app não redimensionável, fechar durante input, resize/recenter, retorno do seletor/QR. Não houve medição de desempenho/latência/energia em hardware nesta revisão.

## APK e validação desta entrega

- Código compilado: `594bcee29e9a7e9f9aeaabd76d7c61fa736b0957`.
- [Actions 36279394546 — aprovado](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36279394546), job `108508215113`, **3m09s**.
- Passaram testes JVM, geometria/protocolo/curva C++, shaders ESSL, APKs, lint (exceção localizada/documentada para flag AOSP), assinatura debug e publicação.
- **[Baixar alpha03 + companion + Dev API](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36279394546/artifacts/10917828874)**. O app principal é `TrackMR-2.0-alpha03-arm64.apk`. Inclui `SOURCE_REVISION.txt` e `SHA256SUMS.txt`. Expira em 10/10/2026.
- [Relatórios](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36279394546/artifacts/10917729732).
- SHA-256 do **arquivo do artefato**, conforme API GitHub (não de um APK individual): `sha256:75d53f1d6b42cb9e3100e3ce849744b5ca7d74dbdac9ae22c2ed9929d01800a7`.

O download para o workspace retornou EOF no armazenamento de artefatos; entrega pelos links oficiais, sem alegar reinspeção local do binário. O commit de documentação posterior não muda o código compilado. Não houve teste físico em telefone/visor/Shizuku nesta sessão.

APKs são debug. Se o Android recusar a atualização por certificado diferente de uma alpha anterior, a reinstalação pode exigir remover a versão antiga, **apagando ajustes/modelos locais**. Preserve os arquivos originais dos modelos e anote seus ajustes antes de decidir remover; não desinstale sem necessidade.
