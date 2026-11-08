# Runtime image pro backend. Jar se sestaví předem (`mvn verify package`), image jen balí výsledek:
#   docker build --platform linux/amd64 -t s74-vouchers:current .
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app && adduser -S -G app -h /app app
WORKDIR /app
COPY target/s74-vouchers.jar /app/app.jar
USER app

EXPOSE 8080
# Parametry JVM (paměť) se předávají přes JAVA_TOOL_OPTIONS v docker-compose, ať jdou měnit bez nového buildu.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
