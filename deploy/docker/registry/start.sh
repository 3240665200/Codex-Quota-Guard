#!/usr/bin/env bash
set -euo pipefail
umask 077

bundle_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
env_file="$bundle_dir/.env"
compose_file="$bundle_dir/compose.yaml"

fail() { printf '错误：%s\n' "$*" >&2; exit 1; }
usage() {
  printf '%s\n' \
    '用法：bash start.sh init http://服务器IP:8080 管理员邮箱' \
    '      bash start.sh up|status|logs [服务名]|backup|stop' \
    'up 从镜像仓库拉取 Compose 中指定的镜像并启动，不在服务器构建。'
}

require_file() {
  [[ -f "$1" && ! -L "$1" ]] || fail "缺少普通文件或遇到符号链接：${1##*/}"
}

compose() {
  docker compose --project-name codex-quota-guard --env-file "$env_file" -f "$compose_file" "$@"
}

start_services() {
  require_file "$env_file"
  require_file "$compose_file"
  [[ ! -L "$bundle_dir/secrets" ]] || fail 'secrets 目录不能是符号链接。'
  if [[ ! -e "$bundle_dir/secrets" ]]; then mkdir -m 700 -- "$bundle_dir/secrets"; fi
  [[ -d "$bundle_dir/secrets" ]] || fail 'secrets 必须是目录。'
  command -v docker >/dev/null || fail '未安装 Docker Engine。'
  docker info >/dev/null 2>&1 || fail 'Docker 未运行或当前用户无权访问。'
  docker compose version --short >/dev/null || fail '需要 Docker Compose v2 插件。'
  # Compose reads the environment; never source it or print interpolated secrets.
  compose config --quiet
  compose up -d --no-build --pull always --wait --wait-timeout 240
  compose ps
  printf '%s\n' '服务已拉取并启动；服务器未执行镜像构建。'
}

command_name="${1:-help}"
[[ $# -eq 0 ]] || shift
case "$command_name" in
  init|status|logs|backup|stop)
    require_file "$bundle_dir/deploy.sh"
    exec bash "$bundle_dir/deploy.sh" "$command_name" "$@"
    ;;
  up) [[ $# -eq 0 ]] || fail 'up 不接受额外参数。' ;;
  help|-h|--help) usage; exit ;;
  *) usage; exit 1 ;;
esac
start_services
