FROM rust:1.90-bookworm AS bsk
RUN apt-get update && apt-get install -y --no-install-recommends git pkg-config libssl-dev && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/Tencent/BrowserSkill.git /src && cd /src && git checkout 3f10983c09fcd443c7420830ab984c9d409954bf && cargo +1.90.0 build --locked --release -p bsk

FROM eclipse-temurin:21-jre-noble
RUN mkdir -p /opt/bsk /var/lib/bsk
COPY --from=bsk /src/target/release/bsk /opt/bsk/bsk
COPY backend-java/skillforge-browser-service/build/libs/skillforge-browser-service.jar /app/browser.jar
ENV BSK_AUTO_UPDATE=off BSK_AUTO_START=0
EXPOSE 8300
ENTRYPOINT ["java","-jar","/app/browser.jar"]
