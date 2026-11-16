#!/bin/sh
# 只在显式给了镜像地址时才改 apt 源；两个变量都不给就原样用官方源（默认路径，可审计）。
# 需要它是因为 Debian 与 PGDG 的官方站在某些网络环境里不可达，而 zhparser 镜像必须装
# postgresql-server-dev-17（这个包只在 PGDG 有），光换 Debian 源仍然装不上。
set -eu

if [ -n "${DEBIAN_MIRROR:-}" ]; then
    for f in /etc/apt/sources.list /etc/apt/sources.list.d/*.sources; do
        [ -f "$f" ] || continue
        # 先长后短：debian-security 不能被 debian 那条规则截断成 <mirror>-security 之外的形状
        sed -i -e "s|http://deb.debian.org/debian-security|${DEBIAN_MIRROR}-security|g" \
               -e "s|http://deb.debian.org/debian|${DEBIAN_MIRROR}|g" \
               -e "s|http://security.debian.org/debian-security|${DEBIAN_MIRROR}-security|g" \
               -e "s|https://deb.debian.org/debian-security|${DEBIAN_MIRROR}-security|g" \
               -e "s|https://deb.debian.org/debian|${DEBIAN_MIRROR}|g" "$f"
    done
fi

if [ -n "${PGDG_MIRROR:-}" ]; then
    for f in /etc/apt/sources.list /etc/apt/sources.list.d/*.list; do
        [ -f "$f" ] || continue
        sed -i -e "s|http://apt.postgresql.org/pub/repos/apt|${PGDG_MIRROR}|g" \
               -e "s|https://apt.postgresql.org/pub/repos/apt|${PGDG_MIRROR}|g" "$f"
    done
fi
