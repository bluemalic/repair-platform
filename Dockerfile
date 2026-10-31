# ================= 构建阶段 =================
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 国内构建时换镜像源。默认的 Maven Central 在国内常出现 TLS 中断或极慢——
# 实测本机 Docker 里连续两次都卡在 "SSL peer shut down incorrectly"，
# 表现是 docker compose build 失败在一行看不出原因的 PluginResolutionException 上。
#
# 用法（服务器上建议写进 .env，见 .env.example）：
#   MAVEN_MIRROR=https://maven.aliyun.com/repository/public
# 不传就用默认的 Maven Central（CI 在境外跑时不必传）。
ARG MAVEN_MIRROR
RUN if [ -n "$MAVEN_MIRROR" ]; then \
      mkdir -p /root/.m2 && \
      printf '%s' "<settings><mirrors><mirror><id>mirror</id><mirrorOf>*</mirrorOf><url>$MAVEN_MIRROR</url></mirror></mirrors></settings>" > /root/.m2/settings.xml && \
      echo "已启用 Maven 镜像源: $MAVEN_MIRROR"; \
    fi

# 先只拷 pom 并预下载依赖 —— 利用 Docker 层缓存，改代码时不必重新下载依赖。
# 多模块（ADR-024）后 reactor 里有 common / domain / notification / app 四个模块：
# 聚合 POM 和每个模块自己的 pom 都要拷（reactor 读图要用全量 POM），go-offline 才能解析。
COPY pom.xml .
COPY repair-common/pom.xml repair-common/pom.xml
COPY repair-domain/pom.xml repair-domain/pom.xml
COPY repair-notification-service/pom.xml repair-notification-service/pom.xml
COPY repair-timeout-service/pom.xml repair-timeout-service/pom.xml
COPY repair-gateway-service/pom.xml repair-gateway-service/pom.xml
COPY repair-app/pom.xml repair-app/pom.xml
RUN mvn -B -q dependency:go-offline

# -pl app -am 会构建 app 的全部依赖：common / domain / notification / timeout 四个模块。
# 所以这四个模块的**源码**都必须在构建上下文里——少拷一个，那个模块就是空 jar，
# app 的主代码在编译时会找不到符号（2026-10-30 的 CD 就是这样红的）。
# gateway 只是 reactor 里的一个 POM（不被 app 依赖、也不构建），拷 POM 即可。
COPY repair-common/src ./repair-common/src
COPY repair-domain/src ./repair-domain/src
COPY repair-notification-service/src ./repair-notification-service/src
COPY repair-timeout-service/src ./repair-timeout-service/src
COPY repair-app/src ./repair-app/src
# 用 maven.test.skip 而不是 skipTests：生产镜像不需要测试，而 skipTests 仍会**编译测试**——
# 测试类直接 new 跨模块的实现类（如 TimeoutServiceImpl），在镜像裁剪后的 classpath 上
# 曾解析到旧的 5 参数快照导致 testCompile 失败（2026-10-31 的 CD）。测试由 CI 全量跑。
RUN mvn -B clean package -Dmaven.test.skip=true -pl repair-app -am

# ================= 运行阶段 =================
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 用非 root 用户运行（安全规范，面试可提）
RUN useradd -r -u 1001 appuser && chown -R appuser /app
USER appuser

COPY --from=builder /build/repair-app/target/*.jar app.jar

EXPOSE 8080

# 用 MaxRAMPercentage 而不是写死 -Xmx，容器内存上限变化时自动适配。
# 比例走环境变量：默认 70% 是本地/大内存机器的值，小内存机型（2C2G）要下调到 55% 左右——
# 堆只占 JVM 的一部分，元空间、线程栈、直接内存加起来能到 200MB 以上，
# 堆没满而容器先被 OOM killer 杀掉，是最难查的那种"莫名其妙重启"。
ENTRYPOINT ["sh", "-c", "java \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=${JAVA_MAX_RAM_PERCENTAGE:-70} \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/logs \
  -Duser.timezone=Asia/Shanghai \
  -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} \
  -jar app.jar"]
