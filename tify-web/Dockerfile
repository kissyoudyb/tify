# ── Stage 1: Build ────────────────────────────────────────────
FROM node:20-alpine AS builder

WORKDIR /build

# 先复制 package 文件，依赖未变时跳过 npm install
COPY package.json package-lock.json ./
RUN npm ci --silent

COPY . .
RUN npm run build

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM nginx:1.27-alpine

# 删除默认配置
RUN apk add --no-cache tzdata \
    && rm /etc/nginx/conf.d/default.conf

COPY --from=builder /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/tify.conf

ENV TZ=Asia/Shanghai

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost/api/v1/health || exit 1

CMD ["nginx", "-g", "daemon off;"]
