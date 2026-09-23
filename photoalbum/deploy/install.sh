#!/usr/bin/env bash
# ==========================================================================
#  私有相册 —— 树莓派一键部署 / 升级脚本
#
#  用法（在树莓派上，代码已上传到 ~/photoalbum 之后）：
#      bash deploy/install.sh
#
#  幂等：重复执行只做增量更新，不会动 data/ 目录里的照片。
#
#  关于 sudo：脚本需要重启 systemd 服务，因此要用到 sudo。
#  从本机 push.ps1 通过 ssh 远程调用时没有终端，sudo 无法弹密码提示，
#  所以这里支持两种方式：
#     · 环境变量 PHOTOALBUM_SUDO_PASS 里带上密码（push.ps1 会带上）
#     · 或者已经配置了免密 sudo
# ==========================================================================
set -euo pipefail

HOME_DIR="${PHOTOALBUM_HOME:-$HOME/photoalbum}"
VENV="$HOME_DIR/venv"
APP_DIR="$HOME_DIR/server/app"
SERVICE=photoalbum
PORT="${PHOTOALBUM_PORT:-8080}"

log() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }

# ---------------------------------------------------------- 包装 sudo
# 非交互场景下用 sudo -S，从标准输入喂密码；已经免密就直接 sudo。
sudocmd() {
  if sudo -n true 2>/dev/null; then
    sudo "$@"
  elif [ -n "${PHOTOALBUM_SUDO_PASS:-}" ]; then
    printf '%s\n' "$PHOTOALBUM_SUDO_PASS" | sudo -S -p '' "$@"
  else
    warn "需要 sudo 但没有可用密码（设置 PHOTOALBUM_SUDO_PASS，或配置免密 sudo）"
    return 1
  fi
}

log "项目目录: $HOME_DIR"
mkdir -p "$HOME_DIR"/{data/media,data/thumbs,data/cache,data/tmp,logs}

# ---------------------------------------------------------------- venv
if [ ! -x "$VENV/bin/python" ]; then
  log "创建 Python 虚拟环境"
  python3 -m venv "$VENV"
fi
log "安装 / 更新 Python 依赖（国内镜像加速）"
"$VENV/bin/pip" install --quiet --upgrade pip
"$VENV/bin/pip" install --quiet -r "$HOME_DIR/server/requirements.txt" \
  -i "${PIP_INDEX:-https://pypi.tuna.tsinghua.edu.cn/simple}"

# ------------------------------------------------- 可选：ffmpeg / HEIC
if ! command -v ffmpeg >/dev/null 2>&1; then
  warn "未检测到 ffmpeg（视频缩略图/转码需要）。安装： sudo apt-get install -y ffmpeg"
fi
if ! command -v heif-convert >/dev/null 2>&1; then
  warn "未检测到 heif-convert（处理 iPhone HEIC 的兜底方案，pillow-heif 已在时可不装）"
fi

# ---------------------------------------------------------- 语法自检
log "Python 语法自检"
( cd "$APP_DIR" && "$VENV/bin/python" -m compileall -q . >/dev/null )

# -------------------------------------------------------------- systemd
log "安装 systemd 服务 $SERVICE"
sudocmd cp "$HOME_DIR/deploy/$SERVICE.service" "/etc/systemd/system/$SERVICE.service"
sudocmd systemctl daemon-reload
sudocmd systemctl enable "$SERVICE" >/dev/null
sudocmd systemctl restart "$SERVICE"

sleep 3
if ! systemctl is-active --quiet "$SERVICE"; then
  warn "服务未启动，最近日志："
  sudocmd journalctl -u "$SERVICE" -n 40 --no-pager || true
  exit 1
fi

# ---------------------------------------------------------- 健康检查
log "健康检查 http://127.0.0.1:$PORT/api/health"
for i in $(seq 1 20); do
  if curl -fsS --max-time 5 "http://127.0.0.1:$PORT/api/health" >/tmp/photoalbum_health.json 2>/dev/null; then
    cat /tmp/photoalbum_health.json; echo
    break
  fi
  sleep 1
  [ "$i" = 20 ] && { warn "健康检查超时"; exit 1; }
done

IP_TS="$(tailscale ip -4 2>/dev/null | head -1 || true)"
IP_LAN="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
cat <<EOF

\033[1;32m部署完成\033[0m
  Tailscale 地址 : http://${IP_TS:-未启用}:$PORT
  局域网地址     : http://${IP_LAN:-未知}:$PORT
  媒体目录       : $HOME_DIR/data/media   （换外接硬盘：设置页 -> 媒体根目录 / 迁移）
  服务日志       : sudo journalctl -u $SERVICE -f   或   tail -f $HOME_DIR/logs/service.log

EOF
