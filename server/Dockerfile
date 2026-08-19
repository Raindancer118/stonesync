FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# curl (health/status checks) + psql (ad-hoc DB inspection) for the console
# scripts below - both are read-only diagnostic tools, not app dependencies.
RUN apt-get update \
	&& apt-get install -y --no-install-recommends curl postgresql-client \
	&& rm -rf /var/lib/apt/lists/*

COPY --from=build /build/target/stonesync-server.jar app.jar
COPY console/pg-env.sh /usr/local/bin/pg-env.sh
COPY console/help console/ss-status console/ss-vaults console/ss-docs console/ss-updates console/ss-attachments console/ss-users console/ss-apikeys /usr/local/bin/
RUN chmod +x /usr/local/bin/pg-env.sh /usr/local/bin/help /usr/local/bin/ss-*

VOLUME ["/data/vault"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
