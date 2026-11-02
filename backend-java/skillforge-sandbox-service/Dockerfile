ARG SERVICE_BASE_IMAGE=public.ecr.aws/docker/library/eclipse-temurin:21-jre-alpine@sha256:1a29e1fe337eb28b5bec30f0ee8ed29f0ff80ab6f75dcf9313efe82911065a52
FROM ${SERVICE_BASE_IMAGE}
ENV TZ=Asia/Shanghai
RUN apk add --no-cache docker-cli tzdata \
    && addgroup -S skillforge \
    && adduser -S -G skillforge skillforge
WORKDIR /app
COPY skillforge-sandbox-service.jar /app/service.jar
EXPOSE 8200
ENTRYPOINT ["java", "-Duser.timezone=Asia/Shanghai", "-XX:MaxRAMPercentage=75", "-jar", "/app/service.jar"]
