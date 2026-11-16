#!/usr/bin/env bash
# 被 start.sh / stop.sh 共用：判一个 pid 在不在、能不能认出来、停得掉停不掉。
#
# 为什么不能直接用 kill：Git Bash 的 kill 只认本会话起的子进程。换个终端起的 java，
# `kill -0` 会报「没有这个进程」，于是 stop.sh 说「本来就不在」而旧后端照旧占着 8080，
# 紧接着 start.sh 走「已在运行」分支既不打包也不起——自检门绿在一份旧 jar 上，
# 跑完一整轮实测才发现测的不是刚改的代码。Windows 上判活还得再问一次 Win32（tasklist）。
#
# 但 tasklist 也不能盲用：`$!` 有时是 msys 自己分配的号，撞上一个毫不相干的 Win32 进程是常事
# （本机实测：同一个后台进程，kill -0 用的号是 5723，tasklist 里它是 13108）。
# 所以「杀」只在两种前提下发生：本会话的孩子（kill 得动），或者 pid 与镜像名对得上。
# 对不上就只报「请手工结束」——误杀一个别人起的进程，比留一个停不掉的进程难查得多。
#
# tasklist 的输出先落到变量再用 case 匹配：`grep -q` 命中即退会让 tasklist 收到 SIGPIPE，
# 在 pipefail 下整条管道返回非零——「进程活着」会被判成「不在」，正好把这个坑反过来踩一遍。

_pid_is_windows() { command -v taskkill >/dev/null 2>&1 && command -v tasklist >/dev/null 2>&1; }

# tasklist 里查这个 pid（可再要求镜像名），命中返回 0
_pid_in_tasklist() {
  _pid_is_windows || return 1
  local out
  # 两个 //FI 是「与」：既要号对上，也要镜像名对上——这才是动手杀人的许可
  out=$(tasklist //FI "PID eq $1" ${2:+//FI "IMAGENAME eq $2"} //NH //FO CSV 2>/dev/null || true)
  case "$out" in *",\"$1\","*) return 0 ;; *) return 1 ;; esac
}

# 活着吗——仅用于报告与判等，不构成杀它的许可
pid_alive() {
  local pid="${1:-}"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null && return 0
  _pid_in_tasklist "$pid"
}

# 能不能确认「这个 pid 就是我们记下的那个进程」：本会话的孩子，或 pid+镜像名同时对上
pid_owned() {
  local pid="${1:-}" image="${2:-}"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" 2>/dev/null && return 0
  _pid_in_tasklist "$pid" "$image"
}

# 停它：返回 0 已停 / 1 停不掉 / 2 认不出来所以没动它
# 用法：pid_stop "$pid" java.exe
pid_stop() {
  local pid="${1:-}" image="${2:-}"
  pid_owned "$pid" "$image" || { pid_alive "$pid" && return 2 || return 0; }
  # 先礼后兵：SIGTERM / taskkill 不带 //F 各试一次，等 10 秒再强杀
  kill "$pid" 2>/dev/null || true
  _pid_is_windows && taskkill //PID "$pid" //T >/dev/null 2>&1 || true
  local _i
  for _i in $(seq 1 20); do pid_alive "$pid" || return 0; sleep 0.5; done
  kill -9 "$pid" 2>/dev/null || true
  _pid_is_windows && taskkill //PID "$pid" //T //F >/dev/null 2>&1 || true
  for _i in $(seq 1 6); do pid_alive "$pid" || return 0; sleep 0.5; done
  return 1
}
