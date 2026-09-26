# Validação

## Automatizada

- `:core:test`: One Euro em repouso/ruído/gap, timestamps repetidos, Kalman em velocidade constante, descarte NaN, pinça/histerese, política térmica, percentis, mesh curva/plana.
- `tests/native_math_test.cpp`: inversão, translação/quaternion, raios de painel e esfera, rejeição de interseções atrás/fora.
- Android assemble + lint em CI. CMake compila Cardboard e renderer contra NDK real; runtime liga loader OpenXR real.
- APKs verificadas com `apksigner`; checksums publicadas junto.

**Build/testes unitários aprovados não substituem teste no celular nem comprovam baixa latência.** Nesta alpha não há teste instrumentado de câmera, frames de referência ou hardware-in-the-loop.

## Roteiro manual obrigatório

| Área | Casos | Critério |
|---|---|---|
| Instalação | Android 10/12/14/15+, arm64; páginas 4/16 KiB; sem ARCore | Home abre sem pedir permissões desnecessárias |
| Cardboard | QR salvo/ausente/inválido; dois perfis | Projeção/distorção corretas, escala não altera ótica |
| Orientação | Yaw/pitch/roll, recenter, retorno de QR | Mundo estável sem eixo invertido; sem NaN |
| 360° | Dois ambientes, costura/polos | Imagens certas, foto sem parallax artificial |
| EGL | Home, bloqueio, rotação, pressão de memória | Recupera render; captura perdida é encerrada com segurança |
| Jogos | Volume+, botão A, toque; erro na sequência | Pontos respondem e Menu retorna |
| ARCore | Instalação, recusa de câmera, sem suporte, pouca luz | Fallback 3DoF e estado claro, sem crash |
| Mãos | CPU/GPU, oclusão, mão esquerda/direita, timestamps | Sem fila, sem esqueleto velho >150ms; nenhum gesto após perda |
| Captura | Recusa, um app, tela inteira, resize, sistema encerra | Notificação, stop, correto aspect; sem reutilizar consentimento |
| Proteção | FLAG_SECURE/DRM | Continua protegido; nunca tentar contornar |
| Shizuku | Ausente, negado, autorizado, morte do binder | Erro útil, sem promoção silenciosa ou comandos no display 0 |
| IA | Sem modelo, incompatível, sem espaço, RAM insuficiente, Activity fecha | Sem nuvem; erro claro; close ordenado |
| Térmica | Status alto, alternância rápida de carga | Escala/cadência limitadas, sem oscilação a cada frame |
| OpenXR | Sem Broker, sem runtime, Monado escolhido, ABI errada | Status real; não registrar runtime falso |

## Critérios de maturidade antes de release

Pelo menos três aparelhos de SoCs diferentes; sessões de 20 minutos; Perfetto; conforto ótico; teste de leitura/tamanho de UI dentro das lentes; auditoria de ciclo de vida/permissões; comparação de filtros com dados reais; política neural validada em aparelhos não usados no treino; scan de dependências/licenças; assinatura release e atualizações de segurança. Relatar modelo do aparelho, Android, visor/perfil, frequência, temperatura e revisão do código em cada resultado.
