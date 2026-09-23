#!/usr/bin/env bash
# ==========================================================================
#  清空相册数据（把服务端恢复到刚部署的干净状态）
#
#  会删除：
#    data/media/    所有原始照片与录像
#    data/thumbs/   缩略图
#    data/cache/    视频转码缓存
#    data/album.db  索引数据库（含账号、相册、收藏、分享）
#
#  不会删除：代码、虚拟环境、日志。
#  用途：本地测试 / 重新开始。生产环境请勿随手执行。
# ==========================================================================
set -euo pipefail
HOME_DIR="${PHOTOALBUM_HOME:-$HOME/photoalbum}"

if [ "${1:-}" != "--yes" ]; then
  cat <<EOF
这将删除 $HOME_DIR/data 下的全部媒体与索引数据。
如果确认，请执行： bash $0 --yes
EOF
  exit 1
fi

echo "==> 停止服务"
sudo systemctl stop photoalbum || true

echo "==> 清理数据目录"
rm -rf "$HOME_DIR"/data/media/* "$HOME_DIR"/data/thumbs/* "$HOME_DIR"/data/cache/* "$HOME_DIR"/data/tmp/* 2>/dev/null || true
rm -f "$HOME_DIR"/data/album.db "$HOME_DIR"/data/album.db-wal "$HOME_DIR"/data/album.db-shm

echo "==> 重新启动服务"
sudo systemctl start photoalbum
sleep 4
systemctl is-active photoalbum
curl -s --max-time 10 http://127.0.0.1:8080/api/health || true
echo
echo "==> 完成（默认账号已重建；初始口令是随机生成的，见服务日志：）"
echo "    sudo journalctl -u photoalbum --since '2 min ago' | grep 默认账号"
