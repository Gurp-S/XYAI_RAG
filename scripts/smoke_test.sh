#!/usr/bin/env bash
# RAG 主链路冒烟验证脚本：改代码后跑一遍，确认链路没被改坏
# 用法：# 离线测试模式：启动时加 --rag.mock-llm.enabled=true 即可用 Mock 大模型跑通全链路（不消耗 LLM 额度）。
# Mock 回答为参考文档摘录+引用编号，检索不命中时返回拒答话术，可用于验证 SSE/[n] 引用/拒答/缓存回放。
BASE_URL=... TOKEN=... QUESTION="Kafka 改造有哪些 Topic" ./scripts/smoke_test.sh
# 产出：控制台输出引用编号检查 + TTFT（首 token 时间）测量结果
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TOKEN="${TOKEN:?需要环境变量 TOKEN（用户 JWT）}"
QUESTION="${QUESTION:-XYAI 后端 Kafka 改造一共设计了哪几个 Topic}"
CHAT_URL="${CHAT_URL:-$BASE_URL/chat/stream}"   # 按实际 SSE 端点调整
AUTH_HEADER="${AUTH_HEADER:-Authorization: Bearer $TOKEN}"

echo "================ RAG 冒烟验证 ================"
echo "[1] 健康检查 $BASE_URL"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$BASE_URL/actuator/health" 2>/dev/null || echo "000")
if [ "$HTTP_CODE" = "200" ]; then echo "  ✅ 服务在线"; else echo "  ⚠️  /actuator/health 返回 $HTTP_CODE（可能未开 actuator，忽略）"; fi

echo "[2] 发送问题并测量 TTFT：$QUESTION"
TMP=$(mktemp)
START_NS=$(date +%s%N)
# SSE 流式请求：拿到第一个 data 行即为首 token
curl -sN --max-time 60 -X POST "$CHAT_URL" \
  -H "$AUTH_HEADER" -H "Content-Type: application/json" \
  -d "{\"message\": \"$QUESTION\", \"conversationId\": null}" | while IFS= read -r line; do
    if [[ "$line" == data:* ]] && [[ -z "${FIRST_TS:-}" ]]; then
      FIRST_TS=$(date +%s%N)
      echo $(( (FIRST_TS - START_NS) / 1000000 )) > "$TMP.ttft"
      echo "  ✅ 首 token 到达，TTFT = $(cat "$TMP.ttft") ms"
    fi
    echo "$line" >> "$TMP"
done

echo "[3] 结果检查"
if [ -f "$TMP.ttft" ]; then
  TTFT=$(cat "$TMP.ttft")
  if [ "$TTFT" -lt 3000 ]; then echo "  ✅ TTFT=${TTFT}ms（<3s 达标）"; else echo "  ⚠️  TTFT=${TTFT}ms（≥3s，检查 embedding/检索耗时）"; fi
else
  echo "  ❌ 60s 内未收到任何 token，检查服务日志与端点路径（CHAT_URL=$CHAT_URL）"
fi

echo "[4] 引用编号检查（grounded prompt 应输出 [n] 引用）"
if grep -qE '\[1\]|\[2\]|\[[0-9]+\]' "$TMP"; then
  echo "  ✅ 回答包含引用编号"
else
  echo "  ⚠️  回答未包含 [n] 引用，检查 ModelInvocationService.SYSTEM_MESSAGE 与 buildRAGResult 的编号注入"
fi
if grep -q "知识库中" "$TMP"; then
  echo "  ℹ️  触发了拒答话术（可能是检索质量门控生效，确认该问题在知识库中有答案则属异常）"
fi

echo "[5] 第二次发送相同问题（验证多层缓存命中）"
START2=$(date +%s%N)
curl -sN --max-time 60 -X POST "$CHAT_URL" \
  -H "$AUTH_HEADER" -H "Content-Type: application/json" \
  -d "{\"message\": \"$QUESTION\", \"conversationId\": null}" | grep -m1 "^data:" >/dev/null
END2=$(date +%s%N)
echo "  第二次首 token 耗时 = $(( (END2 - START2) / 1000000 )) ms（L1/L2/L4 缓存命中应显著低于第一次）"

rm -f "$TMP" "$TMP.ttft"
echo "================ 完成 ================"
