FROM gradle:8.14.3-jdk21 AS build
ENV TZ=Asia/Shanghai
WORKDIR /workspace
COPY --chown=gradle:gradle . .
RUN gradle :skillforge-platform-service:bootJar --no-daemon

FROM eclipse-temurin:21-jre
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY --from=build /workspace/skillforge-platform-service/build/libs/skillforge-platform-service.jar app.jar
EXPOSE 8100
ENTRYPOINT ["java", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]
