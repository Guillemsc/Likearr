FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode clean verify

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/likearr-1.0.0.jar /app/likearr.jar
ENV TZ=Europe/Amsterdam
ENTRYPOINT ["java", "-jar", "/app/likearr.jar"]
