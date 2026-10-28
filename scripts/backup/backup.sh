#!/usr/bin/env bash
#
# 报修平台每日备份（MySQL 逐库 + MinIO 图片 + 配置/证书）
#
# 由服务器上的 cron 调用，默认每天 03:17：
#   17 3 * * * flock -n /var/lock/repair-backup.lock \
#     bash /opt/app/repair/scripts/backup/backup.sh >> /opt/backup/backup.log 2>&1
#
# 宿主侧配置（不进仓库、不随 CD 覆盖）：/opt/backup-secrets/backup.env
#   BACKUP_REMOTE=ubuntu@<异地主机>:/opt/backup/repair-incoming
#   BACKUP_SSH_KEY=/home/ubuntu/.ssh/backup_push
#   BACKUP_KEEP_DAYS=7
#   BACKUP_PING_URL=            # 可选，Healthchecks.io 之类的「任务没跑就告警」地址
# 加密口令：/opt/backup-secrets/passphrase（600，需另存一份到密码管理器）
#
# 退出码：0 全部成功；1 本地备份失败；2 本地成功但异地同步失败（本地文件仍完整）。
#
# 设计取舍：
#   · 口令从 MySQL 容器自己的环境变量取，不在宿主机解析 .env——.env 里的引号/注释
#     会让取出来的值和 compose 实际使用的不一致（项目里踩过）。
#   · 每个库一个文件：恢复一所学校不用碰别人。
#   · 配置与证书含 .env 和 TLS 私钥，离开本机前必须加密；没配口令时只留本地、不异地同步。
#   · MinIO 用 tar 而不是 mc mirror：不为备份再装一个客户端。MinIO 正在写的对象会让 tar
#     返回 1（file changed as we read it），这类退出码按警告处理，其余仍算失败。

set -euo pipefail
umask 077

APP_DIR=${APP_DIR:-/opt/app/repair}
BACKUP_DIR=${BACKUP_DIR:-/opt/backup}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-repair-mysql}
BACKUP_CONF=${BACKUP_CONF:-/opt/backup-secrets/backup.env}
PASSPHRASE_FILE=${BACKUP_PASSPHRASE_FILE:-/opt/backup-secrets/passphrase}

# 宿主侧配置只放非仓库路径；source 前先确认可读，避免 set -u 下的意外。
if [ -r "$BACKUP_CONF" ]; then
    # shellcheck disable=SC1090
    . "$BACKUP_CONF"
fi
KEEP_DAYS=${BACKUP_KEEP_DAYS:-7}
REMOTE=${BACKUP_REMOTE:-}
SSH_KEY=${BACKUP_SSH_KEY:-/home/ubuntu/.ssh/backup_push}
PING_URL=${BACKUP_PING_URL:-}

TS=$(date +%F-%H%M%S)

log()  { printf '[backup %s] %s\n' "$(date '+%F %T')" "$*"; }
warn() { printf '[backup %s] WARN %s\n' "$(date '+%F %T')" "$*" >&2; }
die()  { printf '[backup %s] ERROR %s\n' "$(date '+%F %T')" "$*" >&2; exit 1; }

case "$KEEP_DAYS" in
    ''|*[!0-9]*) die "BACKUP_KEEP_DAYS 必须是正整数，当前为 '$KEEP_DAYS'" ;;
    0)           die "BACKUP_KEEP_DAYS 不能为 0（会删掉当天备份）" ;;
esac

# 失败时清掉半截文件：.part 后缀既不会被保留策略清理，也不会被异地同步。
trap 'rm -f "$BACKUP_DIR"/*.part' EXIT

command -v docker >/dev/null || die "找不到 docker"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

# 不用 `docker inspect ... | grep -q`：pipefail 下 grep 提前退出会让 inspect 收到 SIGPIPE，
# 整条管道被判失败（容器其实是好的）。
MYSQL_RUNNING=$(docker inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER" 2>/dev/null || true)
[ "$MYSQL_RUNNING" = "true" ] || die "MySQL 容器 $MYSQL_CONTAINER 没在运行"

# ---------- 1. MySQL：平台库 + 每个租户库 ----------
DB_LIST=$(docker exec "$MYSQL_CONTAINER" sh -c \
    "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N -e \"SHOW DATABASES LIKE 'repair%'\"")

[ -n "$DB_LIST" ] || die "没有找到 repair% 数据库"

for db in $DB_LIST; do
    case "$db" in
        repair|repair_*) ;;
        *) warn "跳过非预期库名 '$db'"; continue ;;
    esac
    out="$BACKUP_DIR/mysql-${db}-${TS}.sql.gz"
    log "mysqldump $db"
    docker exec "$MYSQL_CONTAINER" sh -c \
        "exec mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" \
            --single-transaction --quick --routines --triggers --events \
            --default-character-set=utf8mb4 --databases '$db'" \
        | gzip > "$out.part"
    gzip -t "$out.part" || die "$db 的 dump 不是合法 gzip，已删除半截文件"
    [ -s "$out.part" ] || die "$db 的 dump 是空文件"
    mv "$out.part" "$out"
done

# ---------- 2. MinIO 图片 ----------
MINIO_DIR="$APP_DIR/data/minio"
[ -d "$MINIO_DIR" ] || die "MinIO 数据目录不存在：$MINIO_DIR"
log "打包 MinIO 图片"
set +e
tar -czf "$BACKUP_DIR/minio-${TS}.tar.gz.part" \
    --exclude='minio/.minio.sys/tmp' \
    -C "$APP_DIR/data" minio
TAR_RC=$?
set -e
# tar 退出码 1 = 读取期间有文件变化（MinIO 正在写），对图片备份可接受；>=2 是硬错误。
if [ "$TAR_RC" -ge 2 ]; then
    die "MinIO 打包失败（tar exit $TAR_RC）"
elif [ "$TAR_RC" -eq 1 ]; then
    warn "MinIO 打包时有文件在变化（tar exit 1），备份可用但不是严格时间点一致"
fi
[ -s "$BACKUP_DIR/minio-${TS}.tar.gz.part" ] || die "MinIO 备份是空文件"
mv "$BACKUP_DIR/minio-${TS}.tar.gz.part" "$BACKUP_DIR/minio-${TS}.tar.gz"

# ---------- 3. 配置与证书（敏感，离开本机前加密） ----------
CONFIG_OK=0
if [ -r "$PASSPHRASE_FILE" ]; then
    log "打包并加密 .env / nginx 证书"
    tar -czf - -C "$APP_DIR" .env nginx/certs \
        | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt \
            -pass "file:$PASSPHRASE_FILE" \
            -out "$BACKUP_DIR/config-${TS}.tar.gz.enc.part"
    mv "$BACKUP_DIR/config-${TS}.tar.gz.enc.part" "$BACKUP_DIR/config-${TS}.tar.gz.enc"
    CONFIG_OK=1
else
    warn "没有 $PASSPHRASE_FILE，配置备份只能是明文；本次不参与异地同步"
    tar -czf "$BACKUP_DIR/config-${TS}.tar.gz" -C "$APP_DIR" .env nginx/certs
    chmod 600 "$BACKUP_DIR/config-${TS}.tar.gz"
fi

# ---------- 4. 本地保留 ----------
log "清理本地 ${KEEP_DAYS} 天前的备份"
find "$BACKUP_DIR" -maxdepth 1 -type f \
    \( -name 'mysql-*.sql.gz' -o -name 'minio-*.tar.gz' \
       -o -name 'config-*.tar.gz' -o -name 'config-*.tar.gz.enc' \) \
    -mtime +"$KEEP_DAYS" -delete

# ---------- 5. 异地同步 ----------
if [ -n "$REMOTE" ]; then
    command -v rsync >/dev/null || die "配置了 BACKUP_REMOTE 但找不到 rsync"
    [ -r "$SSH_KEY" ] || die "找不到异地同步用的私钥：$SSH_KEY"
    log "异地同步到 $REMOTE"
    # 只同步加密后的 config；明文配置留在本机（含口令与证书私钥）。
    if ! rsync -az --timeout=600 \
        -e "ssh -i $SSH_KEY -o BatchMode=yes -o StrictHostKeyChecking=accept-new" \
        --include='mysql-*.sql.gz' --include='minio-*.tar.gz' --include='config-*.tar.gz.enc' \
        --exclude='*' \
        "$BACKUP_DIR/" "$REMOTE/"; then
        warn "异地同步失败：本地备份完整，问题在网络或对端"
        exit 2
    fi

    REMOTE_HOST=${REMOTE%%:*}
    REMOTE_DIR=${REMOTE#*:}
    ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=15 "$REMOTE_HOST" \
        "find '$REMOTE_DIR' -maxdepth 1 -type f \
            \\( -name 'mysql-*.sql.gz' -o -name 'minio-*.tar.gz' -o -name 'config-*.tar.gz.enc' \\) \
            -mtime +$KEEP_DAYS -delete" \
        || { warn "异地端旧备份清理失败（不影响本次备份）"; }
fi

# ---------- 6. 可选心跳 ----------
if [ -n "$PING_URL" ]; then
    curl -fsS -m 10 --retry 2 "$PING_URL" >/dev/null \
        || warn "心跳上报失败（备份本身已完成）"
fi

log "完成：$(du -sh "$BACKUP_DIR" | cut -f1) / $(find "$BACKUP_DIR" -maxdepth 1 -type f | wc -l) 个文件"
