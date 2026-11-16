# PostgreSQL 17 + pgvector 0.8.7 + zhparser（SCWS 中文分词）
# 自建镜像原因：不引入第三方来源不明的预编译镜像，保证本地私有化部署的可审计性。
#
# 三个 ARG 是给「官方源不可达」的环境留的显式出口，默认值全是官方源（只有编译阶段联网，
# 最终阶段不跑 apt，只抄文件 + ldconfig）：
#   PG_BASE        基础镜像。填镜像站的同一仓库路径即可（同一份字节，构建后用 digest 对账）。
#   DEBIAN_MIRROR  Debian apt 源前缀，如 http://mirrors.aliyun.com/debian
#   PGDG_MIRROR    PGDG apt 源，如 http://mirrors.aliyun.com/postgresql/repos/apt
#                  —— postgresql-server-dev-17 只在 PGDG 有，光换 Debian 源装不上它。
# 刻意做成构建参数而不是改机器上的 daemon 全局配置：用了哪个源要留在这份文件和构建命令里。
# 换 apt 源要用 http：基础镜像里没装 ca-certificates，用 https 源会在「装证书」这一步之前先失败。
# apt 的完整性靠仓库 Release 文件的 GPG 签名，不靠 TLS。
#
# 上游源码不在构建时 clone：先跑 deploy/pg/fetch-vendor.sh 把两个 tag 的 tar 包（带 sha256 校验）
# 取到 vendor/，本文件只 COPY 解压。本机 2026-11-16 实测容器内 github.com:443 连不上、宿主侧
# codeload 可达，且 scws 的 tag 名是 1.2.3（没有 v 前缀）。详见 docs/实测指导.md §1。
#
# scws 刻意不加 --prefix：默认落 /usr/local，正是 zhparser Makefile 里 SCWS_HOME 的默认值；
# 词库与规则文件（dict.utf8.xdb / rules.utf8.ini）必须由 zhparser 的 make install 放进
# tsearch_data/，缺了它们分词会退化成逐字切，中文全文检索等于没装。
ARG PG_BASE=pgvector/pgvector:pg17

FROM ${PG_BASE} AS build
ARG DEBIAN_MIRROR=
ARG PGDG_MIRROR=
COPY apt-mirrors.sh /usr/local/bin/apt-mirrors.sh

RUN set -eux; \
    sh /usr/local/bin/apt-mirrors.sh; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        build-essential autoconf automake libtool make \
        postgresql-server-dev-17; \
    rm -rf /var/lib/apt/lists/*

# SCWS 1.2.3：zhparser 的分词后端。GitHub 的 tag 归档里没有 configure，必须先 autoreconf。
COPY vendor/scws-1.2.3.tar.gz /usr/src/scws-1.2.3.tar.gz
RUN set -eux; \
    mkdir -p /usr/src/scws; \
    tar -xzf /usr/src/scws-1.2.3.tar.gz -C /usr/src/scws --strip-components=1; \
    rm /usr/src/scws-1.2.3.tar.gz; \
    cd /usr/src/scws; \
    autoreconf -fiv; \
    test -x ./configure; \
    ./configure; \
    make -j"$(nproc)"; \
    make install

# zhparser 2.3：PostgreSQL 中文全文检索扩展
COPY vendor/zhparser-2.3.tar.gz /usr/src/zhparser-2.3.tar.gz
RUN set -eux; \
    mkdir -p /usr/src/zhparser; \
    tar -xzf /usr/src/zhparser-2.3.tar.gz -C /usr/src/zhparser --strip-components=1; \
    rm /usr/src/zhparser-2.3.tar.gz; \
    cd /usr/src/zhparser; \
    make -j"$(nproc)"; \
    make install; \
    ls -l /usr/lib/postgresql/*/lib/zhparser.so /usr/share/postgresql/*/tsearch_data/dict.utf8.xdb

FROM ${PG_BASE}

COPY --from=build /usr/lib/postgresql/17/lib/zhparser.so /usr/lib/postgresql/17/lib/zhparser.so
COPY --from=build /usr/share/postgresql/17/extension/zhparser* /usr/share/postgresql/17/extension/
COPY --from=build /usr/share/postgresql/17/tsearch_data/dict.utf8.xdb /usr/share/postgresql/17/tsearch_data/dict.utf8.xdb
COPY --from=build /usr/share/postgresql/17/tsearch_data/rules.utf8.ini /usr/share/postgresql/17/tsearch_data/rules.utf8.ini
COPY --from=build /usr/local/lib/libscws* /usr/local/lib/

# 这一段刻意不跑 apt：基础镜像已经带着 postgresql-17 的全部依赖（PostgreSQL 的 snowball 词干提取是
# 源码内编的，不链 libstemmer；Debian 里那个包叫 libstemmer0d，此前写成的 libstemmer1 根本不存在）。
# 只需要让动态链接器认得新抄进来的 /usr/local/lib/libscws.so.2。
RUN set -eux; ldconfig

# 库级初始化：建扩展 + 中文 text search configuration（只对新建 database 生效）
COPY init.sql /docker-entrypoint-initdb.d/init.sql

EXPOSE 5432
