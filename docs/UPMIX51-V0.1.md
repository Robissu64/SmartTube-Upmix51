# SmartTube Upmix PCM 5.1 — V0.1 experimental

## Escopo e estado

Base examinada: SmartTube `4db79986aeb76698852f66ee52ea26142b1230ed` (32.56).
Submódulos: SharedModules `13f5687dd6757b02fbcdf14c5403d0339e377db5`,
MediaServiceCore `da8102d4e052447edaf48d88d86a00a5c21187fb`.
O fork incorporado declara **AmznExoPlayerLib/2.10.6**, em `ExoPlayerLibraryInfo.java`.
Ele inclui modificações posteriores; não foi substituído por Media3.

Esta versão produz seis amostras PCM por frame a partir de duas. A aceitação
final exige teste em TV + eARC + soundbar. Uma build aprovada, um AudioTrack de
seis canais ou áudio audível no Center, isoladamente, não comprovam o transporte
físico de seis canais. Consulte `VALIDATION.md` para o estado real das verificações.

## Arquitetura encontrada

- `settings.gradle` inclui o fork local como `:exoplayer-library-core` e os
  módulos auxiliares. Não usa a dependência Media3 para esse pipeline.
- `ExoMediaSourceFactory` constrói fontes DASH/manifestos a partir dos formatos
  obtidos pelo MediaServiceCore. `TrackSelectorManager`/`DefaultTrackSelector`
  selecionam faixas; `TrackSelectorUtil` apresenta canais, codec e taxa. Nada
  nessa seleção foi alterado: selecionar uma faixa estéreo continua necessário
  para disparar o experimento.
- `ExoPlayerInitializer` cria o player com a factory personalizada.
- `CustomRenderersFactoryBase` estende `DefaultRenderersFactory`.
  `CustomOverridesRenderersFactory.buildAudioRenderers` chama a implementação
  base e, quando há ajuste de atraso/sincronismo, substitui o renderer pelo
  `DelayMediaCodecAudioRenderer`, também com `DefaultAudioSink`.
- `DefaultRenderersFactory.buildAudioRenderers` cria `MediaCodecAudioRenderer`
  com `DefaultAudioSink(AudioCapabilities.getCapabilities(context), audioProcessors)`.
  Também encaminha os processadores às extensões Opus/FLAC/FFmpeg, se presentes.
- Originalmente `buildAudioProcessors()` devolvia um array vazio. A cadeia do
  sink já continha resampling, channel mapping, trimming, silence skipping e Sonic;
  não foi encontrado um DSP de upmix personalizado ativo nessa factory.
- `MediaCodecAudioRenderer.allowPassthrough` consulta os formatos aceitos pelo
  sink; `supportsOutput` usa `AudioCapabilities` para os formatos codificados.
  Para PCM, o fork assume que o Android consegue adaptar/downmixar a saída.
- `AudioCapabilities` usa o broadcast HDMI (encodings e máximo de canais), com
  exceções de configurações de surround/Amazon. Esse máximo não é uma prova de
  LPCM 5.1, pois pode descrever capacidade de Dolby comprimido.
- `DefaultAudioSink.configure` configura os processadores sequencialmente, obtém
  seus canais/taxa/encoding e calcula bytes por frame de entrada e saída separados.
  `getChannelConfig` chama `Util.getAudioTrackChannelConfig(6)`, que devolve
  `AudioFormat.CHANNEL_OUT_5POINT1`.
- `Configuration.createAudioTrackV21` usa `AudioFormat.Builder.setChannelMask`,
  `setEncoding`, `setSampleRate` e o construtor Android `AudioTrack`. O buffer
  é dimensionado pelo `AudioTrack.getMinBufferSize` e pelas regras do fork.

Caminho novo, sem modificar timestamps do decoder:

`AAC/Opus → decoder → PCM16 estéreo → resampling/mapping/trimming → UpmixAudioProcessor → silence skipping/Sonic → AudioTrack PCM16 5.1`

## Arquivos alterados

| Arquivo (sufixo do caminho) | Responsabilidade |
|---|---|
| `audio/UpmixAudioProcessor.java` no core ExoPlayer | Conversão e flag da V0.1 |
| `audio/DefaultAudioSink.java` no core ExoPlayer | Elegibilidade pela entrada original, fallback de inicialização e logs do AudioTrack |
| `renderer/CustomRenderersFactoryBase.java` no common | Injeção do processador nos renderizadores existentes |
| `renderer/Upmix51Capabilities.java` no common | Consulta conservadora ao Android |
| `audio/UpmixAudioProcessorTest.java` | Amostras, estados, fragmentação, limites e formatos |
| `audio/UpmixAudioSinkTest.java` | Integração com a configuração real do sink |
| `smarttubetv/build.gradle` | ID e versão distintos no build debug |
| `smarttubetv/src/debug/res/values/upmix.xml` | Nome identificável do aplicativo experimental |
| `.github/workflows/upmix51.yml` | Build debug assinada e testes em GitHub Actions |
| `docs/UPMIX51-V0.1.md`, `docs/VALIDATION.md` | Documentação e evidências |

## Conversão e ordem

PCM16 intercalado, little endian (o formato PCM inteiro usado no Android).
Entrada: 4 bytes/frame. Saída: 12 bytes/frame. Taxa de amostragem inalterada.

| Posição | Máscara Android | Valor |
|---|---|---|
| 0 | FRONT_LEFT | L |
| 1 | FRONT_RIGHT | R |
| 2 | FRONT_CENTER | (L+R)/2 |
| 3 | LOW_FREQUENCY | 0 |
| 4 | BACK_LEFT | 0 |
| 5 | BACK_RIGHT | 0 |

`CHANNEL_OUT_5POINT1 = 0xfc` usa BACK_LEFT/BACK_RIGHT, e não os bits SIDE_LEFT/
SIDE_RIGHT. São os dois canais surround desta disposição. A documentação Android
ordena as amostras pelos bits crescentes da máscara, confirmado também em teste.
A soma do Center ocorre em inteiro de 32 bits antes de dividir por dois: não
transborda nos extremos PCM16. FL/FR conservam ganho unitário. Não há limiter,
filtragem ou resampling novo. A energia acústica total pode aumentar pela adição
do Center; faça o primeiro teste em volume moderado.

O buffer direto é reutilizado e limitado a 4096 frames por chamada (49.152 bytes).
O processador pode consumir parcialmente um buffer grande, conforme o contrato
da interface. Retém até três bytes de um frame fragmentado; flush descarta essa
sobra, EOS descarta e registra apenas um frame incompleto inválido. Estados
pendentes de configure só se tornam estados de processamento em flush, para
não converter o buffer antigo usando a configuração nova durante drenagem.
O número de frames não muda. O sink mantém a contabilidade original de tempo e
Sonic continua responsável por velocidades explicitamente escolhidas pelo usuário.

## Elegibilidade, suporte e fallback

Somente entrada ORIGINAL PCM16 com dois canais, sem mapa de canais explícito e
sem tunneling pode ativar a V0.1. Isso impede reaplicar upmix a uma fonte nativa
multicanal que o mapper tenha reduzido a estéreo. PCM float/24/32, mono, 5.1/7.1,
mapas especiais e tunneling mantêm o caminho original.

- Android anterior ao 10/API 29: fallback conservador, sem tentar upmix.
- API 29–30: exige saída HDMI/ARC/eARC anunciando PCM16, máscara 0xfc e taxa
  compatível, além de `AudioTrack.isDirectPlaybackSupported` para a combinação.
- API 31–32: exige também um `AudioProfile` associando PCM16 à máscara/taxa,
  evitando confundir capacidades independentes de codecs comprimidos e PCM.
- API 33+: consulta dispositivos para os atributos reais do player com
  `getAudioDevicesForAttributes` e `getDirectPlaybackSupport` para a rota atual.
- Em todos os casos, `getMinBufferSize` precisa devolver valor positivo.
- Informação ausente/inconclusiva ou exceção na consulta: estéreo original.
- Falha na configuração do buffer 5.1: reconfigura a entrada original sem upmix.
- Falha ao criar/inicializar o AudioTrack 5.1: desativa o processador para a vida
  desse sink e tenta estéreo antes de consumir PCM do decoder. Mantém trimming,
  taxa e contabilidade de frames. Se até a saída estéreo falhar, propaga o erro
  original de reprodução; não esconde uma falha geral do dispositivo.

A regra é conservadora: pode recusar uma TV que realmente suporte 5.1, mas cujo
firmware anuncie capacidades incompletas. Não há um botão para forçar suporte.
Em API 29–32, a consulta de direct playback abrange rotas disponíveis, não apenas
necessariamente a ativa. O log `Actual AudioTrack route` deve ser verificado.
Nem essas APIs nem `STATE_INITIALIZED` demonstram o formato recebido pela soundbar.

Passthrough multicanal permanece intacto. Por mínima invasão, passthrough estéreo
comprimido também não é forçado a decodificar: se um formato como AC3 2.0 entrar
nesse modo, será bypassado. AAC/Opus decodificados são o alvo desta prova.

## Ativar e desativar

Em `UpmixAudioProcessor.java`:

```java
public static final boolean ENABLE_STEREO_TO_51_UPMIX = true;
```

Altere para `false` e recompile para desativar. Com false, a factory retorna a
cadeia original sem o processador. Não foi criada uma interface de configuração.
O APK debug usa `org.smarttube.beta.upmix51` e pode coexistir com o oficial.
A configuração é independente; não desinstale seu SmartTube atual.

## Compilar novamente

Requisitos: JDK 17, SDK Android 34, build-tools 30.0.3, NDK 21.0.6113669 e rede
para Maven/Google/JitPack. O wrapper do repositório usa Gradle 7.5 e AGP 7.4.2.

```bash
git submodule update --init --recursive

sdkmanager "platforms;android-34" "build-tools;30.0.3" "ndk;21.0.6113669"

bash gradlew :exoplayer-library-core:testDebugUnitTest --tests '*Upmix*Test'

bash gradlew :smarttubetv:assembleStbetaDebug
```

Para o ZIP completo, os submódulos já estão incluídos como arquivos; não é
necessário executar git submodule. Configure `ANDROID_HOME` ou `local.properties`
com `sdk.dir=/caminho/para/seu/sdk`. Não reutilize caminhos locais desta máquina.

Saída: `smarttubetv/build/outputs/apk/stbeta/debug/`. Use ARM64 se o Android da TV
for 64 bits; uma CPU ARM64 pode estar rodando Android de 32 bits. A universal
cobre ambas. Debug usa a chave de desenvolvimento local, não a chave oficial.
A chave debug de outra máquina/runner pode ser diferente para uma futura atualização.

O workflow **Upmix 5.1 V0.1** permite execução manual ou por push na branch
`experimental/upmix51-v01`, em um fork. Ele compila, testa e publica APKs como
artifacts da execução. Não publica uma release. Nenhuma execução remota é
alegada sem registro em `VALIDATION.md`.

## Teste na TV

1. Instale a APK experimental ao lado do oficial.
2. Ative eARC na TV e selecione a soundbar como saída. Não presuma que o menu
   chamado “PCM” significa LPCM multicanal: em algumas TVs ele limita a estéreo.
   Verifique as configurações/indicador do seu equipamento.
3. Na soundbar, use modo direto/standard sem upmix, virtualização ou surround
   artificial. Desative processamento adicional para não confundir o teste.
4. No SmartTube, deixe tunneling desligado, velocidade 1x, sem boost/ajuste
   automático de volume e sem pular silêncio durante o diagnóstico.
5. Escolha uma faixa AAC/Opus identificada como **2ch**, de preferência 48 kHz.
6. Confirme no log a entrada 2ch, a ativação, a primeira conversão, o AudioTrack
   de seis canais/máscara 0xfc e a rota HDMI/ARC/eARC.
7. Verifique no indicador/app da soundbar **Multichannel PCM / LPCM 5.1**, quando
   disponível. “PCM” sozinho não identifica a quantidade de canais.
8. Ouça FL/FR e Center. Surrounds não devem receber sinal do app. LFE está zerado,
   mas o subwoofer físico ainda pode tocar devido ao bass management da soundbar.
9. Compare com o recurso desligado (ou SmartTube original). Um teste estéreo L-only
   deve tocar FL e Center; R-only deve tocar FR e Center. Um sinal estéreo em
   oposição de fase (R=-L) cancela o Center calculado, ajudando a detectar DSP externo.
10. Teste depois uma faixa 5.1 nativa; ela deve manter o comportamento anterior.
    Teste pause/resume, seek e troca entre estéreo/5.1 e taxas de 44,1/48 kHz.

Não basta ouvir o Center: um DSP da TV/soundbar também pode criá-lo após downmix.
A melhor evidência combina logs, indicação de LPCM multicanal e testes de canais.

## Instalação e logs por ADB

Substitua os valores de exemplo. Na primeira conexão, aceite a autorização na TV.
Depuração sem fio recente pode exigir `adb pair IP:PORTA` antes de `adb connect`.

```bash
adb connect IP_DA_TV:5555

adb install -r SmartTube_Upmix51_v0.1_universal.apk

adb logcat -c

adb logcat -v threadtime 'UPMIX51:I' '*:S' > upmix51-logcat.txt
```

Reproduza um vídeo e use Ctrl+C para encerrar a captura. Para investigar uma falha:

```bash
adb logcat -d -v threadtime > smarttube-logcat-completo.txt

adb shell dumpsys audio > audio-dump.txt

adb shell dumpsys media.audio_flinger > audioflinger-dump.txt

adb shell getprop ro.build.version.release

adb shell getprop ro.product.cpu.abilist
```

Envie o log filtrado, modelo da TV/soundbar, versão Android, forma de conexão e
foto do indicador de áudio. Logs completos podem conter URLs/identificadores;
revise-os antes de compartilhar publicamente.

## Limitações deliberadas

- Sem comprovação física eARC até o teste no seu equipamento.
- Suporte restrito a PCM16, Android 10+ e capacidades explicitamente anunciadas.
- Não força roteamento, direct track ou formato no firmware. Android ainda pode
  processar/converter a saída após o app. Requer confirmação externa.
- Se trocar a saída, desconectar HDMI ou mudar eARC durante a reprodução, pare
  e reabra o vídeo. Esta V0.1 não implementa recuperação contínua de hotplug/
  `AudioTrack.ERROR_DEAD_OBJECT`; segue o tratamento original após erros de escrita.
- Gate reavaliado em configure, não a cada frame. Falha de inicialização desativa
  upmix nesse sink até recriar o player.
- Não converte PCM float/high resolution nem força decodificação de passthrough.
- Não há algoritmo de surround, LFE, filtro, extração vocal, IA ou presets.

Referências primárias verificadas:
- https://github.com/yuliskov/SmartTube
- https://developer.android.com/reference/android/media/AudioFormat
- https://developer.android.com/training/tv/playback/audio-capabilities
- https://developer.android.com/reference/android/media/AudioTrack
