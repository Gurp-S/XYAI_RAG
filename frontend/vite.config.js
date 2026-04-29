import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { createHash } from "crypto";

// 简易密码保护配置（开发模式）
const DEV_PWD = "xyRag2026";
const COOKIE_NAME = "XYAI_DEV_AUTH";
function hashValue(v) {
  return createHash("sha256")
    .update(String(v || ""), "utf8")
    .digest("hex");
}
const EXPECTED = hashValue(DEV_PWD);

function createPasswordPlugin() {
  return {
    name: "xyai-dev-password-protect",
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        try {
          const host = req.headers.host || "localhost";
          const full = new URL(req.url, `http://${host}`);

          // 解析 cookie
          const cookieHeader = req.headers.cookie || "";
          const cookies = {};
          cookieHeader.split(";").forEach((pair) => {
            const p = String(pair || "").trim();
            if (!p) return;
            const idx = p.indexOf("=");
            if (idx === -1) return;
            const k = p.slice(0, idx).trim();
            const v = p.slice(idx + 1).trim();
            cookies[k] = v;
          });

          // 已经有有效 cookie，允许继续
          if (cookies[COOKIE_NAME] === EXPECTED) {
            return next();
          }

          // 通过 ?pwd=xy 进行授权（一次性），设置 cookie 并重定向去掉 pwd
          const provided = full.searchParams.get("pwd");
          if (provided && hashValue(provided) === EXPECTED) {
            full.searchParams.delete("pwd");
            const cookieStr = `${COOKIE_NAME}=${EXPECTED}; Path=/; HttpOnly; SameSite=Strict`;
            res.setHeader("Set-Cookie", cookieStr);
            res.statusCode = 302;
            res.setHeader("Location", full.pathname + (full.search || ""));
            res.end();
            return;
          }

          // 未授权：不返回任何 UI 内容（空响应）以避免泄露信息
          res.statusCode = 403;
          res.setHeader("Content-Type", "text/html; charset=utf-8");
          res.end("");
        } catch (e) {
          // 任何异常都回退到下一中间件，避免阻塞 dev server
          return next();
        }
      });
    },
  };
}

export default defineConfig({
  plugins: [vue(), createPasswordPlugin()],
  server: {
    // 监听 IPv6 未指定地址
    host: "::",
    port: 5173,
    proxy: {
      "/upload": { target: "http://localhost:8080", changeOrigin: true },
      "/ai": { target: "http://localhost:8080", changeOrigin: true },
      "/user": { target: "http://localhost:8080", changeOrigin: true },
      "/milvus": { target: "http://localhost:8080", changeOrigin: true },
      "/oss": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
  },
});
