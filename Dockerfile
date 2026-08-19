# ── Stage 1: Build ────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# 先只复制 pom 文件，利用 Docker 层缓存，依赖未变时跳过下载
COPY pom.xml .
COPY tify-common/pom.xml    tify-common/pom.xml
COPY tify-provider/pom.xml  tify-provider/pom.xml
COPY tify-agent/pom.xml     tify-agent/pom.xml
COPY tify-mcp/pom.xml       tify-mcp/pom.xml
COPY tify-chat/pom.xml      tify-chat/pom.xml
COPY tify-workflow/pom.xml  tify-workflow/pom.xml
COPY tify-knowledge/pom.xml tify-knowledge/pom.xml
COPY tify-app/pom.xml       tify-app/pom.xml

RUN mvn dependency:go-offline -q

# 再复制源码编译
COPY tify-common/src    tify-common/src
COPY tify-provider/src  tify-provider/src
COPY tify-agent/src     tify-agent/src
COPY tify-mcp/src       tify-mcp/src
COPY tify-chat/src      tify-chat/src
COPY tify-workflow/src  tify-workflow/src
COPY tify-knowledge/src tify-knowledge/src
COPY tify-app/src       tify-app/src

RUN mvn package -DskipTests -q

# 用 layertools 拆分 jar，让运行阶段的层缓存更细
RUN java -Djarmode=layertools \
    -jar tify-app/target/tify-app-*.jar extract \
    --destination /build/layers

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

# 非 root 用户运行
RUN apk add --no-cache tzdata \
    && addgroup -S tify \
    && adduser -S tify -G tify

WORKDIR /app

# 按变化频率从低到高分层，最大化缓存命中
COPY --from=builder --chown=tify:tify /build/layers/dependencies/          ./
COPY --from=builder --chown=tify:tify /build/layers/spring-boot-loader/    ./
COPY --from=builder --chown=tify:tify /build/layers/snapshot-dependencies/ ./
COPY --from=builder --chown=tify:tify /build/layers/application/           ./

# 挂载点：外部 application.yml 和日志目录
VOLUME ["/app/config", "/app/logs"]

USER tify

EXPOSE 8080

ENV TZ=Asia/Shanghai \
    SERVER_PORT=8080 \
    JVM_OPTS="-Xms256m -Xmx512m -Duser.timezone=Asia/Shanghai"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:${SERVER_PORT}/api/v1/health || exit 1

ENTRYPOINT ["sh", "-c", \
  "exec java ${JVM_OPTS} \
    -Djava.security.egd=file:/dev/./urandom \
    org.springframework.boot.loader.launch.JarLauncher \
    --spring.config.additional-location=optional:file:/app/config/application.yml"]
