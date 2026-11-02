ARG PYTHON_BASE=public.ecr.aws/docker/library/python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7
ARG NODE_BASE=public.ecr.aws/docker/library/node:24.8.0-bookworm-slim@sha256:cadbfafeb6baf87eaaffa40b3640209c4b7fd38cebde65059d15bc39cd636b85

FROM ${PYTHON_BASE} AS python-runtime
ENV TZ=Asia/Shanghai \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    DOCLING_ARTIFACTS_PATH=/opt/skillforge/docling-models
COPY docling-requirements.txt /opt/skillforge/docling-requirements.txt
COPY download_docling_models.py docling-models.lock.json /opt/skillforge/
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && printf 'Acquire::Retries "2";\n' > /etc/apt/apt.conf.d/80-skillforge-retries \
    && apt-get update && apt-get install -y --no-install-recommends \
        tzdata libreoffice-core libreoffice-common libreoffice-writer libreoffice-calc libreoffice-impress \
        poppler-utils fonts-noto-cjk tesseract-ocr tesseract-ocr-chi-sim tesseract-ocr-eng \
    && python -m pip install --no-cache-dir --only-binary=:all: openpyxl==3.1.5 et-xmlfile==2.0.0 \
    && python -m pip install --no-cache-dir --only-binary=:all: --no-deps --require-hashes -r /opt/skillforge/docling-requirements.txt \
    && python -m pip check \
    && python /opt/skillforge/download_docling_models.py --lock /opt/skillforge/docling-models.lock.json --output /opt/skillforge/docling-models \
    && chmod -R a+rX /opt/skillforge/docling-models \
    && rm -rf /var/lib/apt/lists/*
COPY docling-extra-requirements.txt /opt/skillforge/docling-extra-requirements.txt
RUN python -m pip install --no-cache-dir --only-binary=:all: --no-deps --require-hashes -r /opt/skillforge/docling-extra-requirements.txt \
    && python -m pip check
RUN groupadd --gid 65532 sandbox \
    && useradd --uid 65532 --gid 65532 --no-create-home --home-dir /workspace --shell /usr/sbin/nologin sandbox \
    && mkdir -p /opt/skillforge \
    && chmod 0755 /opt/skillforge
COPY python-entrypoint.sh /opt/skillforge/runner
RUN chmod 0555 /opt/skillforge/runner
USER 65532:65532
WORKDIR /workspace
ENTRYPOINT ["/opt/skillforge/runner"]

FROM ${NODE_BASE} AS node-runtime
ENV TZ=Asia/Shanghai \
    PLAYWRIGHT_BROWSERS_PATH=/opt/skillforge/browsers
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && printf 'Acquire::Retries "2";\n' > /etc/apt/apt.conf.d/80-skillforge-retries \
    && apt-get update && apt-get install -y --no-install-recommends \
        tzdata fonts-noto-cjk bash coreutils grep findutils ripgrep git \
    && npm install --prefix /opt/skillforge/playwright --ignore-scripts --no-audit --no-fund playwright-core@1.62.1 \
    && node /opt/skillforge/playwright/node_modules/playwright-core/cli.js install --with-deps chromium \
    && chmod -R a+rX /opt/skillforge/browsers \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --gid 65532 sandbox \
    && useradd --uid 65532 --gid 65532 --no-create-home --home-dir /workspace --shell /usr/sbin/nologin sandbox \
    && mkdir -p /opt/skillforge \
    && chmod 0755 /opt/skillforge
COPY node-entrypoint.sh /opt/skillforge/runner
RUN chmod 0555 /opt/skillforge/runner
USER 65532:65532
WORKDIR /workspace
ENTRYPOINT ["/opt/skillforge/runner"]
