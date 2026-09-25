# Planejador de Viagem (Android Auto)

App Android em Kotlin que planeja viagens de carro e navega pelo **Android Auto**:

- **Rota** curva a curva, com instruções em português e aviso por voz.
- **Radares** no caminho, com alerta falado e na tela a 1 km e a 300 m, mostrando o limite de velocidade quando ele é conhecido.
- **Paradas para abastecer** calculadas pela autonomia do carro (tanque, consumo, combustível atual e reserva de segurança), com litros e custo estimados.
- **Google Maps** no celular e na tela do carro.
- Recalcula a rota quando o motorista sai do trajeto.

## Arquitetura

```
core/  (Kotlin puro, JVM – testável sem Android)
  api/         OsrmClient (rota), NominatimClient (busca de endereços), OverpassClient (radares e postos)
  geo/         distâncias, polyline, projeção de pontos sobre a rota, simplificação
  planning/    FuelPlanner (paradas de combustível), TripPlanner (orquestra tudo)
  navigation/  NavigationTracker (progresso, próxima manobra, alertas, saída da rota), Instructions (pt-BR)

app/   (Android)
  ui/    tela do celular (Jetpack Compose + Maps Compose) para planejar a viagem
  car/   app Android Auto (Car App Library, categoria NAVIGATION)
         MapSurfaceRenderer desenha o Google Maps na superfície do carro via VirtualDisplay
  data/  TripRepository (viagem compartilhada entre celular e carro, salva em disco)
```

## APIs usadas (todas gratuitas)

| Uso | Serviço | Observação |
|---|---|---|
| Mapa | **Google Maps SDK for Android** | Uso ilimitado e sem custo no Android, mas precisa de uma chave de API |
| Rota | **OSRM** (`router.project-osrm.org`) | Servidor de demonstração, sem garantia de disponibilidade. Para uso intenso, rode o seu (`docker run osrm/osrm-backend`) e troque a `baseUrl` em `OsrmClient` |
| Busca de endereço | **Nominatim** (OpenStreetMap) | Limite de 1 requisição por segundo; a busca só é enviada quando o usuário confirma |
| Radares e postos | **Overpass API** (OpenStreetMap) | `highway=speed_camera` e `amenity=fuel` ao longo da rota. Se o servidor principal falhar, usa um espelho |

O app **não usa** a Directions API nem a Places API do Google, que são pagas.

## Como configurar

1. Crie uma chave em Google Cloud Console → *APIs e serviços* → ative **Maps SDK for Android**.
   Restrinja a chave ao pacote `com.fiscon.viagem` e ao SHA-1 do seu certificado.
2. Adicione a chave em `local.properties` (esse arquivo não vai para o git):
   ```properties
   MAPS_API_KEY=AIza...
   ```
3. Compile com `./gradlew :app:assembleDebug` e instale com `./gradlew :app:installDebug`.

## Testando no Android Auto

1. No celular, abra o Android Auto → toque 10 vezes em *Versão* para ativar o modo desenvolvedor →
   menu ⋮ → *Configurações do desenvolvedor* → ative **Fontes desconhecidas**.
2. Para testar sem carro, use o **Desktop Head Unit (DHU)** do Android SDK (`extras/google/auto`):
   no Android Auto, menu ⋮ → *Iniciar servidor da unidade principal*. Depois rode
   `adb forward tcp:5277 tcp:5277` e `./desktop-head-unit`.
3. Com a navegação aberta, este comando inicia uma **simulação** que percorre a rota. É útil para ver
   os alertas de radar e de abastecimento sem sair de casa:
   ```bash
   adb shell dumpsys activity service com.fiscon.viagem/.car.TravelCarAppService AUTO_DRIVE
   ```

## Uso

1. **No celular:** informe o destino e toque na lupa para escolher o resultado certo. Se a origem ficar
   vazia, o app usa sua localização atual. Ajuste os dados do veículo e toque em **Planejar viagem**.
   O app mostra a rota no mapa, os radares, os postos (as paradas planejadas em verde) e o resumo de
   consumo e custo.
2. **No carro:** a tela inicial mostra a viagem planejada. Toque em **Iniciar navegação**. Também é
   possível buscar um destino no próprio carro ou pedir ao Assistente "navegar até ... com Planejador
   de Viagem".

## Como o abastecimento é planejado

- A autonomia útil é `(combustível − reserva) × consumo`. A reserva é uma porcentagem do tanque (padrão 15%).
- Entre os postos alcançáveis sem entrar na reserva, o app escolhe o mais distante, penalizando o
  desvio até o posto. Em cada parada, o tanque é completado.
- Se nenhum posto for alcançável sem usar a reserva, o app avisa. Se nenhum posto for alcançável de
  jeito nenhum, a viagem aparece como inviável.

## Limitações

- Radares e postos vêm do OpenStreetMap: a cobertura varia por região, e radares móveis não aparecem.
- Não há API gratuita de preço de combustível em tempo real, então o preço por litro é informado pelo usuário.
- Desenhar o Google Maps na tela do carro por `VirtualDisplay` funciona, mas não é um uso documentado
  pelo Google. Para publicar na Play Store como app de navegação, revise as políticas do Android Auto.
  A alternativa oficial do Google é o *Navigation SDK*, que é pago.

## Testes

```bash
./gradlew :core:test
```

Os testes cobrem geometria, leitura das respostas do OSRM e do Overpass, o planejador de
abastecimento, o planejamento completo com um servidor HTTP simulado e o acompanhamento da
navegação (alertas, saída da rota, chegada).
