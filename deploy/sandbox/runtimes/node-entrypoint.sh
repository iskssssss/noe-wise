#!/bin/sh
set -eu

mode=$1
shift

case "$mode" in
  shell-execute)
    # 命令脚本由可信服务写入只读输入，工作目录限定在已授权项目挂载内。
    [ "$#" -eq 2 ] || exit 64
    script_path=$1
    project_cwd=$2
    cd "$project_cwd"
    exec /bin/bash --noprofile --norc "$script_path"
    ;;
  workflow-execute)
    [ "$#" -eq 1 ] || exit 64
    cd /tmp
    exec node "$1"
    ;;
  install)
    mkdir -p /workspace/work/home /workspace/work/node_modules
    printf '{"private":true,"type":"module"}\n' > /workspace/work/package.json
    if [ "$#" -gt 0 ]; then
      npm install \
        --prefix /workspace/work \
        --package-lock=true \
        --ignore-scripts \
        --no-audit \
        --no-fund \
        "$@"
      cp /workspace/work/package-lock.json /workspace/evidence/dependencies.json
    else
      printf '{"lockfileVersion":3,"packages":{}}\n' > /workspace/evidence/dependencies.json
    fi
    ;;
  execute)
    [ "$#" -eq 1 ] || [ "$#" -eq 2 ] || exit 64
    entry_path=$1
    # 无工作区绑定时沿用 /workspace；可信 Runner 可指定授权项目目录。
    cd "${2:-/workspace}"
    exec node "$entry_path"
    ;;
  *)
    exit 64
    ;;
esac
