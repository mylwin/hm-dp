FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
ENV MAVEN_MIRROR_URL=${MAVEN_MIRROR_URL}
COPY maven-settings.xml /build/maven-settings.xml
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -s /build/maven-settings.xml -Dmaven.wagon.http.retryHandler.count=3 -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/target/backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
