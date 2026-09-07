# =============================================================================
# Image de l API GARAH
# =============================================================================
# Construction en DEUX ETAPES. Ce n est pas de la coquetterie :
#
#   sans      l image finale contient le JDK, Maven, les sources et tout le
#             depot de dependances                        → ~800 Mo
#   avec      elle ne contient que le JRE et le jar        → ~250 Mo
#
# Sur Render, une image plus petite demarre plus vite — et l offre gratuite
# endort l instance, donc chaque reveil paie ce demarrage (D-14).
# =============================================================================

# --- Etape 1 : construction --------------------------------------------------
FROM eclipse-temurin:24-jdk AS construction

WORKDIR /build

# On copie D ABORD le pom et le wrapper, PAS les sources.
#
# Docker met chaque etape en cache. Tant que le pom ne change pas, la couche
# de telechargement des dependances est reutilisee — et un commit qui ne
# touche que du code Java ne retelecharge pas Spring Boot.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# chmod +x AVANT tout appel au wrapper.
#
# ⚠️ Sans cette ligne, le premier deploiement echoue sur :
#        /bin/sh: 1: ./mvnw: Permission denied
#
# Pourquoi : git sur Windows ne suit pas le bit executable (core.filemode
# vaut false). Le fichier part donc en 100644, et Linux refuse de l executer.
# Le bit a ete corrige dans l index (git update-index --chmod=+x), mais on ne
# s en remet pas a ca : il suffit qu un mvnw soit un jour re-ajoute depuis
# Windows pour que le probleme revienne — et il ne se verrait qu au
# deploiement suivant, jamais en local ou tout marche.
#
# Une ligne ici vaut mieux qu une convention que personne ne peut verifier.
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

# Les sources ensuite : c est la couche qui change a chaque commit.
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package

# --- Etape 2 : execution -----------------------------------------------------
FROM eclipse-temurin:24-jre

# Un utilisateur non privilegie. Si l application est compromise, l attaquant
# n est pas root dans le conteneur — ca ne resout pas tout, mais ca coute une
# ligne.
RUN useradd --create-home --shell /bin/false garah
USER garah
WORKDIR /app

COPY --from=construction /build/target/*.jar application.jar

# =============================================================================
# Memoire : tenir dans les 512 Mio de l offre gratuite
# =============================================================================
# MaxRAMPercentage plutot que -Xmx : la JVM lit la limite du CONTENEUR.
# Ecrire -Xmx256m en dur casserait le jour ou on change de plan Render.
#
# /!\ CE POURCENTAGE ETAIT A 75, ET LE DEPLOIEMENT MOURAIT AVANT D OUVRIR SON
#     PORT :
#
#         ==> No open ports detected, continuing to scan...
#         ==> Out of memory (used over 512Mi)
#
#     L erreur de raisonnement est de lire « 75 % de 512 » comme un budget
#     total. Ce pourcentage ne regit que le TAS. Tout le reste s y ajoute :
#
#         tas            75 % de 512      384 Mio
#         metaspace      43 depots JPA, Hibernate, SDK AWS   ~150 Mio
#         piles          ~50 threads x 1 Mio                  ~50 Mio
#         cache de code  compilation a quatre niveaux         ~40 Mio
#         natif          tampons directs, TLS                 ~30 Mio
#                                                     -----------------
#                                                            ~654 Mio
#
#     Le conteneur etait donc condamne des le demarrage. Render garde alors
#     l ANCIENNE version en ligne : le service repond, tout a l air normal, et
#     seul le journal de deploiement dit que rien n a ete livre. C est le mode
#     de panne le plus couteux — on cherche le defaut dans le code pendant que
#     le code n est jamais parti.
#
# Le budget vise, sur 512 Mio :
#
#     tas            40 %                       ~205 Mio
#     metaspace      plafonne                    144 Mio
#     piles          ~50 threads x 512 Kio        ~25 Mio
#     cache de code  un seul niveau de JIT        ~15 Mio
#     natif                                       ~40 Mio
#                                          ---------------
#                                                ~429 Mio, ~80 de marge
#
# MaxMetaspaceSize est PLAFONNE volontairement. Sans plafond, le metaspace
# grandit jusqu a ce que le conteneur soit tue — et le journal ne dit alors
# rien d autre que « Out of memory », sans designer le coupable. Avec, on
# obtient une OutOfMemoryError: Metaspace, qui se lit.
#
# TieredStopAtLevel=1 : on s arrete au compilateur C1. Ce qu on perd, c est le
# debit maximal une fois l application chaude ; ce qu on gagne, c est un cache
# de code trois fois plus petit ET un demarrage bien plus court — decisif ici,
# ou le processeur est bride et ou chaque reveil de l instance endormie repaie
# ce demarrage (D-14). A retirer le jour d un plan payant.
#
# UseSerialGC : le ramasse-miettes a la plus petite empreinte. Sur un seul
# coeur, les collecteurs paralleles ne servent a rien et coutent des threads.
# =============================================================================
# Sur UNE seule ligne, volontairement : une continuation « \ » dans un ENV se
# comporte differemment selon les versions de BuildKit, et un reglage memoire
# qui se transforme en chaine vide ne se voit qu au moment ou le conteneur
# meurt.
#
# ─── Revision du 07/09/2026 : l instance est morte en demarrant ──────────────
#
# « Out of memory (used over 512Mi) », juste apres QueryEnhancerFactory —
# c est-a-dire pendant que Spring Data compile les requetes des depots.
#
# ⚠️ LE PREMIER REFLEXE ETAIT LE MAUVAIS. Reduire le tas semblait logique
#    « pour laisser de la place au natif ». Reproduit localement avec
#    -XX:MaxRAM=512m, le journal a repondu :
#
#        Terminating due to java.lang.OutOfMemoryError: Java heap space
#
#    Ce n etait donc PAS le natif : c est le tas qui manquait, et le rogner
#    aggravait exactement ce qu il fallait soulager.
#
# Ce que la mesure a montre, chiffre par chiffre :
#
#   tas 205 Mo (40%)              OOM au demarrage
#   tas 205 Mo + lazy-init beans  OOM au demarrage
#   tas 307 Mo (60%)              demarre — 132 Mo vivants en tenured
#   tas 281 Mo (55%)              465 Mo de RSS, encore en train de demarrer
#
# Le pic n est pas la taille de l application : c est la COMPILATION DE
# TOUTES LES REQUETES D UN SEUL COUP. Une trentaine de @Query, dont
# plusieurs de trente lignes avec sous-requetes, analysees en rafale.
#
# La solution n est donc pas d agrandir le tas — elle est d etaler le pic.
# `spring.data.jpa.repositories.bootstrap-mode=lazy` (application.yml) cree
# les proxys de depot a la PREMIERE UTILISATION plutot qu au demarrage.
#
#   tas 230 Mo (45%) + bootstrap-mode lazy   demarre, repond,
#                                            245 Mo de RSS mesures
#
# Les autres reglages restent utiles et sont conserves :
#   - MaxMetaspaceSize plafonne, pour obtenir une erreur lisible plutot qu un
#     conteneur tue sans explication ;
#   - ReservedCodeCacheSize a 40 Mo : avec TieredStopAtLevel=1 on ne compile
#     qu en C1, et le defaut de 240 Mo reserve un espace jamais utilise ;
#   - UseSerialGC : la plus petite empreinte, et sur un seul cœur les
#     collecteurs paralleles ne servent a rien.
#
# ⚠️ 245 Mo laissent de la marge, mais elle n est pas infinie. Chaque lot
#    ajoute des entites et des requetes. Le jour ou ca remeurt, la reponse
#    n est plus de rogner : c est un plan payant, ou un autre hebergeur.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=45 -XX:MaxMetaspaceSize=112m -XX:ReservedCodeCacheSize=40m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"

# Render fournit le port dans $PORT. application.yml lit ${PORT:8080}.
EXPOSE 8080

# La forme "exec" (sh -c) est necessaire pour que $JAVA_OPTS soit developpe.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar application.jar"]
