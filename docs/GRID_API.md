# API de GRID (Riot Games Open Access): consultes per a Worlds

Guia pràctica per obtenir les dades de League of Legends de GRID, el proveïdor oficial de dades d'esports de Riot. De moment Open Access només té els partits de **Worlds 2026**; quan s'hi afegeixin lligues (LCK, LEC…) les mateixes consultes serveixen canviant el filtre de torneig.

Les consultes d'aquest document són també a [`backend/scripts/grid_explore.py`](../backend/scripts/grid_explore.py), que les executa i desa les respostes en JSON.

> **Estat (20/11/2026):** totes les consultes s'han executat amb la clau d'Open Access i funcionen per a LoL. Els valors reals que retorna GRID són a [Valors reals de LoL](#valors-reals-de-lol).

## Accés

| | |
|---|---|
| Documentació | https://docs.grid.gg/public/documentation (necessita JavaScript) |
| Autenticació | Capçalera `x-api-key: <clau>` a cada petició `POST` |
| Central Data (dades estàtiques) | `https://api-op.grid.gg/central-data/graphql` |
| Series State (estat de les sèries) | `https://api-op.grid.gg/live-data-feed/series-state/graphql` |
| GraphQL Playground | Pestanya «GQL Playground» de la documentació: s'hi enganxa la clau i es poden provar les consultes. El botó **Docs** mostra l'esquema sencer. |

Les URL amb `api-op` són les d'Open Access; les d'accés comercial són `api.grid.gg` i no funcionen amb la nostra clau.

**Què inclou Open Access:** Central Data i Series State. **No inclou** Series Events (WebSocket d'esdeveniments), File Download ni Stats Feed. Les dades en directe arriben amb **5 minuts de retard**.

### Límits

| API | Límit |
|---|---|
| Central Data | 20 peticions/minut |
| Series State | 180 peticions/minut en total i **6 per minut per sèrie** (una cada 10 s) |

Si es supera, l'API respon que cal esperar. `grid_explore.py` ja espaia les peticions.

### On va la clau

Igual que la resta de credencials del projecte, **mai al repositori ni a l'app Android**:

- En local: fitxer `.env` a l'arrel del repositori (ja és al `.gitignore`) amb `GRID_API_KEY=...`, o variable d'entorn `GRID_API_KEY`.
- A AWS: paràmetre `GridApiKey` de la plantilla SAM (arriba a les Lambdes com a `GRID_API_KEY`).

L'app no ha de parlar mai directament amb GRID: llegeix de Firestore, com ara.

## Conceptes

| GRID | League of Text | Notes |
|---|---|---|
| **Title** | — | El joc. Cal l'id de League of Legends per filtrar. |
| **Tournament** | Lliga / torneig | Té jerarquia (`parent` / `children`): Worlds pot tenir fases com a fills. |
| **Series** | Partit (`Matches/{id}`) | Un enfrontament Bo1/Bo3/Bo5 entre dos equips. |
| **Game** | Partida (`Games/{id}`) | Cada partida d'una sèrie (`sequenceNumber` 1, 2, 3…). |
| **Team** / **Player** | Equip / jugador | Els ids són de GRID, diferents dels de l'API de LoL Esports. |

Central Data diu **quan i qui** juga (calendari, equips, format). Series State diu **què ha passat** (marcador, partides, estadístiques), tant en directe com un cop acabada la sèrie.

## 1. Trobar l'id de League of Legends

Central Data:

```graphql
query Titles {
  titles {
    id
    name
    nameShortened
  }
}
```

Apunta l'`id` de *League of Legends*: és el `titleId` de les consultes següents.

## 2. Trobar el torneig de Worlds

Central Data. El filtre de nom no distingeix majúscules:

```graphql
query Tournaments($name: String!, $after: String) {
  tournaments(
    first: 50
    after: $after
    filter: { name: { contains: $name } }
    orderDirection: ASC
  ) {
    totalCount
    pageInfo { hasNextPage endCursor }
    edges {
      node {
        id
        name
        nameShortened
        startDate
        endDate
        logoUrl
        titles { id name }
        parent { id name }
        children { id name }
      }
    }
  }
}
```

Variables: `{ "name": "World" }`.

Serveix per saber el nom exacte que fa servir GRID (p. ex. si és «World Championship 2026» o «Worlds 2026»), els ids i si les fases (Swiss, eliminatòries…) són tornejos fills.

## 3. Llistar les sèries de Worlds

Central Data. Equivalent a `GetScheduledMatches`:

```graphql
query TournamentSeries(
  $titleId: ID!
  $tournamentName: String!
  $from: String!
  $to: String!
  $after: String
) {
  allSeries(
    first: 50
    after: $after
    filter: {
      titleIds: { in: [$titleId] }
      types: [ESPORTS]
      tournament: { name: { contains: $tournamentName }, includeChildren: { equals: true } }
      startTimeScheduled: { gte: $from, lte: $to }
    }
    orderBy: StartTimeScheduled
    orderDirection: ASC
  ) {
    totalCount
    pageInfo { hasNextPage endCursor }
    edges {
      node {
        id
        startTimeScheduled
        updatedAt
        format { name nameShortened }
        tournament { id name nameShortened logoUrl parent { id name } }
        teams {
          scoreAdvantage
          baseInfo { id name nameShortened logoUrl colorPrimary colorSecondary }
        }
        streams { url }
        productServiceLevels { productName serviceLevel }
      }
    }
  }
}
```

Variables:

```json
{
  "titleId": "<id de LoL>",
  "tournamentName": "World",
  "from": "2026-09-01T00:00:00Z",
  "to": "2026-12-31T23:59:59Z"
}
```

- **Paginació:** com a màxim 50 resultats per pàgina. Si `hasNextPage` és `true`, torna a fer la consulta amb `after` = `endCursor`.
- **Filtre per ids:** un cop coneguts els ids del pas 2, és més precís filtrar per `tournament: { id: { in: ["<id>"] }, includeChildren: { equals: true } }` que pel nom.
- **Logotips i colors:** `logoUrl`, `colorPrimary` i `colorSecondary` dels equips poden substituir els logotips que ara es desen a Firestore.
- `productServiceLevels` indica si GRID té dades completes (`FULL`), parcials (`LIMITED`) o cap (`NONE`) de cada sèrie.
- No hi ha cap consulta que retorni «les sèries en directe ara». GRID recomana consultar Central Data cada 15–30 minuts buscant les sèries programades entre 4 hores abans i 1 hora després d'ara.

## 4. Marcador d'una sèrie

Series State. Lleugera, per a les llistes (Pròxims · En directe · Resultats) i per a `MatchIsLive` / `UpdateMatchState`:

```graphql
query SeriesResult($id: ID!) {
  seriesState(id: $id) {
    id
    version
    format
    started
    finished
    forfeited
    valid
    startedAt
    updatedAt
    duration
    teams { id name score won }
    games {
      id
      sequenceNumber
      started
      finished
      paused
      startedAt
      duration
      teams { id name side won }
    }
  }
}
```

Variables: `{ "id": "<id de la sèrie del pas 3>" }`.

- `started` / `finished` substitueixen la lògica actual per saber si un partit és `scheduled`, `live` o `completed`.
- `teams[].score` és el nombre de partides guanyades i `teams[].won` diu qui ha guanyat la sèrie. **Ja no cal endevinar el guanyador** a partir de l'últim fotograma, com fa ara `find_game_winner`.
- `games[].teams[].won` dona el guanyador de cada partida i `side` el costat (blau/vermell).
- `valid` és `false` si GRID ha detectat problemes amb les dades d'aquella sèrie.
- `duration` és una durada ISO 8601 (p. ex. `PT32M15S`).

## 5. Detall complet d'una sèrie

Series State. Per a la pantalla de detall i per a `UpdateGameState`:

```graphql
query SeriesDetail($id: ID!) {
  seriesState(id: $id) {
    id
    format
    started
    finished
    valid
    updatedAt
    teams {
      id
      name
      score
      won
      kills
      deaths
      players { id name kills deaths killAssistsGiven }
    }
    games {
      id
      sequenceNumber
      started
      finished
      paused
      startedAt
      duration
      titleVersion { name }
      clock { currentSeconds ticking }
      draftActions {
        sequenceNumber
        type
        drafter { id type }
        draftable { id type name }
      }
      structures { id type side teamId destroyed currentHealth maxHealth }
      nonPlayerCharacters { id type side alive respawnClock { currentSeconds } }
      teams {
        id
        name
        side
        won
        kills
        deaths
        netWorth
        firstKill
        structuresDestroyed
        objectives { id type completionCount completedFirst }
        unitKills { unitName count }
        multikills { numberOfKills count }
        ... on GameTeamStateLol {
          totalMoneyEarned
          moneyDifference
          experiencePoints
          damageDealt
          visionScore
          baronPowerPlays { id value }
        }
        players {
          id
          name
          participationStatus
          character { id name }
          kills
          deaths
          killAssistsGiven
          netWorth
          firstKill
          inventory { items { id name quantity } }
          multikills { numberOfKills count }
          ... on GamePlayerStateLol {
            roles { id }
            alive
            currentHealth
            maxHealth
            experiencePoints
            totalMoneyEarned
            damageDealt
            visionScore
            kdaRatio
            killParticipation
          }
        }
      }
    }
  }
}
```

L'API és la mateixa per a tots els jocs: els camps comuns (kills, morts, or…) es demanen directament i els propis de LoL van dins de `... on GameTeamStateLol` i `... on GamePlayerStateLol`. A nivell de sèrie no hi ha tipus propi de LoL, només els camps comuns. Treu els camps que no facis servir: la resposta serà més petita i més ràpida.

### Correspondència amb les notificacions de l'app

Open Access no té l'API d'esdeveniments, de manera que els esdeveniments es continuen generant **comparant dos estats consecutius**, com ara amb els fotogrames de `livestats`:

| Notificació actual | D'on surt a Series State |
|---|---|
| Primera sang | `games[].teams[].firstKill` (i `players[].firstKill` per saber qui) |
| Kills / marcador | `games[].teams[].kills` i `players[].kills` |
| Multikills | `multikills[]`: `numberOfKills` (2 = doble, 3 = triple…) i `count`. Quan un `count` puja, hi ha hagut una multikill. |
| Torres i inhibidors | `objectives[]`: `destroyTower`, `destroyFortifier` (i `structures[]` per saber quina estructura) |
| Dracs, baró, herald… | `objectives[]`: `slay…Drake`, `slayElderDragon`, `slayBaron`, `slayRiftHerald` |
| Pauses | `games[].paused` |
| Resultat de la partida | `games[].finished` + `games[].teams[].won` |
| Resultat del partit | `finished` + `teams[].won` / `teams[].score` |
| Temps de joc | `games[].clock.currentSeconds` |
| Or | `netWorth`, `totalMoneyEarned`, `moneyDifference` |

Els valors concrets de `type` (que no són a la documentació) són a la secció següent.

### Draft

`draftActions` dona els bans i picks per ordre (`sequenceNumber`). `drafter.id` és l'equip i `draftable.name` el campió. El campió de cada jugador també surt a `players[].character.name`.

## Valors reals de LoL

Extrets de les respostes reals de Worlds 2026 (`worlds_series.json` i la final, sèrie `3012986`, Gen.G 1-3 BLG). Cap d'aquests valors no és a la documentació de GRID.

### Tornejos de Worlds 2026

```
Worlds
└─ 831629  Worlds - 2026                  (15/10 → 15/11)
   ├─ 831630  Worlds - 2026 (Play-Ins)  →  831631  Worlds - 2026 (Play-Ins: Play-Ins)
   ├─ 831632  Worlds - 2026 (Swiss)     →  831633  Worlds - 2026 (Swiss: Swiss)
   └─ 831634  Worlds - 2026 (Knockouts) →  831635  Worlds - 2026 (Knockouts: Knockouts)
```

- Les sèries pengen del tercer nivell («X: X»). Amb `includeChildren` sobre `Worlds - 2026` surten les **46 sèries**: 6 de Play-Ins, 33 de la fase suïssa i 7 d'eliminatòries (20 Bo1, 13 Bo3, 13 Bo5).
- La fase es pot treure del nom del torneig (el text entre parèntesis) i fer-la servir com a `blockName`.
- Totes les sèries tenen `liveDataFeed: FULL`. `streams` és buit a totes: GRID no dona els enllaços de les retransmissions.
- Els equips porten `logoUrl` (CDN de GRID), `colorPrimary`, `colorSecondary` i `nameShortened` (p. ex. `C9`).
- El format és `Bo5` a Central Data i `best-of-5` a Series State.

### `objectives[].type` (per equip i partida)

| Valor | Què és | Notificació |
|---|---|---|
| `slayDragon` | Total de dracs, **inclòs l'Elder** | — (fer servir els específics) |
| `slayOceanDrake`, `slayCloudDrake`, `slayMountainDrake`, `slayHextechDrake`, `slayChemtechDrake` | Drac elemental de cada tipus. L'infernal no ha sortit a la final: probablement és `slayInfernalDrake`, però cal confirmar-ho amb una altra sèrie. | Drac |
| `slayElderDragon` | Drac ancestral | Drac (Elder) |
| `slayBaron` | Baró Nashor | Baró |
| `slayRiftHerald` | Herald | — |
| `slayVoidGrub` | Larves del Buit | — |
| `slayRiftScuttlerTop`, `slayRiftScuttlerBot` | Crancs del riu | — |
| `destroyTower` | Torre | Torre |
| `destroyFortifier` | **Inhibidor** | Inhibidor |
| `destroyNexus` | Nexe (`completionCount` 1 a l'equip guanyador) | Resultat |
| `destroyTurretPlateTop1`… `Mid3`, `Bot3` | Plaques de torre (carril + número de torre) | — |
| `increaseLevel` | Pujades de nivell sumades de tot l'equip | — |

- `completionCount` és acumulatiu: quan puja entre dues lectures, ha passat l'esdeveniment.
- `completedFirst: true` marca que aquest equip ho ha fet primer (primer drac, primera torre, primer baró…).

### `structures[]`

- `type`: `tower`, `fortifier` (inhibidor) i `base` (nexe).
- `side` és el **propietari** de l'estructura (`blue` / `red`), no qui l'ha destruïda. `destroyed: true` + `side: red` vol dir que l'ha destruïda el blau.
- Per saber quina torre ha caigut (carril i posició), cal creuar-ho amb l'`objective` o amb `position`; l'`id` de l'estructura és genèric.

### Altres camps

| Camp | Valor real |
|---|---|
| `teams[].side` | `blue` / `red` (en minúscula) |
| `teams[].firstKill` | `true` a l'equip que ha fet la primera sang |
| `multikills[]` | `{numberOfKills: 2, count: 3}` = 3 dobles. Només hi surten les multikills que han passat. |
| `unitKills[].unitName` | `minion`, `neutralMinion`, `ward` (no inclou dracs ni baró: per a això, `objectives`) |
| `nonPlayerCharacters[].type` | `ATierNPC`, `BTierNPC`, `STierNPC` (genèrics, **no serveixen** per distingir monstres) |
| `draftActions[]` | 20 per partida. `type`: `ban` / `pick`; `drafter.type`: `team` (l'`id` és el de l'equip); `draftable.type`: `character`, `name`: nom del campió |
| `players[].character.name` | Nom del campió (`Olaf`) |
| `players[].roles` | **Buit**: GRID no dona la posició (top, jungla…) |
| `players[].inventory.items[].name` | Noms d'objectes en anglès |
| `titleVersion.name` | Versió del joc (`16.20`) |
| `games[].duration` | ISO 8601 **amb decimals** (`PT38M55.481S`) |
| `games[].clock.currentSeconds` | Segons de joc (2336 en una partida de 38:55) |
| `baronPowerPlays[].value` | Diferència d'or aconseguida amb cada baró |

## Primeres proves

Des de `backend/`, amb la clau al `.env`:

```bash
python scripts/grid_explore.py titles
python scripts/grid_explore.py tournaments --name World
python scripts/grid_explore.py series --title-id <id de LoL> --tournament World --out worlds_series.json
python scripts/grid_explore.py result <id d'una sèrie>
python scripts/grid_explore.py detail <id de la final> --out worlds_final.json
```

Comprovat el 20/11/2026: `series` retorna les 46 sèries de Worlds, Series State funciona per a LoL amb la clau d'Open Access i tots els camps de les consultes existeixen. Els únics que surten buits són `streams` i `players[].roles`.

Les respostes JSON (`worlds_series.json`, `worlds_final.json`) són dades de GRID: millor no pujar-les al repositori.

## Integració al backend

Implementada a `backend/src/leagueoftext/grid.py` (versió 2.2). La font de dades es tria amb `DATA_SOURCE` (`grid` per defecte, o `lolesports`).

`grid.py` exposa les mateixes funcions que `lolesports.py` i retorna les dades amb la mateixa forma, així que `Match`, `Game`, Firestore i l'app no canvien:

| Lambda | Funció de `grid.py` | Consulta |
|---|---|---|
| `GetScheduledMatches` | `iter_schedule_events()` | `allSeries` (des de fa 1 dia fins a l'horitzó), cada 6 h |
| `MatchIsLive` | `get_live_event(id)` | `seriesState`: `started` i no `finished`; després `series(id)` per als equips |
| `GameIsLive` | `get_game_window(id)` | `seriesState`: el joc amb aquest `sequenceNumber` ha començat |
| `UpdateGameState` | `get_game_window(id, previous_frame=…)` | `seriesState` 4 vegades per execució, cada 10 s |
| `UpdateGameState` | `find_game_winner()` | `games[].teams[].won` (reaprofita l'última lectura) |

Decisions:

- **Ids de joc sintètics** `<sèrie>-<número>` (p. ex. `3012986-2`): GRID no dona els ids dels jocs fins que comencen, i `Match` els necessita des del calendari.
- **Lliga**: el nom del torneig ha de començar pel nom d'una lliga de `LEAGUES` («Worlds - 2026 (Swiss: Swiss)» → `Worlds`). La fase (`blockName`) és el `nameShortened` del torneig («Swiss», «Knockouts»).
- **Fotograma**: cada lectura es converteix en un fotograma de `livestats` (`towers` = `destroyTower`, `inhibitors` = `destroyFortifier`, `barons` = `slayBaron`, `dragons` a partir dels `slay…Drake`). Porta també `clockSeconds` (temps de joc de GRID) i els comptadors de multikills, que `Game` fa servir quan hi són.
- **Ordre dels dracs**: GRID només dona quants n'hi ha de cada tipus. Es conserva l'ordre dels ja coneguts i els nous s'afegeixen al final; si en una mateixa lectura n'apareixen dos de tipus diferents, s'ordenen per tipus.
- **Jugadors**: els `participantId` (1-5 blau, 6-10 vermell) surten d'ordenar els jugadors per id de GRID, perquè siguin estables entre lectures.

Diferències amb l'API de LoL Esports:

- **Retard de 5 minuts** a les dades en directe d'Open Access.
- **4 lectures per minut** com a molt (el límit és 6 per sèrie). Si en una lectura han passat diverses coses, es notifiquen totes de cop.
- **Inici tardà**: si `GameIsLive` veu el joc quan ja fa estona que ha començat, `Game.start` compara el primer fotograma amb un joc a zero i anuncia el que s'havia perdut. Les kills es donen totals per jugador; la primera sang i la primera torre només s'atribueixen si GRID diu qui les va fer (`firstKill`, `completedFirst`). Si s'havien perdut més de 5 esdeveniments, no s'envien un per un: l'avís d'inici diu «… is already in progress (25:00).» i porta les estadístiques.
- **Lligues**: de moment Open Access només té Worlds 2026. Quan s'afegeixin la LCK, la LEC… cal comprovar que els noms dels tornejos comencen pel de la lliga.

## Referències

- Documentació de l'API: https://docs.grid.gg/public/documentation/api-documentation/getting-started/overview
- Referència de Central Data: https://docs.grid.gg/public/documentation/api-reference/central-data-feeds/central-data-feed-api
- Referència de Series State: https://docs.grid.gg/public/documentation/api-reference/live-data-feed/api-reference-series-state-api
- Paginació, límits i errors: https://docs.grid.gg/public/documentation/api-reference/getting-started/graphql
- Endpoints d'Open Access: https://grid.helpjuice.com/3673199
- Límits per producte: https://grid.helpjuice.com/en_US/client-help/rate-limits-for-products
- Sèries en directe: https://grid.helpjuice.com/en_US/client-help/can-i-get-a-list-of-series-that-are-currently-live
