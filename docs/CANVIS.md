# Canvis respecte de la versió original (TFG-Salva)

## Seguretat: credencials retirades

| On era | Què era | Ara |
|---|---|---|
| `API Handler/secret_key.json` i el zip de cada Lambda | Clau privada del compte de servei de Firebase | Secrets Manager o `GOOGLE_APPLICATION_CREDENTIALS` |
| `RiotAPIUtils.py` | Clau `x-api-key` de l'API de LoL Esports | Variable `LOLESPORTS_API_KEY` |
| `app/src/main/res/values/strings.xml` | Dos *client secrets* d'OAuth (`GOCSPX-…`) i tres ids de client | Cap: l'id web el genera el plugin de Google Services |
| `TFGSalva/app/google-services.json` | Configuració del projecte de Firebase | Ignorat per git |
| Lambdes | ARN amb l'id del compte d'AWS | Es dedueix de l'ARN de la Lambda en execució |

> Aquestes claus continuen a l'historial del repositori antic. Cal **revocar-les i generar-ne de noves** (clau del compte de servei a Firebase, *client secrets* a Google Cloud Console).

## Errors corregits

### Backend

- `time.sleep()` sense `import time` a diverses Lambdes: qualsevol error de xarxa feia petar la funció.
- Reintents infinits (`while True`) a totes les peticions i a la cerca del guanyador: ara hi ha sessió HTTP amb reintents limitats, *timeout* i tornada a provar a la següent execució.
- Notificacions sobreescrites: l'id del document era el temps de joc, i dos esdeveniments en el mateix segon (p. ex. torre i kill) es trepitjaven. Ara l'id és un número de seqüència.
- Les estadístiques de les notificacions s'extreien tallant el text a posicions fixes (`text[-75:-73]`…): es trencaven amb noms llargs o xifres de tres dígits. Ara es desen com a camps.
- El temps de joc es desava com a data i es tornava a llegir tallant la cadena: ara són segons.
- Pauses: s'enviava «The game has been paused!» a cada fotograma en pausa, i la durada es comptava des de l'últim fotograma en pausa (no des del primer).
- Multikills: quan s'acabava la finestra de temps, el kill nou no començava una ratxa nova; i el càlcul dels segons des de l'últim kill tallava la cadena del `timedelta`.
- `if len(self.games) == 1: gameNumber == 0` era una comparació, no una assignació.
- Partits al millor de 2 (o qualsevol format que no fos Bo1/Bo3/Bo5) deixaven `necessaryWins` sense definir.
- `hasWinner()` enviava notificacions com a efecte secundari d'una comprovació.
- Si una Lambda s'invocava dues vegades, una partida es podia comptar dues vegades.
- Equips «TBD»: el partit passava a directe amb els equips del calendari i no s'actualitzaven mai.
- Partits ajornats: quedaven programats per sempre amb l'hora antiga, i `MatchIsLive` continuava executant-se cada minut.
- Si faltava menys d'una hora per al partit, la regla *one-shot* es programava en el passat i no s'executava mai.
- Fotogrames repetits entre finestres solapades generaven esdeveniments falsos.
- `updateInputRule` intentava esborrar un objectiu anomenat `Input` que no existia.
- Un permís de Lambda per partit programat: la política de recursos (20 KB) s'acabava omplint.

### App

- `DatabaseAdapter` heretava d'`AppCompatActivity`.
- Cada vegada que canviava una col·lecció s'afegien listeners nous (i no se'n treia cap): el nombre de listeners i de lectures creixia sense límit.
- `MyApplication` era un `object` que estenia `Application` i no estava registrat al manifest.
- ViewModels com a `object` globals, cadascun amb el seu `DatabaseAdapter`.
- `as String` sense comprovacions: qualsevol camp absent feia petar l'app.
- `GamesNotificationsListActivity` feia `!!` sobre llistes que podien ser nul·les i suposava que sempre hi havia la partida 1.
- `@RequiresApi(TIRAMISU)` a `onCreate`, i notificacions sense comprovar el permís.
- Les subscripcions es desaven llegint i reescrivint el document sencer (dos canvis seguits es podien perdre).
- La notificació de cada missatge es reemplaçava per la següent del mateix títol.
- Les dependències barrejaven `android.arch` (2017) amb AndroidX i dues versions del BoM de Firebase.

## Optimitzacions

- **Una sola col·lecció `Matches` amb camp `state`**, en lloc de copiar documents (i subcol·leccions) entre tres col·leccions a cada canvi d'estat.
- **Un listener per llista**, compartit entre pantalles i aturat quan ningú el mira; els jocs i esdeveniments només s'escolten a la pantalla de detall.
- **Memòria cau persistent de Firestore** (50 MB) per funcionar sense connexió, en lloc de duplicar les dades a Room.
- Escriptures en lot (`batch`) i enviament de notificacions amb `send_each`.
- `GetScheduledMatches` només escriu si el partit és nou o ha canviat.
- `UpdateGameState` recupera el retard (fins a 15 finestres per execució) si va endarrerida.
- Lambdes a Python 3.12 sobre arm64; R8 i reducció de recursos a la compilació *release* de l'app.

## Versió 2.1: Kotlin 2.4 i AGP 9

- Kotlin 2.0.21 → **2.4.21**, Android Gradle Plugin 8.7 → **9.5**, Gradle 8.11 → **9.6**.
- Kotlin integrat a AGP 9: s'ha tret el plugin `org.jetbrains.kotlin.android`, i `kotlinOptions {}` passa a `kotlin { compilerOptions {} }`.
- DSL nou d'AGP 9 per a `compileSdk`, `minSdk` i `targetSdk`. `compileSdk` i `targetSdk` passen a 37 (Compose 1.12 ho exigeix).
- Compose BOM 2026.09.00 (Compose 1.12), Firebase BoM 35.1.0, google-services 4.5.0, Lifecycle 2.11, Navigation 2.10, Activity 1.13, Credentials 1.6, coroutines 1.11. `material-icons-extended` es fixa a 1.7.8, perquè ja no s'actualitza amb el BOM.
- *Explicit backing fields* (estables a Kotlin 2.4) als repositoris: s'elimina el patró `_estat` + `asStateFlow()`.

## Interfície

Redissenyada amb Jetpack Compose seguint l'estil de Riot Mobile: fons gairebé negre, títols grans, fila de lligues amb selecció blanca, pestanyes amb subratllat daurat, capçaleres per dia, targetes amb el marcador amagat fins que es toca l'ull, i detall amb selector de partides 1-5, marcador en directe (or, kills, torres, dracs, barons, inhibidors) i cronologia d'esdeveniments amb icones i colors per bàndol.

Funcions noves: pestanya «Seguint», seguir partits sense iniciar sessió, obrir el partit en tocar una notificació, notificacions agrupades per partit, compartir un partit, opció per amagar resultats, interruptor general de notificacions, estirar cap avall per refrescar les dades (Esports, Seguint i detall del partit) i traducció al castellà i a l'anglès.

## Versió 2.2: dades oficials de GRID

- Nou client `backend/src/leagueoftext/grid.py` per a l'API oficial de GRID (Riot Games Open Access): calendari amb Central Data i estat de les sèries amb Series State (GraphQL, capçalera `x-api-key`).
- El client retorna les dades amb la mateixa forma que `lolesports.py` (esdeveniments del calendari i finestres de `livestats`), de manera que `Match`, `Game`, Firestore i l'app no canvien. La font es tria amb `DATA_SOURCE` (`grid` per defecte, o `lolesports`) a `datasource.py`.
- Com que GRID no dona els ids dels jocs abans que comencin, es fan servir ids sintètics `<sèrie>-<número>` (p. ex. `3012986-2`).
- `UpdateGameState` amb GRID llegeix l'estat 4 vegades per execució, cada 10 s (el límit d'Open Access és 6 per minut i sèrie), i ja no ha d'estimar el guanyador: GRID l'indica.
- `Game`: si el fotograma porta el rellotge de joc (`clockSeconds`) es fa servir en lloc de calcular-lo amb les marques de temps, i si porta els comptadors de multikills de GRID es fan servir en lloc de la finestra de 10 s.
- Plantilla SAM: paràmetres nous `DataSource`, `GridApiKeySecretArn` i `GridTitleId`; `LolEsportsApiKey` passa a ser opcional. La clau de GRID es guarda a Secrets Manager (un paràmetre `NoEcho` no es desa a `samconfig.toml` i quedava buit en tornar a desplegar).
- Lambdes i capa a **x86_64** en lloc d'arm64: `sam build --use-container` en un PC x86 havia d'emular arm64 (lent i amb risc d'errors en compilar `grpcio`). Costa un ~20% més, però amb aquest volum continua dins de la capa gratuïta.
- Inici tardà: si `GameIsLive` troba el joc ja començat, `Game.start` anuncia el que s'havia perdut (kills totals per jugador; primera sang i primera torre només si la font diu qui les va fer). Si són més de 5 esdeveniments, l'avís d'inici diu que el joc ja està en marxa en lloc d'enviar-los un per un.
- `backend/scripts/import_grid_series.py` (lògica a `backfill.py`): importa a Firestore partits ja jugats a partir de l'estat final de GRID, amb detecció de duplicats. Serveix per carregar el Mundial 2026.
- `backend/scripts/grid_explore.py` per provar les consultes i desar les respostes en JSON. Documentació a `docs/GRID_API.md`.
- Tests nous (`tests/test_grid.py` i el flux complet amb GRID a `tests/test_handlers_flow.py`).

### App

- Vista de calendari opcional (mensual, setmanal o diària) a Pròxims i Resultats, que s'activa al Perfil.
- Filtre per una o diverses lligues alhora.
- Pestanya inicial segons el contingut (En directe > Pròxims > Resultats); mentre no se'n tria cap, s'escolten les tres llistes i després només la visible. A En directe es mostren només les targetes, sense calendari.
- Dades de demostració (`leagueoftext.mockData`, només debug): la interfície `MatchSource` separa les pantalles de l'origen de les dades, i `MockMatchRepository` genera partits programats i en directe que avancen en temps real. Resultats i els partits que no són de demostració es continuen llegint de Firestore, d'on també s'agafen els logotips.
- `versionName` 2.2.0 (`versionCode` 4).
