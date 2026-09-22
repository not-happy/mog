# mog-engine

自研 Java 游戏引擎骨架：**LWJGL 3 + OpenGL 3.3（Core Profile）**，不依赖任何游戏引擎。

当前状态：完整引擎底座——渲染（多光源/阴影/PBR+IBL/HDR后处理/粒子）+ ECS(位掩码+稀疏集)/场景图 + 模型加载 + **B阶段**：碰撞检测(AABB/射线拾取/碰撞事件)、骨骼动画(Assimp蒙皮+关键帧插值)、2D精灵批渲染、公告板粒子、A阶段基础设施（固定步长/事件总线/资产管理/字体HUD/音频/存读档）。

## 操作方式

| 按键/操作 | 功能 |
|---|---|
| `W A S D` | 水平移动（相对朝向） |
| `空格` / `左Shift` | 上升 / 下降 |
| `↑ ↓ ← →` 方向键 | 转动视角（键盘替代操作，与鼠标等效） |
| 鼠标移动 | 转动视角（俯仰限制 ±89°；**远程桌面下不可靠，见排查章节**） |
| `F1` | 切换鼠标捕获（锁定/释放光标） |
| `F3` | 切换输入调试日志（原始 dx/dy + yaw/pitch，诊断鼠标漂移用） |
| `F5` | 开关后处理（泛光/暗角/饱和度），伴随提示音（事件驱动音频演示） |
| `F6` | 开关背景音乐（程序化合成的和弦软垫） |
| `F9` / `F10` | 存档 / 读档（`saves/game.json`，恢复实体变换与相机位姿） |
| 鼠标左键 | 准星拾取（射线-AABB，命中实体名显示在 HUD + 日志） |
| `ESC` | 退出 |

## 日志与崩溃排查

- 日志框架：SLF4J + Logback，双通道输出（控制台 + `logs/mog-engine.log`）
- 滚动策略：按天 + 单文件 10MB 切分，保留 7 天，总量 100MB 封顶
- 启动时自动记录 OpenGL 版本/渲染器/厂商（判断真实 GPU 还是软件渲染）
- 三层异常防线：`Game.start()` 捕获可恢复异常 → `CrashHandler` 兜住所有线程的
  未捕获异常（日志 + 崩溃对话框）→ shutdown hook 覆盖 kill 信号退出
- Java 层崩溃看 `logs/mog-engine.log`；**原生层崩溃（驱动 segfault）看工作目录的
  `hs_err_pid*.log`**——JVM 异常处理管不到原生代码
- 开发期可加 JVM 参数强化诊断：`-Dorg.lwjgl.util.Debug=true`（LWJGL 调试断言）、
  `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs/`

### 已知环境问题：RDP 远程桌面污染鼠标输入（已实测确认）

通过日志诊断实锤：在远程桌面会话中，`GLFW_CURSOR_DISABLED` 模式下任何鼠标移动
都会伴随**恒定 ~325px/帧的 Y 轴注入流**（折算 ~19500px/秒，人手不可能产生），
数秒内把 pitch 顶到下限，表现为"视角不受控下沉/轻微移动方向错乱"。
静止时 delta 恒为 0（无漂移），dx 忠实反映真实移动——即污染只发生在移动期间、只在 Y 轴。

- 根因：远程桌面协议以低频绝对坐标重放鼠标，与 GLFW 虚拟累积坐标机制冲突，应用层无法根治
- 应对：① 用**方向键转视角**（键盘事件在 RDP 下传输干净可靠）；
  ② 鼠标手感验证请在**本地物理机**上进行（拷贝 fat jar + JDK 17 即可运行）
- 诊断方法：logback.xml 把 `com.mog.input.InputHandler` / `com.mog.game.CameraController`
  调成 DEBUG，游戏内按 F3，静止时 delta 应恒为 0，移动时对照 `[mouse]`（原始）与
  `[look]`（钳制后+角度变化）两行定位问题环节

## 环境要求

- JDK 17+
- Maven 3.9+（项目自带 Maven Wrapper，可用 `mvnw` / `mvnw.cmd`）
- 支持 OpenGL 3.3 的显卡（2010 年后的机器基本都支持）

## 运行

```bash
# 开发模式（Maven 直接运行）
mvnw.cmd compile exec:java

# 或打 fat jar 运行
mvnw.cmd package
java -jar target/mog-engine-0.1.0-SNAPSHOT.jar
```

操作：`ESC` 或关闭窗口退出；标题栏实时显示 FPS；窗口可自由缩放。

## 工程结构

```
src/main/java/com/mog/
├── Main.java              # 入口
├── core/                  # 引擎核心
│   ├── Game.java          # 主循环：固定步长模拟(60Hz) + 可变帧渲染
│   ├── Scene.java         # 场景接口（为场景切换/SceneManager 铺路）
│   ├── Window.java        # GLFW 窗口封装（vsync、resize、FPS 标题、GL 环境信息日志）
│   ├── Timer.java         # delta time
│   ├── CrashHandler.java  # 全局异常处理（未捕获异常日志 + 崩溃对话框 + shutdown hook）
│   └── event/             # EventBus 发布订阅 + 事件定义（子系统解耦）
├── asset/
│   └── AssetManager.java  # 资产缓存 + 引用计数 + 异步预加载
├── audio/
│   └── AudioEngine.java   # OpenAL + 程序化合成音效/BGM（无设备自动哑模式）
├── ui/
│   ├── FontAtlas.java     # stb_truetype 字形图集（系统字体探测，含中文烘焙）
│   ├── TextRenderer.java  # immediate-mode 文本批渲染（HUD）
│   └── SpriteBatch.java   # 2D 精灵批渲染（同纹理自动合批，纹理切换 flush）
├── input/
│   └── InputHandler.java  # 轮询式键鼠状态 + 边沿检测 + 鼠标增量
├── ecs/                   # 实体-组件-系统（位掩码签名 + 稀疏集存储）
│   ├── Component.java     # 组件标记接口（纯数据，禁止逻辑）
│   ├── GameSystem.java    # 系统接口（每帧由 World 调度）
│   ├── Transforms.java    # 变换计算工具（TRS 矩阵构建）
│   ├── World.java         # 稀疏集组件仓库 + 64位签名 view 查询 + 系统调度
│   ├── components/        # Transform/Parent/WorldMatrix/Mesh/Material/PbrMaterial/
│   │                      #   Texture/PointLight/Spin/Orbit/Collider/WorldAabb/
│   │                      #   Animation/BoneMatrices/Emitter
│   └── systems/           # Spin/Orbit/Animation/Transform(场景图)/Collision
├── physics/               # 碰撞几何
│   ├── Aabb.java          # 轴对齐包围盒（分离定理相交测试）
│   └── Ray.java           # 射线（slab 法射线-AABB 求交）
├── input/
│   └── InputHandler.java  # 轮询式键鼠状态 + 边沿检测 + 鼠标增量
├── render/                # 渲染层（所有 GL 调用集中在这里）
│   ├── Shader.java        # GLSL 编译/链接/uniform 缓存
│   ├── ShaderSet.java     # 五条管线着色器（顶点色/纹理/光照/PBR/阴影深度）
│   ├── Mesh.java          # VAO/VBO/EBO 封装（position/color/texCoord/normal）
│   ├── Shapes.java        # 基础几何体工厂（立方体/纯色立方体/平面）
│   ├── ModelLoader.java   # Assimp 加载 obj/gltf/glb/fbx -> LoadedModel
│   ├── LoadedModel.java   # 模型加载结果：网格/材质/贴图/包围盒
│   ├── Material.java      # 纯数据：经典材质（颜色/高光参数）
│   ├── PbrMaterial.java   # 纯数据：PBR 材质（albedo/metallic/roughness）
│   ├── Texture.java       # stb_image 解码（classpath/文件/内存字节）+ mipmap
│   ├── DepthMap.java      # 阴影深度 FBO（1024² 深度纹理）
│   ├── FrameBuffer.java   # 通用 FBO：颜色纹理(可选 RGBA16F HDR) + 深度 Renderbuffer
│   ├── Environment.java   # IBL：程序化天空 -> 辐照度图/预滤波环境图/BRDF LUT
│   ├── ParticleEngine.java # 粒子：CPU 模拟(SoA池) + 相机公告板四边形 + 加色混合
│   ├── Skeleton.java      # 骨架：节点树 + 骨骼逆绑定矩阵
│   ├── AnimationData.java # 动画：TRS 关键帧轨
│   ├── Animator.java      # 采样器：插值关键帧 -> 骨骼蒙皮矩阵
│   ├── PostProcessor.java # HDR 后处理链：明亮提取 -> 高斯ping-pong -> 曝光/tonemap/gamma/暗角/饱和度
│   ├── Camera.java        # 相机数据 + 视图矩阵
│   └── Renderer.java      # 两遍渲染：光空间深度遍 + 主遍（面向 World）
└── game/                  # 游戏层
    ├── Light.java         # 纯数据：方向光
    ├── PointLight.java    # 纯数据：点光源（位置/颜色/衰减）
    ├── SaveManager.java   # 存读档：实体变换/轨道角/相机位姿 <-> JSON
    ├── CameraController.java # 相机行为：WASD 漫游 + 鼠标/方向键视角
    └── DemoScene.java     # 世界构建器：创建实体挂组件、注册系统、持有资源所有权
```

### ECS 数据流

```
DemoScene.init()  →  World（实体 + 组件 + 系统）
Game.loop 每帧    →  world.update(dt)：SpinSystem → OrbitSystem → TransformSystem（层级世界矩阵）
                  →  renderer.renderShadowDepthPass(world, ...)  深度遍（DepthMap FBO）
                  →  post.beginScene() + renderer.render(...)    场景遍（SceneFBO）
                  →  post.process()                              后处理链（bright→blur×6→composite→屏幕）
```

### 管线选择规则（Renderer）

| 实体组件组合 | 使用的着色器 |
|---|---|
| 有 PbrMaterialComponent | `pbr.vert/frag`（Cook-Torrance + 阴影 + 色调映射） |
| 有 MaterialComponent | `lit.vert/frag`（多光源 Blinn-Phong + 阴影） |
| 无材质有 TextureComponent | `textured.vert/frag` |
| 都没有 | `default.vert/frag`（顶点色，灯标记"自发光"用） |

### 场景图层级

实体可挂 `ParentComponent(父实体ID)`；`TransformSystem` 每帧递归求解
`世界矩阵 = 父世界矩阵 × 本地TRS`，结果缓存在 `WorldMatrixComponent`（记忆化，父链只算一次）。
鸭子模型 = 根实体（位置/缩放/自旋）+ 子网格实体（本地单位变换）。深度上限 64 防成环。

### 设计约定

- **组件与数据类（Component / Material / Light / PointLight）只持有数据，不含逻辑**；
  行为逻辑全部在 System（SpinSystem/OrbitSystem/CameraController），矩阵构建与管线选择在 Renderer。
- 所有原生内存用 `MemoryStack`（自动释放）或 `memAlloc/memFree`（配对释放）。
- 顶点属性约定：`location 0 = position(vec3)`，`1 = color(vec3)`，`2 = texCoord(vec2)`，`3 = normal(vec3)`。

## 下一步路线图

1. ~~纹理渲染：stb_image 加载 PNG + 采样器 uniform~~ ✅
2. ~~光照：Phong 模型（环境光/漫反射/镜面反射）~~ ✅（Blinn-Phong，方向光）
3. ~~相机漫游：WASD + 鼠标视角~~ ✅
4. ~~3D 模型加载：Assimp 导入 obj/gltf~~ ✅
5. ~~模型自带材质/贴图：解析 Assimp 材质 + 嵌入式纹理~~ ✅
6. ~~多光源：方向光 + 点光源（距离衰减）~~ ✅
7. ~~阴影贴图：光空间深度遍 + PCF 软阴影 + 自适应偏差~~ ✅（方向光）
8. ~~ECS 架构：实体-组件-系统~~ ✅（Map 存储教学版；实体上千时升级位掩码+紧凑数组）
9. ~~PBR 渲染：glTF 金属度-粗糙度工作流（Cook-Torrance + 直接光 + 阴影）~~ ✅
10. ~~场景图层级：ParentComponent + TransformSystem 递归世界矩阵~~ ✅
11. ~~后处理管线：泛光（明亮提取+高斯ping-pong）+ 暗角 + 饱和度，F5 开关~~ ✅
12. ~~HDR 管线：RGBA16F 场景缓冲 + 全链路线性空间 + 曝光/tonemap/gamma 统一到合成遍~~ ✅
13. ~~IBL 环境光照：程序化天空 + 辐照度图 + 预滤波环境图 + BRDF LUT（split-sum）~~ ✅

### A 阶段：引擎基础设施（已全部完成 ✅）

| # | 里程碑 | 实现 |
|---|---|---|
| A1 | 固定时间步长循环 | 60Hz accumulator + 死亡螺旋保护；相机走帧步长保手感 |
| A2 | 事件总线 | 类型精确匹配 pub/sub；resize/F5 已事件化 |
| A3 | 资产管理器 | 引用计数 + 加载器注册表 + 异步预载接口；纹理按 `?repeat` 参数区分资产 |
| A4 | 文本渲染 + HUD | stb_truetype 烘焙系统字体（含中文）→ R8 图集 → 动态批渲染；4 行调试 HUD |
| A5 | 音频引擎 | OpenAL + 运行时合成（blip 提示音/和弦软垫 BGM）；无设备哑模式降级 |
| A6 | 场景序列化 | Scene 接口 + Gson 存读档（实体变换/轨道角/相机位姿 ↔ saves/game.json）|

### B 阶段：模拟与表现（已全部完成 ✅）

| # | 里程碑 | 实现 |
|---|---|---|
| B1 | 碰撞核心 | AABB 分离定理 + CollisionSystem(新重叠->CollisionEvent->音效) + 射线准星拾取(slab法) + 相机地面约束 |
| B2 | 骨骼动画 | Assimp 解析节点树/骨骼权重/关键帧 -> Skeleton/AnimationData -> Animator 采样(slerp) -> 蒙皮 PBR 管线(bones[96] uniform)；CesiumMan 行走循环 |
| B3 | 2D 精灵管线 | SpriteBatch：正交投影 + 动态合批 + 纹理切换自动 flush + 色调 |
| B4 | 粒子系统 | SoA 池(2048) + 发射器组件 + 重力/寿命/淡出 + 相机公告板四边形 + 加色混合(参与泛光) |
| B5 | ECS 升级 | 64位组件签名 + 稀疏集存储(O(1) 增删查) + view 从最小集过滤 + 结果列表缓存复用；API 与教学版完全兼容 |

### 软件渲染器（WARP）已知问题

- `glReadPixels` 在 Mesa-d3d12/WARP 上会挂起（PostProcessor 像素自检已停用，真实 GPU 可恢复）
- `GL_POINTS` 点精灵绘制会挂起（粒子已改用公告板四边形，这也是商业引擎主流做法）

### 后续（C 阶段候选：类型分叉）

- **C-MC 体素线**：区块系统/体素网格化(greedy meshing)/噪声地形生成/光照传播(BFS)/方块交互/区域存档
- **C-暗黑 ARPG 线**：NavMesh+A* 寻路/等距相机/随机地牢/战利品背包
- **C-塞尔达线**：第三人称相机/角色物理深化/动画混合树/交互任务系统
- **C-DNF 线**：帧数据系统(前摇/判定框/取消窗口)/多图层横版场景/连招技能树

## 资源目录

```
assets/models/duck.glb   # Khronos glTF 示例模型（MIT/CC0），运行时从文件系统加载
```

模型通过文件系统相对路径加载（Assimp 不接受 classpath 流），**务必在项目根目录运行**：

```bash
mvnw.cmd compile exec:java                        # cwd 天然是项目根
java -jar target/mog-engine-0.1.0-SNAPSHOT.jar    # 也要在根目录执行
```

## 参考资料

- [3D Game Development with LWJGL 3](https://lwjglgamedev.gitbooks.io/3d-game-development-with-lwjgl/content/) — 本骨架的结构蓝本
- [LearnOpenGL](https://learnopengl.com/) — 图形学概念圣经（C++ 代码，概念通用）
- [LWJGL 官方示例](https://github.com/LWJGL/lwjgl3/tree/master/modules/samples)
