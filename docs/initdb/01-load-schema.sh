#!/bin/bash
#
# 用「显式指定字符集」的方式导入建表脚本。
#
# 为什么需要这个包装，而不是让 MySQL 的 entrypoint 直接执行 schema.sql：
#   mysql:8.0 容器里，mysql 客户端的默认 character_set_client 是 **latin1**，
#   而 docs/schema.sql 是 UTF-8。让 entrypoint 直接执行 .sql 文件时，文件里的中文字节
#   会被逐字节当成 latin1 解释、再转成 UTF-8 存进库——每个中文都变成两个乱码字符：
#       正确的 "1号楼" = 31 E58FB7 E6A5BC
#       存进去的      = 31 C3A5C28FC2B7 C3A6C2A5C2BC
#   症状：页面上所有中文都是 åŽŠå‹¤... 这种，而后端/nginx 日志干净得像什么都没发生，
#   灌库也不报错。最坏的地方在于它"看起来成功了"。
#
# 实测确认过：`SHOW VARIABLES LIKE 'character_set_client'` 在这个容器里返回 latin1。
# 所以这里显式带上 --default-character-set=utf8mb4。
#
# 这个文件被 docker-entrypoint-initdb.d 以 **source** 方式执行（所以能读到容器的环境变量），
# set -e 会让导入失败时整个 entrypoint 以非零退出——容器起不来、日志里有明确报错，
# 比"静默地把中文写坏"要好得多。

set -e

echo "[initdb] 按 utf8mb4 导入 schema.sql → 数据库 ${MYSQL_DATABASE}"

mysql --default-character-set=utf8mb4 \
      --protocol=socket \
      -uroot -p"${MYSQL_ROOT_PASSWORD}" \
      "${MYSQL_DATABASE}" < /opt/repair-schema.sql

echo "[initdb] 导入完成，校验一处中文（应为 UTF-8 的 E6B5B7…）"
mysql --default-character-set=utf8mb4 \
      --protocol=socket \
      -uroot -p"${MYSQL_ROOT_PASSWORD}" \
      "${MYSQL_DATABASE}" \
      -e "SELECT name, HEX(name) AS hex_bytes FROM building ORDER BY id LIMIT 1;"
