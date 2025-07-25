/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.vectors;

import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.VectorUtil;
import org.elasticsearch.simdvec.ESVectorUtil;

import static org.apache.lucene.index.VectorSimilarityFunction.COSINE;
import static org.apache.lucene.index.VectorSimilarityFunction.EUCLIDEAN;

public class OptimizedScalarQuantizer {
    public static void initInterval(byte bits, float vecStd, float vecMean, float min, float max, float[] initInterval) {
        initInterval[0] = (float) clamp(MINIMUM_MSE_GRID[bits - 1][0] * vecStd + vecMean, min, max);
        initInterval[1] = (float) clamp(MINIMUM_MSE_GRID[bits - 1][1] * vecStd + vecMean, min, max);
    }

    // The initial interval is set to the minimum MSE grid for each number of bits
    // these starting points are derived from the optimal MSE grid for a uniform distribution
    static final float[][] MINIMUM_MSE_GRID = new float[][] {
        { -0.798f, 0.798f },
        { -1.493f, 1.493f },
        { -2.051f, 2.051f },
        { -2.514f, 2.514f },
        { -2.916f, 2.916f },
        { -3.278f, 3.278f },
        { -3.611f, 3.611f },
        { -3.922f, 3.922f } };
    public static final float DEFAULT_LAMBDA = 0.1f;
    private static final int DEFAULT_ITERS = 5;
    private final VectorSimilarityFunction similarityFunction;
    private final float lambda;
    private final int iters;
    private final float[] statsScratch;
    private final float[] gridScratch;
    private final float[] intervalScratch;

    public OptimizedScalarQuantizer(VectorSimilarityFunction similarityFunction, float lambda, int iters) {
        this.similarityFunction = similarityFunction;
        this.lambda = lambda;
        this.iters = iters;
        this.statsScratch = new float[similarityFunction == EUCLIDEAN ? 5 : 6];
        this.gridScratch = new float[5];
        this.intervalScratch = new float[2];
    }

    public OptimizedScalarQuantizer(VectorSimilarityFunction similarityFunction) {
        this(similarityFunction, DEFAULT_LAMBDA, DEFAULT_ITERS);
    }

    public record QuantizationResult(float lowerInterval, float upperInterval, float additionalCorrection, int quantizedComponentSum) {}

    public QuantizationResult[] multiScalarQuantize(float[] vector, int[][] destinations, byte[] bits, float[] centroid) {
        assert similarityFunction != COSINE || VectorUtil.isUnitVector(vector);
        assert similarityFunction != COSINE || VectorUtil.isUnitVector(centroid);
        assert bits.length == destinations.length;
        if (similarityFunction == EUCLIDEAN) {
            ESVectorUtil.centerAndCalculateOSQStatsEuclidean(vector, centroid, vector, statsScratch);
        } else {
            ESVectorUtil.centerAndCalculateOSQStatsDp(vector, centroid, vector, statsScratch);
        }
        float vecMean = statsScratch[0];
        float vecVar = statsScratch[1];
        float norm2 = statsScratch[2];
        float min = statsScratch[3];
        float max = statsScratch[4];
        float vecStd = (float) Math.sqrt(vecVar);
        QuantizationResult[] results = new QuantizationResult[bits.length];
        for (int i = 0; i < bits.length; ++i) {
            assert bits[i] > 0 && bits[i] <= 8;
            int points = (1 << bits[i]);
            // Linearly scale the interval to the standard deviation of the vector, ensuring we are within the min/max bounds
            initInterval(bits[i], vecStd, vecMean, min, max, intervalScratch);
            boolean hasQuantization = optimizeIntervals(intervalScratch, destinations[i], vector, norm2, points);
            // Now we have the optimized intervals, quantize the vector
            int sumQuery;
            if (hasQuantization) {
                sumQuery = getSumQuery(destinations[i]);
            } else {
                sumQuery = ESVectorUtil.quantizeVectorWithIntervals(
                    vector,
                    destinations[i],
                    intervalScratch[0],
                    intervalScratch[1],
                    bits[i]
                );
            }
            results[i] = new QuantizationResult(
                intervalScratch[0],
                intervalScratch[1],
                similarityFunction == EUCLIDEAN ? norm2 : statsScratch[5],
                sumQuery
            );
        }
        return results;
    }

    public QuantizationResult scalarQuantize(float[] vector, int[] destination, byte bits, float[] centroid) {
        assert similarityFunction != COSINE || VectorUtil.isUnitVector(vector);
        assert similarityFunction != COSINE || VectorUtil.isUnitVector(centroid);
        assert vector.length <= destination.length;
        assert bits > 0 && bits <= 8;
        int points = 1 << bits;
        if (similarityFunction == EUCLIDEAN) {
            ESVectorUtil.centerAndCalculateOSQStatsEuclidean(vector, centroid, vector, statsScratch);
        } else {
            ESVectorUtil.centerAndCalculateOSQStatsDp(vector, centroid, vector, statsScratch);
        }
        float vecMean = statsScratch[0];
        float vecVar = statsScratch[1];
        float norm2 = statsScratch[2];
        float min = statsScratch[3];
        float max = statsScratch[4];
        float vecStd = (float) Math.sqrt(vecVar);
        // Linearly scale the interval to the standard deviation of the vector, ensuring we are within the min/max bounds
        initInterval(bits, vecStd, vecMean, min, max, intervalScratch);
        boolean hasQuantization = optimizeIntervals(intervalScratch, destination, vector, norm2, points);
        // Now we have the optimized intervals, quantize the vector
        int sumQuery;
        if (hasQuantization) {
            sumQuery = getSumQuery(destination);
        } else {
            sumQuery = ESVectorUtil.quantizeVectorWithIntervals(vector, destination, intervalScratch[0], intervalScratch[1], bits);
        }
        return new QuantizationResult(
            intervalScratch[0],
            intervalScratch[1],
            similarityFunction == EUCLIDEAN ? norm2 : statsScratch[5],
            sumQuery
        );
    }

    /**
     * Optimize the quantization interval for the given vector. This is done via a coordinate descent trying to minimize the quantization
     * loss. Note, the loss is not always guaranteed to decrease, so we have a maximum number of iterations and will exit early if the
     * loss increases.
     * @param initInterval initial interval, the optimized interval will be stored here
     * @param vector raw vector
     * @param norm2 squared norm of the vector
     * @param points number of quantization points
     *
     * @return true if {@param destination} contains the quantize vector and we can skip the quantization.
     */
    private boolean optimizeIntervals(float[] initInterval, int[] destination, float[] vector, float norm2, int points) {
        double initialLoss = ESVectorUtil.calculateOSQLoss(vector, initInterval[0], initInterval[1], points, norm2, lambda, destination);
        final float scale = (1.0f - lambda) / norm2;
        if (Float.isFinite(scale) == false) {
            return true;
        }
        for (int i = 0; i < iters; ++i) {
            // calculate the grid points for coordinate descent
            ESVectorUtil.calculateOSQGridPoints(vector, destination, points, gridScratch);
            float daa = gridScratch[0];
            float dab = gridScratch[1];
            float dbb = gridScratch[2];
            float dax = gridScratch[3];
            float dbx = gridScratch[4];
            double m0 = scale * dax * dax + lambda * daa;
            double m1 = scale * dax * dbx + lambda * dab;
            double m2 = scale * dbx * dbx + lambda * dbb;
            // its possible that the determinant is 0, in which case we can't update the interval
            double det = m0 * m2 - m1 * m1;
            if (det == 0) {
                return true;
            }
            float aOpt = (float) ((m2 * dax - m1 * dbx) / det);
            float bOpt = (float) ((m0 * dbx - m1 * dax) / det);
            // If there is no change in the interval, we can stop
            if ((Math.abs(initInterval[0] - aOpt) < 1e-8 && Math.abs(initInterval[1] - bOpt) < 1e-8)) {
                return true;
            }
            double newLoss = ESVectorUtil.calculateOSQLoss(vector, aOpt, bOpt, points, norm2, lambda, destination);
            // If the new loss is worse, don't update the interval and exit
            // This optimization, unlike kMeans, does not always converge to better loss
            // So exit if we are getting worse
            if (newLoss > initialLoss) {
                return false;
            }
            // Update the interval and go again
            initInterval[0] = aOpt;
            initInterval[1] = bOpt;
            initialLoss = newLoss;
        }
        return true;
    }

    private static int getSumQuery(int[] quantize) {
        int sum = 0;
        for (int q : quantize) {
            sum += q;
        }
        return sum;
    }

    private static double clamp(double x, double a, double b) {
        return Math.min(Math.max(x, a), b);
    }

}
