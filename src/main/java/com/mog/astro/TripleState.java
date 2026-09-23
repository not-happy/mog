package com.mog.astro;

import org.joml.Vector3d;

/**
 * 三重星初始状态快照（纯数据对象，无逻辑）。
 * 由 {@link TripleConfigs} 工厂生成，{@link GravitySimulation} 负责应用。
 */
public final class TripleState {

    public final double[] mass = new double[3];
    public final Vector3d[] starPos = {new Vector3d(), new Vector3d(), new Vector3d()};
    public final Vector3d[] starVel = {new Vector3d(), new Vector3d(), new Vector3d()};
    public final Vector3d planetPos = new Vector3d();
    public final Vector3d planetVel = new Vector3d();
}
