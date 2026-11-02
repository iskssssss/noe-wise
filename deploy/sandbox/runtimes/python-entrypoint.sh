#!/bin/sh
set -eu

mode=$1
shift

case "$mode" in
  workflow-execute)
    [ "$#" -eq 1 ] || exit 64
    cd /tmp
    exec python -I -B "$1"
    ;;
  install)
    mkdir -p /workspace/work/home /workspace/work/python
    if [ "$#" -gt 0 ]; then
      python -m pip install \
        --disable-pip-version-check \
        --no-input \
        --no-cache-dir \
        --only-binary=:all: \
        --report /workspace/evidence/dependencies.json \
        --target /workspace/work/python \
        "$@"
    else
      printf '{"install":[],"version":"1"}\n' > /workspace/evidence/dependencies.json
    fi
    ;;
  execute)
    [ "$#" -eq 1 ] || [ "$#" -eq 2 ] || exit 64
    entry_path=$1
    export PYTHONPATH=/workspace/work/python
    # 工作区绑定只改变当前目录，依赖仍来自本次调用的临时安装目录。
    cd "${2:-/workspace}"
    exec python -B "$entry_path"
    ;;
  *)
    exit 64
    ;;
esac
