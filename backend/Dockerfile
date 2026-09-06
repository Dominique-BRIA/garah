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
RUN ./mvnw -B -ntp dependency:go-offline

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

# MaxRAMPercentage plutot que -Xmx : la JVM lit la limite du CONTENEUR.
# Ecrire -Xmx512m en dur casserait le jour ou on change de plan Render.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

# Render fournit le port dans $PORT. application.yml lit ${PORT:8080}.
EXPOSE 8080

# La forme "exec" (sh -c) est necessaire pour que $JAVA_OPTS soit developpe.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar application.jar"]
