#!/usr/bin/env bash
set -euo pipefail
umask 077

bundle_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
env_file="$bundle_dir/.env"
compose_file="$bundle_dir/compose.yaml"
image_tags=()

fail() { printf '错误：%s\n' "$*" >&2; exit 1; }
usage() {
  printf '%s\n' \
    '用法：bash start.sh init http://服务器IP:8080 管理员邮箱' \
    '      bash start.sh load|up|status|logs [服务名]|backup|stop' \
    'load 校验并导入离线镜像；up 仅使用本机镜像，不构建或拉取。'
}

require_file() {
  [[ -f "$1" && ! -L "$1" ]] || fail "缺少普通文件或遇到符号链接：${1##*/}"
}

require_docker() {
  command -v docker >/dev/null || fail '未安装 Docker Engine。'
  docker info >/dev/null 2>&1 || fail 'Docker 未运行或当前用户无权访问。'
}

compose() {
  docker compose --project-name codex-quota-guard --env-file "$env_file" -f "$compose_file" "$@"
}

read_image_manifest() {
  require_file "$bundle_dir/images.txt"
  local reference previous
  while IFS= read -r reference || [[ -n "$reference" ]]; do
    reference="${reference%$'\r'}"
    [[ -n "$reference" ]] || continue
    [[ ${#reference} -le 255 && "$reference" =~ ^[a-z0-9][a-z0-9._/-]*:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] \
      || fail 'images.txt 必须仅包含带显式标签的镜像名。'
    for previous in "${image_tags[@]}"; do
      [[ "$reference" != "$previous" ]] || fail 'images.txt 包含重复镜像。'
    done
    image_tags+=("$reference")
    [[ ${#image_tags[@]} -le 3 ]] || fail 'images.txt 必须恰好包含三个镜像。'
  done < "$bundle_dir/images.txt"
  [[ ${#image_tags[@]} -eq 3 ]] || fail 'images.txt 必须恰好包含三个镜像。'
}

verify_checksums() {
  require_file "$bundle_dir/SHA256SUMS"
  require_file "$bundle_dir/images.tar"
  command -v sha256sum >/dev/null || fail '需要 sha256sum 校验离线文件。'
  local line filename previous archive_listed=false
  local -a filenames=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -n "$line" ]] || continue
    # The package is flat. Never let a checksum record read outside its directory.
    [[ "$line" =~ ^[[:xdigit:]]{64}\ [\ \*]([A-Za-z0-9._-]+)$ ]] \
      || fail 'SHA256SUMS 包含无效记录或非平级文件路径。'
    filename="${BASH_REMATCH[1]}"
    [[ "$filename" != . && "$filename" != .. ]] || fail '校验文件路径无效。'
    require_file "$bundle_dir/$filename"
    for previous in "${filenames[@]}"; do
      [[ "$filename" != "$previous" ]] || fail 'SHA256SUMS 包含重复文件。'
    done
    filenames+=("$filename")
    if [[ "$filename" = images.tar ]]; then archive_listed=true; fi
  done < "$bundle_dir/SHA256SUMS"
  [[ "$archive_listed" = true ]] || fail 'SHA256SUMS 未包含 images.tar。'
  (cd -- "$bundle_dir" && sha256sum --check --strict --status SHA256SUMS) \
    || fail '离线包校验失败，未导入任何镜像。'
}

load_images() {
  verify_checksums
  require_docker
  docker load --input "$bundle_dir/images.tar"
}

verify_compose_images() {
  local configured reference allowed previous found
  local -a observed=()
  configured="$(compose config --images)" || fail '无法读取 Compose 镜像清单。'
  while IFS= read -r reference || [[ -n "$reference" ]]; do
    reference="${reference%$'\r'}"
    [[ -n "$reference" ]] || continue
    found=false
    for allowed in "${image_tags[@]}"; do
      if [[ "$reference" = "$allowed" ]]; then found=true; break; fi
    done
    [[ "$found" = true ]] || fail 'Compose 镜像与 images.txt 不一致。'
    for previous in "${observed[@]}"; do
      [[ "$reference" != "$previous" ]] || fail 'Compose 镜像清单包含重复项。'
    done
    observed+=("$reference")
  done <<< "$configured"
  [[ ${#observed[@]} -eq 3 ]] || fail 'Compose 必须使用清单中的三个镜像。'
}

start_services() {
  require_file "$env_file"
  require_file "$compose_file"
  read_image_manifest
  require_docker
  docker compose version --short >/dev/null || fail '需要 Docker Compose v2 插件。'
  # Parse the environment through Compose only; never source it or print its values.
  compose config --quiet
  verify_compose_images
  local reference platform
  for reference in "${image_tags[@]}"; do
    platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$reference" 2>/dev/null)" \
      || fail "镜像未导入：$reference；请先运行 bash start.sh load。"
    [[ "$platform" = linux/amd64 ]] || fail "镜像架构不是 linux/amd64：$reference"
  done
  compose up -d --no-build --pull never --wait --wait-timeout 240
  compose ps
  printf '%s\n' '离线服务已启动；镜像未构建或联网拉取。'
}

command_name="${1:-help}"
[[ $# -eq 0 ]] || shift
case "$command_name" in
  init|status|logs|backup|stop)
    require_file "$bundle_dir/deploy.sh"
    exec bash "$bundle_dir/deploy.sh" "$command_name" "$@"
    ;;
  load|up) [[ $# -eq 0 ]] || fail '该命令不接受额外参数。' ;;
  help|-h|--help) usage; exit ;;
  *) usage; exit 1 ;;
esac
case "$command_name" in
  load) load_images ;;
  up) start_services ;;
esac
