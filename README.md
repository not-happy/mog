# mog-engine

自研 Java 游戏引擎骨架：**LWJGL 3 + OpenGL 3.3（Core Profile）**，不依赖任何游戏引擎。

当前状态：完整引擎底座——渲染（多光源/阴影/PBR+IBL/HDR后处理/粒子）+ ECS(位掩码+稀疏集)/场景图 + 模型加载 + **B阶段**：碰撞检测(AABB/射线拾取/碰撞事件)、骨骼动画(Assimp蒙皮+关键帧插值)、2D精灵批渲染、公告板粒子、A阶段基础设施（固定步长/事件总线/资产管理/字体HUD/音频/存读档）。

游戏层（《曜纪》，设计文档见 [docs/GDD-曜纪.md](docs/GDD-曜纪.md)）：
- **C1 星系模拟核心 ✅**：三体积分器（种子驱动 Kozai 互倾三重星）、纪元分类、宇宙视角场景（HDR 辉光/运动拖尾/时间控制）、Monte-Carlo 平衡工具
- **C2 地表场景骨架 ✅**：45° RTS 相机、32×32 建造网格地形、纪元驱动的太阳方向光与色调分级（GDD D5/D7）
- **C3 建造核心·最小闭环 ✅**：建筑类型表（5 种）、占位网格、鼠标拾取放置/拆除（左键放置、Shift+左键拆除、数字键 1-5 选型）、网格光标高亮、建造事件（场景只发、Game 订阅）
- 三体模拟为 Game 级持久会话（`CosmosSession`）：Tab 切换 地表↔宇宙，模拟不中断——"宇宙视角看到的三星之舞，就是地表经历的天气"
- C3 余项（资源节点/采集者寻路/生产链）未动，接口预留见 [docs/C3接口清单.md](docs/C3接口清单.md)

## 操作方式

**启动参数**：默认进地表场景；`--cosmos` 直进宇宙视角；`--demo` 进技术演示场（A/B 阶段引擎能力展示）；`--speed=N` 宇宙会话初始倍速（钳制 [0.5, 120]，调试加速用）。

**通用热键**：

| 按键 | 功能 |
|---|---|
| `Tab` | 切换 地表 ↔ 宇宙视角（Demo 离开后经 Tab 落到地表，不再回来） |
| `F5` / `F6` | 开关后处理（泛光/暗角/饱和度/纪元色调） / 开关背景音乐 |
| `F1` / `F3` | 切换鼠标捕获 / 输入调试日志 |
| `ESC` | 退出 |

**地表场景**（RTS 建造视角）：

| 操作 | 功能 |
|---|---|
| `W A S D` | 平移相机焦点（速度随高度缩放） |
| 滚轮 | 缩放（相机高度 8~80m） |
| 中键拖拽 | 平移焦点 |
| 右键拖拽 / `Q` `E` | 旋转偏航（俯角固定 45°） |
| 左键 | 放置建筑（绿高亮=可放，红=不可） |
| `Shift`+左键 | 拆除建筑（多格足迹点任一覆盖格均可） |
| 数字键 `1`-`5` | 选择建筑：指挥中枢/晶眠舱/采集站/天文台/列算阵 |
| `空格` / `+` `-` | 暂停 / 倍速×2 / 倍速×0.5（作用于共享的宇宙会话） |

**宇宙场景**（轨道观察视角）：

| 操作 | 功能 |
|---|---|
| 左键拖拽 | 旋转轨道相机 |
| 滚轮 | 缩放 |
| `空格` / `+` `-` | 暂停 / 倍速（与地表共享同一会话状态） |

**Demo 场景**（漫游）：`WASD` 移动、`空格`/`Shift` 升降、鼠标/方向键转视角、左键准星拾取、`F9`/`F10` 存读档。
（鼠标移动在远程桌面下不可靠，见排查章节）

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
│   │                      #   Animation/BoneMatrices/Emitter/Building
│   └── systems/           # Spin/Orbit/Animation/Transform(场景图)/Collision
├── physics/               # 碰撞几何 + 屏幕拾取
│   ├── Aabb.java          # 轴对齐包围盒（分离定理相交测试）
│   ├── Ray.java           # 射线（slab 法射线-AABB 求交）
│   └── ScreenPicker.java  # 静态工具：窗口坐标→NDC→逆 projView 射线→水平面求交（C3 建造拾取）
├── input/
│   └── InputHandler.java  # 轮询式键鼠状态 + 边沿检测 + 鼠标增量
├── astro/                 # 星系模拟（纯 Java，零 GL/ECS 依赖，《曜纪》C1）
│   ├── GravitySimulation.java # 三体积分器（自适应子步 + Plummer 软化，种子驱动）
│   ├── TripleConfigs.java # 混沌构型生成器（Kozai 互倾三重星 / 穿越家族）
│   ├── TripleState.java   # 三星+行星状态快照
│   ├── EpochClassifier.java # 纪元分类（序曜/乱曜/寒曜/烈曜/掠曜/三曜凌空/失家）
│   ├── Epoch.java / EpochType.java / EndingType.java # 纪元与终局数据
│   ├── HostTracker.java   # 宿主星滞回追踪（易天计数）
│   ├── FateJudge.java     # 终局判定（坠焚/失家/恒星弹射）
│   ├── GameCalendar.java  # 文明历（1 游戏年 = 2π 模拟单位 = 360 日）
│   └── tools/             # MonteCarloTool / ChaoticSpectrumTool（无头平衡性实测）
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
│   ├── TrailRenderer.java # 宇宙拖尾：环形缓冲动态 VBO + 加色混合（DYNAMIC）
│   ├── GridRenderer.java  # 地表建造网格线：贴地静态 VBO + alpha 混合（关背面剔除）
│   ├── Skeleton.java      # 骨架：节点树 + 骨骼逆绑定矩阵
│   ├── AnimationData.java # 动画：TRS 关键帧轨
│   ├── Animator.java      # 采样器：插值关键帧 -> 骨骼蒙皮矩阵
│   ├── PostProcessor.java # HDR 后处理链：明亮提取 -> 高斯ping-pong -> 曝光/tonemap/gamma/暗角/饱和度/纪元色调
│   ├── Camera.java        # 相机数据 + 视图矩阵
│   └── Renderer.java      # 两遍渲染：光空间深度遍（正交参数可调）+ 主遍（面向 World）
└── game/                  # 游戏层
    ├── Light.java         # 纯数据：方向光（intensity 可就地改写——地表太阳每步变化）
    ├── PointLight.java    # 纯数据：点光源（位置/颜色/衰减）
    ├── SaveManager.java   # 存读档：实体变换/轨道角/相机位姿 <-> JSON
    ├── CameraController.java # Demo 漫游相机：WASD + 鼠标/方向键视角
    ├── CameraRig.java     # 相机装备接口（update 走帧步长，与固定步长模拟解耦）
    ├── OrbitCameraRig.java # 宇宙轨道相机（球面坐标，左键拖拽旋转）
    ├── RtsCameraRig.java  # 地表 45° RTS 相机（焦点+高度+偏航，指数平滑，边界钳制）
    ├── CosmosSession.java # 三体模拟会话（Game 级持久唯一真源：模拟/纪元/拖尾缓冲/时间控制）
    ├── CosmosScene.java   # 宇宙场景：轨道线球体 + 拖尾 + 会话视图
    ├── CosmosViewSystem.java # 会话 -> ECS 视图同步（薄壳系统，仅注册进宇宙 World）
    ├── SurfaceScene.java  # 地表场景（C3 建造闭环）：网格地形 + 放置/拆除 + 纪元氛围
    ├── SurfaceSky.java    # 静态工具：三体模拟 -> 地表太阳方向/颜色/强度
    ├── EpochPalette.java  # 静态调色板：7 纪元 -> 阳光色/色调分级参数（GDD D7）
    ├── BuildGrid.java     # 静态工具：32×32 建造网格换算（worldToCell/cellToWorld/snap）
    ├── BuildingType.java  # 建筑类型静态表（5 种：显示名/足迹/尺寸/颜色，成本字段预留不扣）
    ├── OccupancyGrid.java # 占位网格（entityId+1 偏移存储，canPlace/place/clear/entityAt）
    ├── TerrainBuilder.java # 静态工具：种子化缓丘高度场 -> 地形网格 + heightAt 查询
    └── DemoScene.java     # 技术演示场（--demo）：A/B 阶段引擎能力展示
```

### ECS 数据流

```
Scene.init()（Demo/Surface/Cosmos）→  World（实体 + 组件 + 系统）
Game.loop 每帧 →  cosmos.tick(FIXED_DT)（固定步长，先于场景——模拟跨场景持久）
               →  world.update(dt)：场景各自注册的系统（如 TransformSystem 层级世界矩阵）
               →  renderer.renderShadowDepthPass(world, ...)  深度遍（DepthMap FBO）
               →  post.beginScene() + renderer.render(...)    场景遍（SceneFBO）
               →  scene.renderOverlay(...)                     叠加遍（拖尾/建造网格线）
               →  post.process()                               后处理链（bright→blur×6→composite(含纪元色调)→屏幕）
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

### C 阶段：《曜纪》技术线（GDD §7，进行中）

| # | 里程碑 | 状态 |
|---|---|---|
| C1 | 星系模拟核心：三体积分器/纪元分类/宇宙视角场景 | ✅（含 S1 测量工具、S2 玩法层事件链） |
| C2 | 地表场景骨架：45° RTS 相机/建造网格地形/纪元氛围 | ✅（骨架完成；无缝缩放过渡未做，现为 Tab 硬切换） |
| C3 | 建造核心：网格放置/拆除、资源节点、采集者寻路、生产链 | 🔶 最小闭环 ✅（建筑表/占位网格/拾取放置拆除/光标高亮/建造事件）；资源节点、寻路、生产链未动 |
| C4+ | 人口气候 / UI 控件库 / 研究成就 / 终局流程 | 未开始 |

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
