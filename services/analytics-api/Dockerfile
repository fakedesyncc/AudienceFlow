FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /src
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre

RUN useradd --system --create-home --shell /usr/sbin/nologin audienceflow
USER audienceflow

COPY --from=build /src/target/analytics-api-0.1.0.jar /app/analytics-api.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/analytics-api.jar"]
