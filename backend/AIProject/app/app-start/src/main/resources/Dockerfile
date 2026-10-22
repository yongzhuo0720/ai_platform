FROM openjdk:8u102-jre
LABEL maintainer="HeNeng"

# 时区设置
RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo 'Asia/Shanghai' >/etc/timezone
# 创建工作目录（用于挂载配置）
RUN mkdir -p /home/app/ai/config && \
    mkdir -p /home/app/ai/Logs
# 声明端口和挂载点
EXPOSE 8507
# 声明挂载点（配置、日志、数据）
VOLUME ["/home/app/ai/config", "/home/app/ai/Logs"]
# 设置工作目录
WORKDIR /home/app/ai
# 复制JAR包
COPY app-start-1.0-SNAPSHOT.jar /home/app/ai/app-start-1.0-SNAPSHOT.jar

# 启动命令
ENTRYPOINT ["java", "-jar", "/home/app/ai/app-start-1.0-SNAPSHOT.jar", "--spring.config.additional-location=file:/home/app/ai/config/"]
