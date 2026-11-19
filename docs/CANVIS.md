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

## Interfície

Redissenyada amb Jetpack Compose seguint l'estil de Riot Mobile: fons gairebé negre, títols grans, fila de lligues amb selecció blanca, pestanyes amb subratllat daurat, capçaleres per dia, targetes amb el marcador amagat fins que es toca l'ull, i detall amb selector de partides 1-5, marcador en directe (or, kills, torres, dracs, barons, inhibidors) i cronologia d'esdeveniments amb icones i colors per bàndol.

Funcions noves: pestanya «Seguint», seguir partits sense iniciar sessió, obrir el partit en tocar una notificació, notificacions agrupades per partit, compartir un partit, opció per amagar resultats, interruptor general de notificacions i traducció al castellà i a l'anglès.
