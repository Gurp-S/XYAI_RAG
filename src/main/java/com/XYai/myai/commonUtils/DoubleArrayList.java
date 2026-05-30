package com.XYai.myai.commonUtils;

import java.util.AbstractList;
import java.util.RandomAccess;


public class DoubleArrayList extends AbstractList<Double> implements RandomAccess {
    private final double[] array;

    public DoubleArrayList(double[] array) {
        this.array = array;
    }

    @Override
    public Double get(int index) {
        return array[index];
    }

    @Override
    public int size() {
        return array.length;
    }
}
