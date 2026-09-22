package com.mog.audio;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * 音频引擎：OpenAL 封装 + 程序化合成音效（零音频资产依赖）。
 *
 * 设计：
 *   - init 失败（无音频设备，如远程服务器）时静默降级为哑模式，所有播放调用变 no-op
 *   - 演示音效全部运行时合成（正弦波），后续接入 stb_vorbis 加载 .ogg 资产
 *   - SFX 用单个可重触发 source；BGM 用独立 looping source
 */
public class AudioEngine {

    private static final Logger log = LoggerFactory.getLogger(AudioEngine.class);
    private static final int SAMPLE_RATE = 44100;

    private long device;
    private long context;
    private boolean available;

    private int blipBuffer;
    private int padBuffer;
    private int sfxSource;
    private int musicSource;
    private boolean musicPlaying;

    /** 初始化 OpenAL 设备/上下文并合成演示音效；失败时降级为哑模式。 */
    public void init() {
        try {
            device = alcOpenDevice((ByteBuffer) null);
            if (device == NULL) {
                throw new IllegalStateException("alcOpenDevice 返回空（无音频设备）");
            }
            ALCCapabilities deviceCaps = ALC.createCapabilities(device);
            context = alcCreateContext(device, (IntBuffer) null);
            alcMakeContextCurrent(context);
            AL.createCapabilities(deviceCaps);

            blipBuffer = synthToBuffer(AudioEngine::blipSamples, 0.09f);
            padBuffer = synthToBuffer(AudioEngine::padSamples, 4.0f);

            sfxSource = alGenSources();
            alSourcei(sfxSource, AL_BUFFER, blipBuffer);
            alSourcef(sfxSource, AL_GAIN, 0.5f);

            musicSource = alGenSources();
            alSourcei(musicSource, AL_BUFFER, padBuffer);
            alSourcei(musicSource, AL_LOOPING, AL_TRUE);
            alSourcef(musicSource, AL_GAIN, 0.35f);

            available = true;
            log.info("音频引擎就绪: {} (OpenAL 合成音效 x2)", alcGetString(device, ALC_DEVICE_SPECIFIER));
        } catch (Exception e) {
            available = false;
            log.warn("音频不可用，降级为哑模式: {}", e.getMessage());
        }
    }

    /** 播放 UI 提示音（短促下滑 sine blip）。 */
    public void playBlip() {
        if (!available) {
            return;
        }
        alSourceRewind(sfxSource);
        alSourcePlay(sfxSource);
    }

    /** 切换背景氛围音（C 大三和弦软垫，循环）。 */
    public void toggleMusic() {
        if (!available) {
            return;
        }
        if (musicPlaying) {
            alSourcePause(musicSource);
        } else {
            alSourcePlay(musicSource);
        }
        musicPlaying = !musicPlaying;
        log.info("背景音乐{}", musicPlaying ? "播放" : "暂停");
    }

    public boolean isAvailable() {
        return available;
    }

    public void shutdown() {
        if (!available) {
            return;
        }
        alDeleteSources(sfxSource);
        alDeleteSources(musicSource);
        alDeleteBuffers(blipBuffer);
        alDeleteBuffers(padBuffer);
        alcMakeContextCurrent(NULL);
        alcDestroyContext(context);
        alcCloseDevice(device);
        available = false;
    }

    // ==================== 程序化合成 ====================

    /** 采样生成函数：t ∈ [0, duration)，返回 [-1,1] 采样值。 */
    private interface SampleFn {
        float sample(float t);
    }

    /** UI 提示音：880→440Hz 下滑正弦 + 指数衰减包络。 */
    private static float blipSamples(float t) {
        float freq = 880f - 440f * (t / 0.09f);
        float env = (float) Math.exp(-t * 30f);
        return (float) Math.sin(2 * Math.PI * freq * t) * env * 0.6f;
    }

    /** 氛围软垫：C 大三和弦（C4/E4/G4）+ 慢速 LFO 起伏，首尾淡入淡出保证循环无缝。 */
    private static float padSamples(float t) {
        float s = (float) (Math.sin(2 * Math.PI * 261.63 * t)
                + Math.sin(2 * Math.PI * 329.63 * t)
                + Math.sin(2 * Math.PI * 392.00 * t)) / 3f;
        float lfo = 0.75f + 0.25f * (float) Math.sin(2 * Math.PI * 0.25 * t);
        // 循环点交叉淡化：首尾 0.3s 斜坡，消除循环咔哒声
        float fade = Math.min(1f, Math.min(t, 4.0f - t) / 0.3f);
        return s * lfo * fade * 0.5f;
    }

    /** 按生成函数合成 16bit 单声道 PCM 并上传为 AL buffer。 */
    private static int synthToBuffer(SampleFn fn, float duration) {
        int n = (int) (SAMPLE_RATE * duration);
        ShortBuffer pcm = memAllocShort(n);
        for (int i = 0; i < n; i++) {
            float v = Math.max(-1f, Math.min(1f, fn.sample(i / (float) SAMPLE_RATE)));
            pcm.put((short) (v * Short.MAX_VALUE));
        }
        pcm.flip();
        int buffer = alGenBuffers();
        alBufferData(buffer, AL_FORMAT_MONO16, pcm, SAMPLE_RATE);
        memFree(pcm);
        return buffer;
    }
}
