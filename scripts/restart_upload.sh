#!/bin/bash
# 登录 evalbot 并上传文档触发重索引
source "$(dirname "$0")/.local.env"
PORT=8080
for i in $(seq 1 36); do
  code=$(curl -s --noproxy "*" -o /dev/null -w "%{http_code}" http://localhost:$PORT/actuator/health --max-time 3)
  [ "$code" = "200" ] && echo "backend UP" && break
  sleep 5
done
resp=$(curl -s --noproxy "*" -X POST "http://localhost:$PORT/user/login?id=$EVALBOT_ID" -F "password=$EVALBOT_PW" --max-time 10)
token=$(echo "$resp" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
echo "token_len=${#token}"
if [ -z "$token" ]; then echo "LOGIN FAILED: $resp" | head -c 300; exit 1; fi
echo "$token" > /tmp/xy_token.txt
curl -s --noproxy "*" -X POST "http://localhost:$PORT/upload/up" -H "Authorization: Bearer $token" \
  -F "file=@kafka教程.md" \
  -F "file=@docs/RAG系统对标研究与优化路线.md" \
  -F "file=@docs/RAG企业级优化实施计划.md" \
  -F "collectionName=my_ai" --max-time 120
echo
