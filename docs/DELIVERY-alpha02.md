# Entrega TRACKMR2.0 · alpha02

## Compilação validada

- **Código dos APKs:** `7324be1900d40fe7c01dbc399c5df7e619fa798e`.
- **GitHub Actions:** [36277281705 — aprovado](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36277281705).
- Job `108502363345`: **4m07s**, aprovado em 26/09/2026.
- Passaram: geometria C++, protocolo de frames OpenXR, compilação/link ESSL, testes JVM `core`/`xr`/`handtracking`, dois APKs arm64, lint, `apksigner`, checksums e publicação de artefatos.
- O commit de documentação posterior não altera código dos APKs. Confira sempre `SOURCE_REVISION.txt` ao baixar.

## Downloads

**[Pacote dos APKs e Dev API](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36277281705/artifacts/10917875396)**

Inclui:

- `TrackMR-2.0-alpha02-arm64.apk`
- `TrackMR-Runtime-Companion-alpha02-arm64.apk`
- `TrackMR-Dev-API-v1.jar`
- `SHA256SUMS.txt` (hashes de cada APK/JAR)
- `SOURCE_REVISION.txt`

Artefato `TrackMR-2.0-debug-arm64`: **30.289.611 bytes**, expira em **10/10/2026**. SHA-256 do arquivo do artefato informado pela API GitHub (não confundir com hash de um APK):

```
a7858831ced0de5c6bfe7a418ce81190493ac61f8b03cd538fbba17b42840eee
```

[Relatórios automatizados](https://github.com/AkunDiscoRemake/TrackMR-2.0/actions/runs/36277281705/artifacts/10917830809).

O endpoint de armazenamento de artefatos retornou EOF ao tentar baixar para este workspace. Por isso os arquivos são entregues pelos links oficiais do Actions; não há aqui alegação de reinspeção local dos APKs ou de seus hashes individuais. Assinaturas/checksums foram verificados/gerados no job aprovado. Pode ser necessário entrar no GitHub para baixar.

## Alterações principais

- Inicialização diretamente espacial, solicitando passthrough e mãos; fallback neutro explícito, VR selecionável.
- Cardboard oficial preservado, dock de 14 destinos, janelas/teclado e interação por mãos/olhar.
- ARCore/Camera2, filtros e associação temporal independentes da UI; âncoras de sessão visíveis e depth opcional de objetos.
- Browser espacial privado, IA local com importação explícita, screenshot com confirmação e sons UI opcionais.
- Biblioteca com pesquisa/recentes/favoritos, catálogo com detalhes; sem fingir marketplace remoto.
- Cliente de sessão OpenXR independente com loader, lifecycle, swapchains/actions/hands e submissão de frames; teste de protocolo e shaders no CI.
- Correções de ownership da câmera/captura, encerramento de projeção bound, clocks/UV de depth, GPU query disjoint e orçamento de atlas/packet.

## Limites que permanecem

**Não é a conclusão de todo o escopo solicitado.** Os APKs não fornecem um runtime Monado standalone. A sessão OpenXR exige runtime externo compatível e ainda não contém o shell completo. Faltam, entre outros, apps Shizuku independentes com input, WebXR embarcado, marketplace com instalação/update verificados, SLAM/âncoras persistentes/guardian, ambientes glTF, áudio HRTF e Vulkan.

**Nenhum teste físico de telefone/visor/runtime, latência E2E, FPS sustentado, potência ou térmica foi realizado.** Não anunciar ganho ou compatibilidade antes dessas medições.

Detalhes: [README](../README.md), [arquitetura](ARCHITECTURE.md), [OpenXR](OPENXR.md), [performance](PERFORMANCE.md), [validação](TESTING.md), [pendências](ROADMAP.md) e [pesquisa OSS](research/DECISIONS.md).
