#!/bin/sh
# 取 zhparser 构建所需的的上游源码包，放到本目录 vendor/。
# 为什么不在 Dockerfile 里直接 git clone：本机实测容器内访问 github.com:443 连不上，
# 宿主侧走 codeload 却是通的——两处网络栈不同，只有宿主侧这条路能落地。
# 两个包的 sha256 是写死的： vendor 里躺着一个对不上号的 tar 包，比构建失败更难查。
set -eu

cd "$(dirname "$0")"
mkdir -p vendor

fetch() {
    name=$1
    url=$2
    want=$3
    if [ -f "vendor/$name" ] && [ "$(sha256sum "vendor/$name" | cut -d' ' -f1)" = "$want" ]; then
        echo "已就位且校验通过：vendor/$name"
        return 0
    fi
    echo "下载 $url"
    curl --fail --silent --show-error --location -o "vendor/$name.part" "$url"
    got=$(sha256sum "vendor/$name.part" | cut -d' ' -f1)
    if [ "$got" != "$want" ]; then
        rm -f "vendor/$name.part"
        echo "校验失败：vendor/$name" >&2
        echo "  期望 $want" >&2
        echo "  实得 $got" >&2
        echo "上游若换了字节，先确认来源真的是上面这个 tag，再回来改本文件的常量和 Dockerfile 的版本注释。" >&2
        exit 1
    fi
    mv "vendor/$name.part" "vendor/$name"
    echo "已就位：vendor/$name"
}

fetch scws-1.2.3.tar.gz \
    https://codeload.github.com/hightman/scws/tar.gz/refs/tags/1.2.3 \
    98e5932221029f507eadc762c4036eaf95f31f62f45f972401ec6952258feb10

fetch zhparser-2.3.tar.gz \
    https://codeload.github.com/amutu/zhparser/tar.gz/refs/tags/v2.3 \
    96fe19f1b72c922eab19ecfc305384381562ff840da4d3b77452e21fefe8f0d2
