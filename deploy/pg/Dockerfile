# PostgreSQL 17 + pgvector 0.8.7 + zhparser（SCWS 中文分词）
# 自建镜像原因：不引入第三方来源不明的预编译镜像，保证本地私有化部署的可审计性。
FROM pgvector/pgvector:pg17 AS build

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        build-essential git autoconf automake libtool make ca-certificates \
        postgresql-server-dev-17; \
    rm -rf /var/lib/apt/lists/*

# SCWS 1.2.3：zhparser 的分词后端
RUN set -eux; \
    git clone --depth 1 --branch v1.2.3 https://github.com/hightman/scws /usr/src/scws; \
    cd /usr/src/scws; \
    ./autogen.sh 2>/dev/null || autoreconf -fiv 2>/dev/null || true; \
    test -x ./configure || { echo "scws: configure script missing" >&2; exit 1; }; \
    ./configure --prefix=/usr; \
    make -j"$(nproc)"; \
    make install

# zhparser：PostgreSQL 中文全文检索扩展
RUN set -eux; \
    git clone --depth 1 --branch v2.3 https://github.com/amutu/zhparser /usr/src/zhparser; \
    cd /usr/src/zhparser; \
    make -j"$(nproc)"; \
    make install

FROM pgvector/pgvector:pg17

COPY --from=build /usr/lib/postgresql/17/lib/zhparser.so /usr/lib/postgresql/17/lib/zhparser.so
COPY --from=build /usr/share/postgresql/17/extension/zhparser* /usr/share/postgresql/17/extension/
COPY --from=build /usr/local/lib/libscws* /usr/local/lib/
COPY --from=build /usr/local/etc/scws.char_table /usr/local/etc/scws.char_table
COPY --from=build /usr/local/etc/scws.unicode_table /usr/local/etc/scws.unicode_table

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends libstemmer1; \
    rm -rf /var/lib/apt/lists/*; \
    ldconfig

# 库级初始化：建扩展 + 中文 text search configuration（只对新建 database 生效）
COPY init.sql /docker-entrypoint-initdb.d/init.sql

EXPOSE 5432
