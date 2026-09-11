# ================= 构建阶段 =================
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# 先只拷 pom 并预下载依赖 —— 利用 Docker 层缓存，改代码时不必重新下载依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ================= 运行阶段 =================
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 用非 root 用户运行（安全规范，面试可提）
RUN useradd -r -u 1001 appuser && chown -R appuser /app
USER appuser

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

# 用 MaxRAMPercentage 而不是写死 -Xmx，容器内存上限变化时自动适配
ENTRYPOINT ["sh", "-c", "java \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=70 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/logs \
  -Duser.timezone=Asia/Shanghai \
  -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} \
  -jar app.jar"]
