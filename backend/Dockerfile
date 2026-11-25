FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /var/dicomhub/storage/original-repo \
                /var/dicomhub/storage/working-copies \
                /var/dicomhub/storage/ai-results
COPY --from=build /workspace/target/dicom-hub-backend-*.jar /app/app.jar
EXPOSE 8080 11112
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
