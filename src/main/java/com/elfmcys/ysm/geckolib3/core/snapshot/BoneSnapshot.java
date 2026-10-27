/*
 * Copyright (c) 2020. Author: Bernie G. (Gecko)
 */

package com.elfmcys.ysm.geckolib3.core.snapshot;

import com.elfmcys.ysm.geckolib3.core.processor.BoneView;
import org.joml.Vector3f;

public class BoneSnapshot {
    public final int name;

    public final Vector3f position = new Vector3f();
    public final Vector3f rotation = new Vector3f();
    public final Vector3f scale = new Vector3f(1, 1, 1);

    public boolean hidden;
    public boolean childrenHidden;

    public BoneSnapshot(BoneView bone) {
        copyFrom(bone);
        this.name = bone.getPooledName();
    }

    public void copyFrom(BoneView bone) {
        position.set(bone.getPositionX(), bone.getPositionY(), bone.getPositionZ());
        rotation.set(bone.getRotationX() - bone.getInitialRotationX(), bone.getRotationY() - bone.getInitialRotationY(),
            bone.getRotationZ() - bone.getInitialRotationZ());
        scale.set(bone.getScaleX(), bone.getScaleY(), bone.getScaleZ());

        hidden = bone.areChildrenHidden(); // TODO
        childrenHidden = bone.areChildrenHidden();
    }

    public void copyFrom(BoneSnapshot snapshot) {
        position.set(snapshot.position);
        rotation.set(snapshot.rotation);
        scale.set(snapshot.scale);

        hidden = snapshot.hidden;
        childrenHidden = snapshot.childrenHidden;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o instanceof BoneSnapshot) {
            BoneSnapshot that = (BoneSnapshot)o;
            return name == that.name;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return name;
    }
}
