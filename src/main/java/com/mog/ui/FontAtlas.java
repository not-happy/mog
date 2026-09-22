package com.mog.ui;

import org.lwjgl.stb.STBTTFontinfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.stb.STBTruetype.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * 字体图集：用 stb_truetype 把指定字符集光栅化进一张单通道 GL 纹理。
 *
 * 策略：启动时一次性烘焙（ASCII + 调用方文本里出现的 CJK 字符）。
 * 动态字形缓存（按需烘焙任意汉字）留作后续升级，当前 HUD 文案固定，静态烘焙足够。
 */
public class FontAtlas {

    private static final Logger log = LoggerFactory.getLogger(FontAtlas.class);

    /** 系统字体候选（按序探测，取第一个可用的） */
    private static final String[] FONT_CANDIDATES = {
            "C:/Windows/Fonts/msyh.ttc",     // 微软雅黑（中文）
            "C:/Windows/Fonts/simhei.ttf",   // 黑体（中文）
            "C:/Windows/Fonts/arial.ttf",    // 英文兜底
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/System/Library/Fonts/Helvetica.ttc",
    };

    /** 单个字形的图集矩形与排版度量（单位：像素，scale=1 时）。 */
    public record Glyph(float u0, float v0, float u1, float v1,
                        int w, int h, int offsetX, int offsetY, float advanceX) {
    }

    private final int textureId;
    private final int atlasWidth;
    private final int atlasHeight;
    private final int lineHeight;
    private final Map<Integer, Glyph> glyphs = new HashMap<>();

    /**
     * 探测系统字体并烘焙图集；全部候选不可用时返回 null（调用方降级为无文本渲染）。
     *
     * @param pixelHeight 字形像素高度
     * @param extraChars  额外需要烘焙的字符（自动并入 ASCII 32~126，去重）
     */
    public static FontAtlas loadSystemFont(int pixelHeight, String extraChars) {
        for (String path : FONT_CANDIDATES) {
            if (Files.exists(Path.of(path))) {
                try {
                    FontAtlas atlas = new FontAtlas(path, pixelHeight, extraChars);
                    log.info("字体图集就绪: {} ({} 字形, {}x{}, 行高 {}px)",
                            path, atlas.glyphs.size(), atlas.atlasWidth, atlas.atlasHeight, atlas.lineHeight);
                    return atlas;
                } catch (Exception e) {
                    log.warn("字体 {} 加载失败，尝试下一候选: {}", path, e.getMessage());
                }
            }
        }
        log.warn("未找到可用系统字体，文本渲染禁用");
        return null;
    }

    public FontAtlas(String fontPath, int pixelHeight, String extraChars) throws IOException {
        // ===== 字符集：ASCII 可打印 + 额外字符（去重）=====
        Set<Integer> codePoints = new HashSet<>();
        for (int cp = 32; cp <= 126; cp++) {
            codePoints.add(cp);
        }
        extraChars.codePoints().forEach(codePoints::add);

        // ===== 读字体文件到堆外内存 =====
        byte[] fileBytes = Files.readAllBytes(Path.of(fontPath));
        ByteBuffer ttf = memAlloc(fileBytes.length);
        ttf.put(fileBytes).flip();
        log.info("字体文件已读取: {} ({} 字节, {} 字符待烘焙)", fontPath, fileBytes.length, codePoints.size());

        try (var stack = stackPush()) {
            STBTTFontinfo info = STBTTFontinfo.malloc(stack);
            int offset = fontPath.toLowerCase().endsWith(".ttc")
                    ? (int) stbtt_GetFontOffsetForIndex(ttf, 0) : 0;
            if (!stbtt_InitFont(info, ttf, offset)) {
                throw new IllegalStateException("stbtt_InitFont 失败: " + fontPath);
            }
            float scale = stbtt_ScaleForPixelHeight(info, pixelHeight);

            IntBuffer ascent = stack.mallocInt(1);
            IntBuffer descent = stack.mallocInt(1);
            IntBuffer lineGap = stack.mallocInt(1);
            stbtt_GetFontVMetrics(info, ascent, descent, lineGap);
            lineHeight = (int) Math.ceil((ascent.get(0) - descent.get(0)) * scale);

            // ===== 第一趟：量尺寸，货架式排布 =====
            IntBuffer advance = stack.mallocInt(1);
            IntBuffer lsb = stack.mallocInt(1);
            IntBuffer x0 = stack.mallocInt(1), y0 = stack.mallocInt(1);
            IntBuffer x1 = stack.mallocInt(1), y1 = stack.mallocInt(1);

            record Placement(int cp, int w, int h, int offX, int offY, float adv, int px, int py) {
            }
            // 先收集放置信息
            java.util.List<Placement> placements = new java.util.ArrayList<>();
            atlasWidth = 1024;
            int penX = 1, penY = 1, rowHeight = 0;
            for (int cp : codePoints) {
                stbtt_GetCodepointBitmapBox(info, cp, scale, scale, x0, y0, x1, y1);
                int w = x1.get(0) - x0.get(0);
                int h = y1.get(0) - y0.get(0);
                stbtt_GetCodepointHMetrics(info, cp, advance, lsb);
                float adv = advance.get(0) * scale;
                if (penX + w + 1 > atlasWidth) {   // 换行
                    penX = 1;
                    penY += rowHeight + 1;
                    rowHeight = 0;
                }
                placements.add(new Placement(cp, w, h, x0.get(0), y0.get(0), adv, penX, penY));
                penX += w + 1;
                rowHeight = Math.max(rowHeight, h);
            }
            atlasHeight = nextPow2(penY + rowHeight + 1);
            log.info("字形度量完成: {} 个, 图集 {}x{}", placements.size(), atlasWidth, atlasHeight);

            // ===== 第二趟：光栅化进图集缓冲（单通道）=====
            ByteBuffer atlas = memCalloc(atlasWidth * atlasHeight);
            for (Placement p : placements) {
                if (p.w() <= 0 || p.h() <= 0) {   // 空格等无位图字形：只记度量
                    glyphs.put(p.cp(), new Glyph(0, 0, 0, 0, 0, 0, 0, 0, p.adv()));
                    continue;
                }
                ByteBuffer bmp = stbtt_GetCodepointBitmap(info, scale, scale, p.cp(),
                        stack.mallocInt(1), stack.mallocInt(1), stack.mallocInt(1), stack.mallocInt(1));
                if (bmp == null) {   // 防御：有度量但无位图的异形字形
                    glyphs.put(p.cp(), new Glyph(0, 0, 0, 0, 0, 0, 0, 0, p.adv()));
                    continue;
                }
                for (int row = 0; row < p.h(); row++) {
                    atlas.position((p.py() + row) * atlasWidth + p.px());
                    bmp.position(row * p.w()).limit(row * p.w() + p.w());
                    atlas.put(bmp);
                }
                // 关键：拷贝循环把 bmp.position 推到了末尾，而 stbtt_FreeBitmap 内部
                // 用 memAddress(bmp)（基址+position）作为 free 指针——不复位就是
                // 释放内存块中间的野指针，原生堆挂死。释放前必须 clear()！
                bmp.clear();
                stbtt_FreeBitmap(bmp, NULL);
                glyphs.put(p.cp(), new Glyph(
                        (float) p.px() / atlasWidth, (float) p.py() / atlasHeight,
                        (float) (p.px() + p.w()) / atlasWidth, (float) (p.py() + p.h()) / atlasHeight,
                        p.w(), p.h(), p.offX(), p.offY(), p.adv()));
            }
            atlas.flip();

            // ===== 上传为 GL_R8 纹理 =====
            textureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, textureId);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);   // 单通道必须设 1，否则行对齐错位
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, atlasWidth, atlasHeight, 0,
                    GL_RED, GL_UNSIGNED_BYTE, atlas);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glBindTexture(GL_TEXTURE_2D, 0);
            memFree(atlas);
        } finally {
            memFree(ttf);
        }
    }

    private static int nextPow2(int v) {
        int p = 1;
        while (p < v) {
            p <<= 1;
        }
        return p;
    }

    public Glyph getGlyph(int codePoint) {
        return glyphs.get(codePoint);
    }

    public int getTextureId() {
        return textureId;
    }

    public int getLineHeight() {
        return lineHeight;
    }

    public void cleanup() {
        glDeleteTextures(textureId);
    }
}
