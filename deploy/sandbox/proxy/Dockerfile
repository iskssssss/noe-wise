ARG PYTHON_BASE=public.ecr.aws/docker/library/python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7

FROM ${PYTHON_BASE} AS ca-init
ENV TZ=Asia/Shanghai
RUN apt-get update && apt-get install -y --no-install-recommends tzdata && rm -rf /var/lib/apt/lists/*
RUN apt-get update \
    && apt-get install -y --no-install-recommends openssl \
    && rm -rf /var/lib/apt/lists/*
COPY init-ca.sh /usr/local/bin/init-ca
RUN chmod 0555 /usr/local/bin/init-ca
ENTRYPOINT ["/usr/local/bin/init-ca"]

FROM ${PYTHON_BASE} AS proxy
ENV TZ=Asia/Shanghai
RUN apt-get update && apt-get install -y --no-install-recommends tzdata && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir mitmproxy==11.0.2 \
    && groupadd --gid 65532 sandbox \
    && useradd --uid 65532 --gid 65532 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin sandbox \
    && mkdir -p /opt/skillforge
COPY network_guard.py /opt/skillforge/network_guard.py
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["mitmdump", "--listen-host", "0.0.0.0", "--listen-port", "8080", "--set", "confdir=/ca", "--set", "connection_strategy=lazy", "--set", "rawtcp=false", "--set", "tcp_hosts=^$", "--set", "udp_hosts=^$", "--set", "websocket=false", "--scripts", "/opt/skillforge/network_guard.py"]
