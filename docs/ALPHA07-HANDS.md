# Alpha07 — entrega de resultados, esqueleto GLES e diagnóstico

**A alpha06 continuou sem rastrear as mãos no aparelho do usuário.** A foto mostra câmera ativa e mão, mas nenhum esqueleto; os diagnósticos anteriores estavam cortados. Não sabemos ainda o modelo/Android nem os tempos/contadores do detector desse aparelho. Não atribuímos a falha física ao ARCore, ao modelo ou à latência sem esses dados.

## Defeito reproduzido e correção

A alpha06 expirava o resultado pela idade desde a aquisição, usando `clamp(processamento + 65, 150, 350)` ms. Uma inferência de 200 ms deixava só 65 ms de uso antes de esconder a mão, embora a próxima inferência ainda estivesse em andamento. A entrega de `null` ao ponteiro cancelava a pinça e exigia mão aberta novamente. Acima de 350 ms, o resultado já chegava vencido.

Agora cada lote tem um relógio de conclusão separado:

- Mantém o último resultado durante **uma cadência estimada**, limitada a `clamp(max(processamento, intervalo) + 80, 120, 350)` ms **após a conclusão**.
- Interação exige processamento ≤300 ms e idade desde aquisição ≤650 ms.
- Resultado mais lento pode ser **visual apenas**, em âmbar. Não inicia nem mantém contato.
- Após o prazo de conclusão, ou com aquisição >1.500 ms, expira totalmente. Sem mãos/pausa térmica cancela imediatamente.
- Uma amostra visual de baixa qualidade também não habilita pinça. Nunca se substitui uma mão perdida por landmarks da referência.

Isso elimina um intervalo artificial sem resultado, **não acelera o modelo** e não estabelece a causa da foto do aparelho. Captura aqui é o relógio local de aquisição, não uma medição física sensor→tela.

## Render e diagnóstico

- Esqueleto com linhas e pontos de 6 pixels no framebuffer do olho: verde interativo; âmbar visual apenas. O tamanho final depende da escala/distorção Cardboard.
- Conversão dos 21 pontos, shaders e rotina de desenho extraídos para um helper compartilhado com o teste GLES Android, sem nova dependência de produção.
- Três cartões de diagnóstico agora usam coordenadas relativas a cada viewport Cardboard, não posição fixa no mundo. São ignorados no hit test. Mostram aparelho/Android, YUV, backend, inferências/tempo, autoteste, mãos brutas/filtradas e situação da interação.
- A seleção de FPS Camera2 deixava vencer a faixa de menor mínimo. Agora prefere maior máximo até 30 FPS e, no empate, maior mínimo suportado; usa a menor faixa disponível se todas excederem 30. Não inventa `[30,30]` numa câmera que não anuncia essa faixa. Isso limita exposição longa quando há uma faixa fixa disponível; não é comprovação de melhora de detecção e pode afetar luz/exposição.

Sem toque na tela como substituto das mãos, sem remover ARCore opcional, sem trocar o modelo por um mock. A migração de configuração da alpha06 permanece; não sobrescreve novamente preferências explícitas de quem já a executou.

## Testes e limites

1. **JVM:** reprodução da antiga expiração; consumo a cada 16 ms com inferências de 100/150/200/280 ms; sequência abrir→pinçar→segurar→soltar exige exatamente DOWN/MOVE/UP. Cobre também lentidão, relógios inválidos, perda, pausa e expiração. Estes são testes de política com amostras sintéticas, não gestos físicos.
2. **Camera2 FPS:** preferência por faixa fixa, alternativa variável anunciada, câmeras sem 30 FPS e ausência de faixas.
3. **Android/JNI:** tracker de produção, ImageReader/ImageWriter YUV, MediaPipe ARM64 real traduzido, 24 imagens positivas nas quatro rotações + 4 pretas negativas. Autoteste não pode publicar mãos no shell.
4. **GLES/EGL:** landmarks realmente detectados/filtrados são enviados à conversão, shaders e rotina de desenho de produção; um pbuffer EGL é lido com `glReadPixels`. Exige pixels verdes e âmbar; ausência de mão exige zero pixels de esqueleto. Não executa a Activity, o compositor Cardboard completo, a projeção óptica ou a câmera física.
5. **Segurança temporal:** o teste Android compara a política com os tempos medidos. Uma primeira versão do teste exigia que toda inferência fosse visível, e falhou quando a execução emulada excedeu o limite de 1.500 ms. A expectativa foi corrigida; o limite de segurança da aplicação **não foi relaxado** para aprovar o emulador. Tempos emulados não são benchmark de celular.

## Download e execução aprovada

**[Baixar alpha07 — APKs e proveniência](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36286888783/artifacts/10920654003)**. Instale `TrackMR-2.0-alpha07-arm64.apk`; companion, Shizuku ou importação de pesos não são requisitos das mãos.

- Fonte: `6069ee7c5d8357ba6e88750d830bd794eb606dd8`.
- [Actions 36286888783](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36286888783): ambos os jobs aprovados.
- Build/JVM/lint/APKs: job `108529231243`, 4m17s.
- Android JNI + GLES: job `108529231177`, 6m22s; teste instrumentado 64,224 s, zero falhas/erros/ignorados.
- Anotação confirmada: `PASS GLES readback: real filtered landmarks render green and amber pixels`, além das quatro rotações e quadros pretos sem mãos.
- Os **24 resultados positivos emulados levaram 1.930–2.010 ms** (mediana 1.943,5 ms, tempos inteiros registrados). Todos foram corretamente classificados EXPIRED pela política. O teste de geometria GLES desenha os landmarks separadamente dessa política; **não demonstra interação nativa em tempo real**. A continuidade de pinça foi validada nos testes JVM com tempos simulados, não no emulador lento nem no telefone.
- ZIP: artifact `10920654003`, 30.378.416 bytes; expira `2026-10-11T01:57:40Z`. SHA-256 informado pela API GitHub: `ff563af8fd21eb47e4ca21362060ef4fbcee5bbcbc743b7f4d94f3342c0b57fb`.
- Relatórios nativos: `10919869780`; JVM/lint: `10921115595`. Evidência obtida nas anotações/metadados do Actions; a tentativa de download local do ZIP falhou com EOF, não foi alegada inspeção local do APK.

A compilação/JVM/lint da mudança de validade anterior (`6b51505`, run `36286492116`) passou, mas aquele run como um todo falhou no teste nativo descrito acima. Essa falha não foi contabilizada como aprovação.

APKs debug podem ter assinatura diferente da instalação anterior. Se Android recusar atualização, preserve dados/modelos antes de considerar desinstalar; desinstalação apaga dados.

## Validação física ainda necessária

Não há confirmação de mãos funcionais no telefone. Precisamos do **modelo/Android e de uma foto dos três novos cartões após deixar a mão aberta diante da câmera**. Isso separa falha de inicialização, ausência de YUV, detector sem mãos, filtro e resultado lento/expirado, sem pedir upload das imagens da câmera para um serviço externo.

Depois: apontar, abrir→pinçar→arrastar→soltar, perda/reentrada sem clique fantasma, duas mãos, background/retorno, orientação e sessão prolongada. Latência/FPS/temperatura/bateria e legibilidade dentro das lentes permanecem sem medição física.
