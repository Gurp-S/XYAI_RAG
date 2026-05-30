package com.XYai.myai.commonUtils;

import java.util.AbstractList;
import java.util.RandomAccess;

public class FloatArrayList extends AbstractList<Float> implements RandomAccess {
    private final float[] array;

    public FloatArrayList(float[] array) {
        this.array = array;
    }

    @Override
    public Float get(int index) {
        return array[index];
    }

    @Override
    public int size() {
        return array.length;
    }
}
