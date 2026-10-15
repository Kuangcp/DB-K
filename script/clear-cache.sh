#!/bin/bash
# db-k 元数据缓存清理脚本
# 用于快速清空 meta_cache 和 column_cache，解决 6 万表等大数据源导致的卡顿问题

DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share/db-k}"
DB_PATH="$DATA_DIR/app.db"

if [ ! -f "$DB_PATH" ]; then
    echo "❌ 找不到数据库文件：$DB_PATH"
    exit 1
fi

echo "🗑️  正在清理 db-k 缓存（$DB_PATH）..."

sqlite3 "$DB_PATH" "DELETE FROM meta_cache; DELETE FROM column_cache;"

echo "✅ meta_cache: 已清空"
echo "✅ column_cache: 已清空"

# 确认
META=$(sqlite3 "$DB_PATH" "SELECT count(*) FROM meta_cache")
COL=$(sqlite3 "$DB_PATH" "SELECT count(*) FROM column_cache")
echo ""
echo "📊 剩余行数 — meta_cache: $META, column_cache: $COL"
echo ""
echo "💡 提示：重启应用后刷新即可生效。"
echo "   如果仍然卡顿，可以加 --full 参数清除所有数据源连接缓存："
echo "     ./clear-cache.sh --full"
