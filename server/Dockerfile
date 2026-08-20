FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# curl (admin API + health calls) + psql (ad-hoc DB inspection) + jq (formatting
# admin API responses) for the console scripts below - diagnostic/ops tooling,
# not app dependencies.
RUN apt-get update \
	&& apt-get install -y --no-install-recommends curl postgresql-client jq \
	&& rm -rf /var/lib/apt/lists/*

COPY --from=build /build/target/stonesync-server.jar app.jar
COPY console/pg-env.sh console/admin-env.sh console/api-env.sh /usr/local/bin/
COPY console/help \
     console/ss-status console/ss-vaults console/ss-docs console/ss-updates console/ss-attachments console/ss-users console/ss-apikeys \
     console/ss-create-user console/ss-delete-user console/ss-create-vault console/ss-delete-vault \
     console/ss-grant-access console/ss-revoke-access console/ss-create-apikey console/ss-revoke-apikey \
     console/ss-create-invite \
     console/ss-git-log console/ss-vault-restore \
     console/ss-access console/ss-rule-set console/ss-rule-rm console/ss-audit \
     console/ss-file-history console/ss-make-admin \
     /usr/local/bin/
RUN chmod +x /usr/local/bin/pg-env.sh /usr/local/bin/admin-env.sh /usr/local/bin/api-env.sh /usr/local/bin/help /usr/local/bin/ss-*

VOLUME ["/data/vault"]
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
