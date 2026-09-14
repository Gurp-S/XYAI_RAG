#!/usr/bin/env bash
# 黄金问答集批量导入脚本
# 用法：
#   1. 启动服务后获取管理员 JWT
#   2. BASE_URL=http://localhost:8080 TOKEN=xxx ./scripts/import_golden_set.sh
# 可选：如果 golden_cases.json 里还是 TODO_ 占位符，先用真实 fileId 替换：
#   sed -i 's/TODO_kafka教程.md/实际fileId/g' docs/golden/golden_cases.json
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TOKEN="${TOKEN:?需要环境变量 TOKEN（管理员 JWT）}"
# 注意：接口按 Jackson 驼峰反序列化，JSON 键必须是 question/groundTruth/expectedDocIds/source/enabled/remark，
# 不要用 snake_case（expected_doc_ids 会静默丢失，跑批时 expected 为空导致指标全 0）。
CASES_FILE="${CASES_FILE:-docs/golden/golden_cases_resolved.json}"

if ! command -v jq >/dev/null 2>&1; then
  echo "错误：需要 jq（https://jqlang.github.io/jq/download/）" >&2
  exit 1
fi

if grep -q "TODO_" "$CASES_FILE"; then
  echo "⚠️  $CASES_FILE 中仍有 TODO_ 占位 expected_doc_ids，"
  echo "    这些用例的 expected 会匹配不到任何文档（precision/recall 记 0）。"
  echo "    建议先查库拿到真实 fileId 再替换："
  echo "      mysql -e 'select id, file_name from xy_file_record limit 20'"
  echo "    按回车继续导入（占位版），Ctrl+C 中止去替换..."
  read -r
fi

echo ">>> 导入 $CASES_FILE 到 $BASE_URL/xyAdmin/evaluate/golden/import"
RESP=$(curl -s -X POST "$BASE_URL/xyAdmin/evaluate/golden/import" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @"$CASES_FILE")

echo ">>> 响应：$RESP"
echo "$RESP" | jq -e '.data.inserted >= 1' >/dev/null && echo "✅ 导入成功" || { echo "❌ 导入失败"; exit 1; }

echo ">>> 触发一轮跑批评估..."
RUN=$(curl -s -X POST "$BASE_URL/xyAdmin/evaluate/golden/run" \
  -H "Authorization: Bearer $TOKEN")
echo ">>> 跑批汇总："
echo "$RUN" | jq '.data'
