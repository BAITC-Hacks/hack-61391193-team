# Build from the repository root so the prepared map data is included in the JAR.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace/backend-akim
COPY backend-akim/pom.xml ./pom.xml
COPY backend-akim/src/main ./src/main
COPY data1/overture/astana/ /workspace/data1/overture/astana/
RUN mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/backend-akim/target/backend-akim-0.0.1-SNAPSHOT.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
