# Multi-stage build for the Spring Boot backend
# Builder: use Maven + JDK to produce the fat jar
FROM maven:3.9.4-eclipse-temurin-21 AS builder
WORKDIR /workspace
# copy sources
COPY pom.xml .
COPY src ./src
# If you have additional modules (frontend etc) that slow down build,
# you can copy only the java sources. Using full package to be safe.
RUN mvn -B -DskipTests package

# Runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
ARG JAR_FILE=target/My-AI-0.0.1-SNAPSHOT.jar
COPY --from=builder /workspace/${JAR_FILE} ./app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]

