# AI 平台后端部署与运维文档

---

## 一、基础环境

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | **1.8** | | |
| Maven | **3.6+** | 构建打包 |
| PostgreSQL | **14** | 数据库 |
| Redis | **6.0+** | 缓存 |

确认环境：
```bash
java -version          # 输出 1.8.x
mvn -v                 # 输出 3.6 以上
```

---

## 二、打包编译

```bash
# 进入项目根目录
cd AIProject

# 打包（跳过测试）
mvn clean package -DskipTests
```

> 产物在 `app/app-start/target/app-start-1.0-SNAPSHOT.jar`

---

## 三、部署

### 1) 上传 JAR

```bash
scp app/app-start/target/app-start-1.0-SNAPSHOT.jar root@<服务器IP>:/opt/aiproject/
```

### 2) 首次启动（自动生成运维脚本）

```bash
cd /opt/aiproject
java -jar app-start-1.0-SNAPSHOT.jar --spring.profiles.active=dev
```

首次启动成功后，框架会自动在 `/opt/aiproject/` 下生成 `start.sh`、`stop.sh`，同时在 `/etc/systemd/system/` 下注册 `aiproject.service` 并设置开机自启。

> 看到启动日志正常后 `Ctrl+C` 停止，后续使用 `systemctl` 管理。

### 3) 启动服务

```bash
systemctl daemon-reload
systemctl start aiproject
systemctl status aiproject   # 确认状态为 active (running)
```

### 4) 验证

```bash
curl http://localhost:8507/ai/doc.html
```

浏览器访问 `http://<服务器IP>:8507/ai/doc.html` 可看到 Swagger 接口文档。

### 5) 常用命令

```bash
systemctl start aiproject       # 启动
systemctl stop aiproject        # 停止
systemctl restart aiproject     # 重启
systemctl status aiproject      # 查看状态

```

### 6) 更新部署

```bash
# 备份旧包
cp /opt/aiproject/app-start-1.0-SNAPSHOT.jar /opt/aiproject/backup/app-start-$(date +%Y%m%d%H%M).jar

# 上传新包
scp app-start-1.0-SNAPSHOT.jar root@<服务器IP>:/opt/aiproject/

# 重启
systemctl restart aiproject
```

### 7) 关键配置

需要修改的配置项位于 `app/app-start/src/main/resources/application-dev.properties`：

```properties
server.port=8507
hnsp.datasource.ip=你的数据库地址:5432
hnsp.datasource.username=postgres
hnsp.datasource.password=你的密码
hnsp.redis.host=你的Redis地址
hnsp.redis.port=26830
hnsp.redis.password=你的密码
```

---

## 四、二次开发

### 项目结构

```
AIProject/
├── pom.xml                          # 根 POM
├── app/app-start/                   # 启动模块（入口、配置）
├── app-business/
│   ├── app-business-service/        # 业务服务（Bean/Controller/Holder/Mapper）
│   └── app-business-permission/     # 权限服务（用户、角色、标签）
└── app-common/                      # 公共模块（DTO、工具类）
```

### 开发约定

| 层 | 包路径 | 职责 |
|----|--------|------|
| **Bean** | `.../service/bean/` | 实体类，对应数据库表 |
| **Mapper** | `.../service/mapper/` | 数据访问层，继承 MyBatis-Plus BaseMapper |
| **Holder** | `.../service/holde/` | 业务逻辑层（等同于 Service） |
| **Controller** | `.../service/controller/` | REST 接口层 |
| **DTO** | `.../common/dto/` 或 `.../service/dto/` | 数据传输对象 |

```

### 启动本地调试

```bash
# IDE 中直接运行 AppStartApplication.java 的 main 方法即可
# 或在终端
mvn spring-boot:run -pl app/app-start -Dspring-boot.run.profiles=dev
```

### 接口文档

启动后访问 `http://localhost:8507/ai/doc.html` 即可查看和调试全部接口。
