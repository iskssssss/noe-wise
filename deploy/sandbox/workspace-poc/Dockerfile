# 复用本地已构建的运行时；只负责文件和命令测试，云存储挂载由外部提供。
FROM skillforge-sandbox-python:3.12
USER root
RUN mkdir -p /workspace && chown 10001:10001 /workspace
WORKDIR /workspace
USER 10001:10001
ENTRYPOINT ["/bin/bash"]
CMD ["-lc", "grep --version | head -n 1; find --version | head -n 1; ls -la /workspace"]
