FROM gradle:8.14.3-jdk21 AS build
ENV TZ=Asia/Shanghai
WORKDIR /workspace
COPY --chown=gradle:gradle . .
RUN gradle :skillforge-gateway:bootJar --no-daemon

FROM eclipse-temurin:21-jre
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY --from=build /workspace/skillforge-gateway/build/libs/skillforge-gateway.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]
