# Agent Gmail Spring Boot

Application monolithique Spring Boot 3.x en Java 17 qui surveille Gmail en continu, lit les nouveaux emails de la boite de reception, genere une reponse avec OpenAI `gpt-4o-mini`, envoie la reponse, puis affiche les etapes dans une interface Thymeleaf via SSE.

## Configuration OpenAI

Dans `src/main/resources/application.properties`, remplacez :

```properties
openai.api.key=xxx
openai.model=gpt-4o-mini
```

par votre cle API OpenAI.

## Configuration Google Gmail

Le fichier `credentials.json` est le fichier OAuth fourni par Google Cloud. Il permet a l'application de demander l'autorisation d'acceder au compte Gmail utilise pour les tests.

Pour le generer :

1. Ouvrez la [Google Cloud Console](https://console.cloud.google.com/).
2. Creez un nouveau projet ou selectionnez un projet existant.
3. Dans le menu **APIs et services > Bibliotheque**, cherchez **Gmail API**.
4. Ouvrez **Gmail API**, puis cliquez sur **Activer**.
5. Allez dans **APIs et services > Ecran de consentement OAuth**.
6. Choisissez le type d'utilisateur adapte :
   - **External** pour un compte Gmail classique.
   - **Internal** uniquement si vous utilisez Google Workspace et que l'application reste dans votre organisation.
7. Renseignez les informations obligatoires de l'application, puis sauvegardez.
8. Si l'ecran OAuth est en mode **Testing**, ajoutez le compte Gmail de test dans **Test users**.
9. Allez dans **APIs et services > Identifiants**.
10. Cliquez sur **Creer des identifiants > ID client OAuth**.
11. Selectionnez le type d'application **Application Web**.
12. Dans **URI de redirection autorises**, ajoutez exactement :

```text
http://localhost:8888/Callback
```

13. Cliquez sur **Creer**.
14. Telechargez le fichier JSON du client OAuth.
15. Renommez ce fichier en `credentials.json`.
16. Placez `credentials.json` a la racine du projet, au meme niveau que `pom.xml`.

Exemple d'emplacement attendu :

```text
Agent-Gmail/
  credentials.json
  pom.xml
  src/
```

Au premier lancement de l'agent, une page Google s'ouvre pour autoriser les scopes Gmail.

Les tokens OAuth sont ensuite stockes localement dans le dossier `tokens/`.

Le port est configurable via `gmail.oauth.callback.port` dans `src/main/resources/application.properties`.

Si vous changez de compte Gmail de test ou de fichier `credentials.json`, supprimez le token local pour forcer une nouvelle autorisation :

```powershell
Remove-Item -Recurse -Force .\tokens
```

Si les scopes Gmail changent, supprimez le dossier local `tokens/` avant de relancer l'application. Google affichera alors un nouveau consentement OAuth avec les nouvelles permissions. Sur PowerShell :

```powershell
Remove-Item -Recurse -Force .\tokens
```

## Agent autonome

L'agent scanne la boite de reception Gmail avec une requete de ce type :

```text
in:inbox -from:me -label:AgentGmailTraite after:yyyy/MM/dd
```

Au demarrage, l'application memorise l'heure de lancement. Elle ignore ensuite tous les emails dont la date interne Gmail est anterieure a ce lancement, meme s'ils n'ont pas encore le label `AgentGmailTraite`.

Apres reponse envoyee, l'email entrant est marque avec le label `AgentGmailTraite` pour eviter les doubles reponses.

Le polling est configurable dans `src/main/resources/application.properties` :

```properties
agent.poll.enabled=true
agent.poll.start-on-boot=false
agent.poll.initial-delay-ms=10000
agent.poll.fixed-delay-ms=60000
agent.poll.batch-size=5
```

## Lancement

```bash
mvn spring-boot:run
```

Ouvrez ensuite :

```text
http://localhost:8080
```

L'application expose un seul mode automatique. Ouvrez l'interface, cliquez sur **Demarrer** pour activer la surveillance, puis sur **Arreter** pour la suspendre. Quand l'agent est actif, il scanne Gmail en continu selon la frequence configuree.
