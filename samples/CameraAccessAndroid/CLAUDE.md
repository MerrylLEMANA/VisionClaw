# Projet : Assistant vocal Claude pour Ray-Ban Meta

## Contexte
Application Android (Kotlin) qui transforme les Ray-Ban Meta Gen 2 en interface vocale
mains libres vers Claude (+ Gemini en secours/gratuit). Base de code : fork de
`Intent-Lab/VisionClaw` (`samples/CameraAccessAndroid`), qui fournit déjà l'intégration
DAT (Meta Wearables Device Access Toolkit), le flux caméra/micro Bluetooth, et une
connexion Gemini Live fonctionnelle.

Utilisateur : mathématicien + génie civil/informatique. Cas d'usage différenciateur :
pointer une formule/un calcul du regard et obtenir une vérification ou une solution
("mode solveur", voir plus bas).

**Cible : un jalon fonctionnel et démontrable, pas l'app finale parfaite du premier coup.**

## Stack et contraintes connues
- Android (Kotlin), pas iOS (pour rester sans Mac). Pixel 3 = banc de test minimal ;
  doit aussi marcher sur Samsung/autres Android récents.
- DAT SDK Meta = en developer preview. Compte créé via wearables.developer.meta.com
  (⚠️ inscription desktop only, pas mobile).
- MockDeviceKit disponible : permet de tester SANS lunettes physiques ni Application ID
  réel (utiliser `0` en placeholder dans AndroidManifest avec Developer Mode).
- Le micro passe par Bluetooth HFP/SCO — flux 8kHz, et c'est CE lien qui plafonne la
  batterie des lunettes en écoute continue (~150 mAh), pas une limite logicielle.
- GitHub Packages exige un Personal Access Token (`read:packages`) dans `local.properties`.

## Décisions actées (ne pas re-discuter sans raison)
- **Déclenchement** : wake-word (cascade VAD léger → Porcupine, pas un wake-word lourd
  permanent) ET double-tap — les deux co-égaux, toujours disponibles. Le double-tap
  n'est jamais limité par la batterie (il n'ouvre le SCO qu'à l'usage).
- **Multi-fournisseurs** : Gemini Flash (gratuit, déjà câblé dans le repo, via
  GeminiLiveService) par défaut ; Claude (Haiku/Sonnet/Opus, modèle dans la config,
  jamais en dur) pour le raisonnement, surtout les maths.
- **Double sortie obligatoire** : réponse parlée concise ("langage naturel simplifié" —
  jamais lire des symboles un par un, résultat d'abord, détail sur "détaille") +
  version écrite complète (notation réelle) dans un journal local.
- **Mode solveur / profils experts** : pas juste les maths — profils extensibles
  (maths, génie civil, code, finance) avec une couche commune de calcul/vérification
  PAR EXÉCUTION DE CODE (jamais de calcul "de tête" du LLM pour des résultats vérifiables).
- **UX audio** : barge-in (interruption), "répète", "détaille" — interface sans écran,
  donc tout passe par la voix et des indices sonores courts.
- **Français** de bout en bout (STT, TTS, prompts).
- Vision : capture/sélection multi-frame + photo haute résolution plutôt qu'une frame
  unique du flux — une formule ou un graphe ne se lit pas bien en 720p.

## Roadmap (ordre de construction, voir spec v1.2 pour le détail)
Spike 0 (faisabilité écoute continue : batterie, stabilité SCO, faux réveils) en
parallèle de v0.1 (double-tap → STT → Gemini Flash → TTS) → v0.2 (Claude + routage +
journal) → v0.3 (vision + mode solveur) → v0.4 (wake-word promu défaut) → v0.5
(streaming complet, barge-in, finitions).

## État actuel du projet (mettre à jour à chaque session)
- [x] Repo `Intent-Lab/VisionClaw` cloné et exploré.
- [x] Squelette Claude écrit (non testé/compilé) dans `claude-skeleton/` :
      `ClaudeConfig.kt`, `SttTtsEngines.kt`, `ClaudeLiveService.kt`,
      `AndroidSttEngine.kt`, `AndroidTtsEngine.kt`, `INTEGRATION.md`.
- [x] Fichiers copiés dans `app/src/main/java/.../cameraaccess/claude/`.
- [x] `SettingsManager.kt` étendu avec les champs Claude (voir INTEGRATION.md §1).
- [x] `SettingsScreen.kt` étendu avec les champs UI Claude (voir INTEGRATION.md §2).
- [x] `StreamViewModel.kt` câblé pour basculer Gemini/Claude (voir INTEGRATION.md §3).
- [x] Premier build réussi.
- [x] Test "bouton debug → sendTextMessage → Claude répond" (avant l'audio complet).
- [x] Test MockDeviceKit.
- [x] `ClaudeSessionViewModel.kt` créé — pipeline STT→Claude→TTS complet, sans `audioManager.startCapture()`.
- [x] `ControlsRow.kt` étendu : bouton robot orange = Claude.
- [x] `StreamScreen.kt` câblé : overlay Claude, dispose, erreurs Toast.
- [x] `gradle.properties` corrigé : `org.gradle.java.home=C:\AndroidStudio\jbr` (JDK 21).
- [x] Test bouton Claude en mode Phone — répond vocalement. ✓
- [x] v0.3 vision : bouton Photo pendant session Claude → frame attachée au prochain message. ✓
- [x] Pipeline complet validé en mode Phone : STT (fr) → Claude → TTS → audio + vision. ✓
- [x] Mode solveur validé : code_execution_20250825, calcul Python server-side, résultat numérique réel. ✓
- [x] Qualité TTS : voix fr-fr-x-frc-local (qualité HIGH), vitesse 0.75, sanitizeForTts() complet. ✓
- [x] Echo éliminé : pause/resume SpeechRecognizer pendant TTS. ✓
- [x] Wake-word opérationnel (JARVIS/Claude via SpeechRecognizer — solution temporaire téléphone). ✓
- [x] Application ID Meta réel enregistré dans manifest (1211030581168329). ✓
- [ ] Lunettes en main (arrivée lundi 2026-06-30) + Spike 0 premier test matériel.

## Pièges déjà identifiés (ne pas re-découvrir en debug)
1. **Conflit micro** : `SpeechRecognizer` et `AudioManager` (AudioRecord de Gemini) ne
   peuvent pas tenir le micro en même temps. Ne jamais démarrer `audioManager.startCapture()`
   quand le fournisseur actif est Claude.
2. **Sample rate TTS non garanti** : `AndroidTtsEngine` lit le vrai sample rate dans
   l'en-tête WAV produit par `synthesizeToFile()` — ne pas supposer 24000 Hz à l'avance
   pour l'AudioTrack de sortie. `ClaudeSessionViewModel.ensurePlaybackTrack()` (re)crée
   l'AudioTrack à ce rate réel via le callback `onActualSampleRateKnown`.
3. **Inscription Meta developer = desktop uniquement**, pas mobile.
4. **AudioManager (Gemini) couple capture ET lecture** : `playAudio()` est ignoré si
   `startCapture()` n'a pas été appelé (gate `isCapturing`), et l'AudioTrack n'est créé
   que dans `startCapture()`. INUTILISABLE pour Claude (qui ne capture jamais). Claude a
   donc son propre `playbackTrack` dans `ClaudeSessionViewModel`, en `USAGE_MEDIA`
   (speaker), pas `USAGE_VOICE_COMMUNICATION`.
5. **fr-CA pas installé partout** : ni le STT (`AndroidSttEngine`) ni le TTS
   (`AndroidTtsEngine`) ne doivent forcer `fr-CA` — le STT produit ERROR_NO_MATCH
   silencieux et le TTS produit un WAV vide (ENOENT). Utiliser `fr-FR` avec repli sur
   le locale système / `Locale.FRENCH`.
6. **TTS : listener écrasé** : l'engine `TextToSpeech` est partagé et
   `setOnUtteranceProgressListener` écrase le précédent. Comme `ClaudeLiveService`
   synthétise phrase par phrase, le `onDone` doit reconstruire le fichier WAV depuis
   l'`utteranceId` reçu en paramètre, JAMAIS depuis un `tempFile` capturé en closure.
7. **`org.gradle.java.home`** doit pointer sur le JBR d'Android Studio
   (`C:\AndroidStudio\jbr`, JDK 21). Le JDK 25 système casse AGP 8.6.0 (`25.0.1`).
8. **`WakeWordEngine` = solution temporaire téléphone** : utilise `SpeechRecognizer` en
   boucle courte — valide pour les tests sur Pixel 3 (micro téléphone). Incompatible avec
   le flux HFP/SCO des lunettes : garder le lien SCO ouvert en permanence pour le wake-word
   viderait la batterie (~150 mAh, piège micro). Avant le passage sur lunettes, remplacer
   par un détecteur on-device léger (Porcupine ou openWakeWord) qui tourne sur AudioRecord
   local, pas sur le SCO. Tâche planifiée pour v0.4 (pas maintenant, bloquée sur compte
   Meta developer + Spike 0). Voir spec §5.3/§7.

## Gestion des tokens / limites d'usage (décidé — Niveaux 0 et 1 uniquement)

Pas d'outils tiers à hooks/MCP pour ça (risque trop élevé pour un projet qui contient
des clés API Claude + Gemini). Uniquement :

**Niveau 0 — commandes intégrées à utiliser activement pendant les sessions :**
- `/context` en début de session pour connaître le coût de base réel.
- `/compact` manuel à des moments logiques, plutôt que d'attendre la compaction
  automatique (qui arrive tard et perd plus de nuance).
- `/cost` pour suivre la consommation.
- Sous-agents (`Task` tool) pour toute exploration de code volumineuse (ex. fouiller
  le SDK DAT ou les fichiers VisionClaw) — ça lit beaucoup mais ne retourne qu'un
  résumé à la session principale, qui reste légère.
- `/model` pour rester sur Sonnet par défaut, monter sur Opus ponctuellement pour les
  décisions d'architecture difficiles (cohérent avec la spec §6.4 et nos décisions plus haut).

**Niveau 1 — règles de sortie concise (source : `drona23/claude-token-efficient`, MIT,
fichier texte seul, pas de code exécuté — vérifié avant adoption) :**

- Pas de formules d'ouverture ou de clôture creuses ("Bonne question !", "J'espère que
  ça aide !"). La réponse commence directement.
- Pas de reformulation de la question avant de répondre.
- Pas de tirets cadratins ni de guillemets typographiques décoratifs — ASCII simple,
  pour rester copier-collable sans accroc.
- Ne jamais deviner une API, une version, un flag, ou un nom de package — vérifier en
  lisant le code ou la doc avant d'affirmer. (Particulièrement important ici : le SDK
  DAT est en developer preview, deviner une signature = bug garanti.)
- Lire un fichier avant de le modifier. Ne jamais éditer à l'aveugle.
- Solution la plus simple qui fonctionne. Pas de sur-ingénierie, pas d'abstraction pour
  un usage unique, pas de fonctionnalités spéculatives non demandées.
- Pour une revue/debug : énoncer le bug, montrer le correctif, s'arrêter là — pas de
  suggestions hors du périmètre demandé.
- Si la cause d'un bug n'est pas claire après lecture du code concerné : le dire
  explicitement plutôt que de deviner.
- Les instructions explicites de l'utilisateur priment toujours sur ces règles (s'il
  demande une explication détaillée, la fournir sans résister).

## Ce que l'utilisateur peut faire / ne peut pas faire lui-même
- Sait utiliser Android Studio à un niveau basique, ne connaît pas bien Claude Code.
- Peut agir sur le matériel physique (brancher le téléphone, porter les lunettes,
  appuyer sur le double-tap, dire des phrases test) — c'est lui qui doit le faire,
  jamais moi.
- Préfère des explications concrètes, peu de jargon non expliqué, et qu'on lui dise
  clairement quand quelque chose est incertain/pas testé plutôt que de survendre.
