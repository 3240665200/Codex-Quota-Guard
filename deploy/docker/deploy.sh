#!/usr/bin/env bash
set -euo pipefail
umask 077

deploy_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
env_file="$deploy_dir/.env"
compose_file="$deploy_dir/compose.yaml"

fail() { printf '错误：%s\n' "$*" >&2; exit 1; }
usage() {
  printf '%s\n' \
    '用法：bash deploy/docker/deploy.sh init http://服务器IP:8080 管理员邮箱' \
    '      bash deploy/docker/deploy.sh up|status|logs [服务名]|stop|backup' \
    '服务名：backend、web、postgres；stop 保留数据库，不提供删除数据命令。'
}

prepare_secrets_directory() {
  local secrets_dir="$deploy_dir/secrets"
  [[ ! -L "$secrets_dir" ]] || fail 'secrets 目录不能是符号链接。'
  if [[ ! -e "$secrets_dir" ]]; then mkdir -m 700 -- "$secrets_dir"; fi
  [[ -d "$secrets_dir" ]] || fail 'secrets 必须是目录。'
  # Existing files, permissions and owners belong to the operator. Do not read,
  # generate or chmod/chown the optional private authorization preset.
}

initialize() {
  prepare_secrets_directory
  if [[ -e "$env_file" || -L "$env_file" ]]; then
    printf '%s\n' '配置已存在，保留原密码与密钥，不会重新生成。'
    return
  fi
  [[ $# -eq 2 ]] || { usage; exit 1; }
  local origin="$1" email="$2" host port password repeated tmp_file
  local postgres_secret database_secret jwt_secret mail_secret official_secret proxy_secret
  # This entry point intentionally handles IP/domain + HTTP, not paths or userinfo.
  [[ "$origin" =~ ^http://([A-Za-z0-9][A-Za-z0-9.-]*)(:([0-9]{1,5}))?$ ]] \
    || fail '地址应为 http://服务器IP:8080，不要带路径、账号或末尾斜杠。'
  host="${BASH_REMATCH[1]}"
  port="${BASH_REMATCH[3]:-80}"
  [[ "$host" != 0.0.0.0 ]] || fail '0.0.0.0 不是可访问的服务器地址。'
  (( 10#$port >= 1 && 10#$port <= 65535 )) || fail '端口范围应为 1～65535。'
  port=$((10#$port))
  [[ "$email" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] \
    || fail '请填写有效的管理员邮箱地址。'
  command -v openssl >/dev/null || fail '需要 openssl 生成密钥，请先安装 openssl。'
  read -r -s -p '设置管理员密码（12～72 个 ASCII 字符，输入不回显）：' password || fail '未输入密码。'
  printf '\n'
  read -r -s -p '再次输入管理员密码：' repeated || fail '未确认密码。'
  printf '\n'
  [[ "$password" == "$repeated" ]] || fail '两次密码不一致。'
  [[ ${#password} -ge 12 && ${#password} -le 72 && "$password" != *"'"* && "$password" != *'\'* && "$password" != *$'\r'* ]] \
    || fail '密码需 12～72 个字符；请勿使用单引号、反斜杠或换行。'
  [[ "$password" != *[!\ -~]* ]] || fail '初始化密码请使用 ASCII 字符。'

  postgres_secret="$(openssl rand -hex 32)" || fail '生成数据库管理员密钥失败。'
  database_secret="$(openssl rand -hex 32)" || fail '生成数据库应用密钥失败。'
  jwt_secret="$(openssl rand -hex 32)" || fail '生成 JWT 密钥失败。'
  mail_secret="$(openssl rand -base64 32)" || fail '生成邮箱加密密钥失败。'
  official_secret="$(openssl rand -base64 32)" || fail '生成官方账户加密密钥失败。'
  proxy_secret="$(openssl rand -base64 32)" || fail '生成代理节点加密密钥失败。'
  [[ "$postgres_secret" =~ ^[a-f0-9]{64}$ && "$database_secret" =~ ^[a-f0-9]{64}$ && "$jwt_secret" =~ ^[a-f0-9]{64}$ ]] \
    || fail '随机生成器返回了无效的密钥。'
  [[ "$mail_secret" =~ ^[A-Za-z0-9+/]{43}=$ ]] || fail '随机生成器返回了无效的邮箱密钥。'
  [[ "$official_secret" =~ ^[A-Za-z0-9+/]{43}=$ && "$proxy_secret" =~ ^[A-Za-z0-9+/]{43}=$ ]] \
    || fail '随机生成器返回了无效的账户或代理密钥。'
  tmp_file="$(mktemp "$deploy_dir/.env.tmp.XXXXXX")"
  # Single-quoted dotenv password preserves literal $/# without shell evaluation.
  if ! {
    printf 'HTTP_BIND=0.0.0.0\nHTTP_PORT=%s\nPUBLIC_ORIGIN=%s\n' "$port" "$origin"
    printf 'POSTGRES_PASSWORD=%s\n' "$postgres_secret"
    printf 'DATABASE_PASSWORD=%s\n' "$database_secret"
    printf 'JWT_SECRET=%s\nJWT_ISSUER=codex-quota-guard\n' "$jwt_secret"
    printf 'MAIL_ASSISTANCE_ENCRYPTION_KEY=%s\n' "$mail_secret"
    printf 'OFFICIAL_SESSION_ENCRYPTION_KEY=%s\n' "$official_secret"
    printf 'MANAGED_PROXY_ENCRYPTION_KEY=%s\n' "$proxy_secret"
    printf "ADMIN_BOOTSTRAP_EMAIL=%s\nADMIN_BOOTSTRAP_PASSWORD='%s'\n" "$email" "$password"
  } > "$tmp_file"; then
    rm -f -- "$tmp_file"
    fail '生成配置失败，未写入 .env。'
  fi
  # Atomic no-clobber: concurrent init cannot overwrite another process's keys.
  if ! ln -- "$tmp_file" "$env_file"; then
    rm -f -- "$tmp_file"
    fail '配置已经存在或目录不可写，未覆盖任何密钥。'
  fi
  rm -f -- "$tmp_file"
  printf '配置已保存：%s\n访问地址：%s\n' "$env_file" "$origin"
  printf '%s\n' 'HTTP 不加密，请限制为可信网络。请单独备份 .env，升级时不要重新生成。'
}

compose() {
  docker compose --project-name codex-quota-guard --env-file "$env_file" -f "$compose_file" "$@"
}

check_runtime() {
  [[ -f "$env_file" && ! -L "$env_file" ]] || fail '请先运行 init 创建 .env（不支持符号链接）。'
  command -v docker >/dev/null || fail '未安装 Docker Engine / Compose。'
  docker compose version --short >/dev/null || fail '需要 Docker Compose v2 插件。'
  docker info >/dev/null 2>&1 || fail 'Docker 未运行或当前用户无权访问。'
  # Never source/eval .env and never print interpolated secrets via config output.
  compose config --quiet
}

backup() {
  local backup_root="$deploy_dir/backups" staging destination
  [[ ! -L "$backup_root" ]] || fail '备份目录不能是符号链接。'
  mkdir -p -- "$backup_root"
  chmod 700 "$backup_root"
  staging="$(mktemp -d "$backup_root/.incomplete.XXXXXX")"
  if ! compose exec -T postgres pg_dump -U postgres -d quota_guard --format=custom > "$staging/database.dump"; then
    printf '备份失败；未完成的数据保留在：%s\n' "$staging" >&2
    return 1
  fi
  [[ -s "$staging/database.dump" ]] || fail '数据库备份为空，未标记成功。'
  cp -- "$env_file" "$staging/deployment.env"
  chmod 600 "$staging/database.dump" "$staging/deployment.env"
  destination="$backup_root/$(date -u +%Y%m%dT%H%M%SZ)-${staging##*.}"
  mv -- "$staging" "$destination"
  printf '备份完成（包含敏感配置，请离线加密保存）：%s\n' "$destination"
}

command_name="${1:-help}"
[[ $# -eq 0 ]] || shift
case "$command_name" in
  init) initialize "$@"; exit ;;
  help|-h|--help) usage; exit ;;
  up|status|stop|backup) [[ $# -eq 0 ]] || fail '该命令不接受额外参数。' ;;
  logs)
    [[ $# -le 1 ]] || fail '最多指定一个服务。'
    case "${1:-}" in ''|backend|web|postgres) ;; *) fail '未知服务名。' ;; esac ;;
  *) usage; exit 1 ;;
esac
check_runtime
case "$command_name" in
  up)
    prepare_secrets_directory
    compose up -d --build --wait --wait-timeout 240
    compose ps
    printf '%s\n' '服务已启动，请通过 .env 中的 PUBLIC_ORIGIN 访问后台，客户端也填写同一地址。'
    ;;
  status) compose ps ;;
  logs) compose logs --tail 150 --follow "$@" ;;
  stop) compose stop ;;
  backup) backup ;;
esac
