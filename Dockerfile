# ---------- 建置階段 ----------
# 相依先解析再複製原始碼：pom.xml 沒變時，相依層可命中快取。
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- 拆層階段 ----------
# Spring Boot 的 layered jar：依賴與應用程式碼分屬不同層。
# 壓測要反覆重建映像，改一行程式碼只需重建最上層。
FROM eclipse-temurin:21-jre AS layers
WORKDIR /layers
COPY --from=builder /build/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------- 執行階段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# 由變動頻率最低到最高，讓 docker layer cache 發揮作用
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./

# heap 上限由 compose 的 JAVA_TOOL_OPTIONS 提供，不在此寫死 ——
# 不同的資源限制需要不同的值，寫死會讓 compose 的設定失效。
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
